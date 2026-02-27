package com.wshg.voice.controller;

import com.wshg.voice.config.VoiceProperties;
import com.wshg.voice.dto.ChatRequest;
import com.wshg.voice.dto.ChatResponse;
import com.wshg.voice.dto.VoiceUploadResponse;
import com.wshg.voice.dto.QwenTtsResponse;
import com.wshg.voice.service.VoicePipelineService;
import com.wshg.voice.service.VectorStoreService;
import com.wshg.voice.service.ChatHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.net.URI;

/**
 * 语音上传接口：接收 ESP32 上传的 WAV，执行 ASR → LLM → TTS，返回文字与音频 URL。
 */
@Slf4j
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceUploadController {

    private final VoicePipelineService pipelineService;
    private final VoiceProperties voiceProperties;
    private final RestTemplate restTemplate;
    private final VectorStoreService vectorStoreService;
    private final ChatHistoryService chatHistoryService;

    /** 健康检查，确认服务已启动 */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "voice-assistant-api");
    }

    /**
     * 上传 WAV 文件（multipart 字段名：file）。
     * 响应：{ "text": "识别文字", "reply": "大模型回复", "audioUrl": "http://.../tts/xxx.wav" }
     * 或 { "error": "错误信息" }。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VoiceUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {

        log.info("[API] POST /api/voice/upload fileSize={}", file != null ? file.getSize() : 0);
        if (file.isEmpty()) {
            log.warn("[API] /upload 拒绝: 文件为空");
            return ResponseEntity.badRequest()
                    .body(VoiceUploadResponse.error("请上传音频文件"));
        }
        if (file.getSize() > voiceProperties.getMaxUploadSize()) {
            log.warn("[API] /upload 拒绝: 文件过大 size={}", file.getSize());
            return ResponseEntity.badRequest()
                    .body(VoiceUploadResponse.error("文件过大，最大 " + (voiceProperties.getMaxUploadSize() / 1024 / 1024) + "MB"));
        }

        String baseUrl = buildBaseUrl(request);
        VoiceUploadResponse resp = pipelineService.process(file, baseUrl);

        if (resp.getError() != null) {
            log.warn("[API] /upload 处理失败: {}", resp.getError());
            return ResponseEntity.unprocessableEntity().body(resp);
        }
        log.info("[API] /upload 成功 text={}, audioUrl={}", resp.getText(), resp.getAudioUrl());
        return ResponseEntity.ok(resp);
    }

    /**
     * 单独文字转语音。
     * POST /api/voice/tts  Body: { "text": "要合成的文字" }
     * 或 GET /api/voice/tts?text=要合成的文字
     * 响应：{ "audioUrl": "http://.../tts/xxx.wav" }，失败返回 502 与 error。
     */
    @PostMapping("/tts")
    public ResponseEntity<Map<String, Object>> ttsPost(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String text = body != null ? (String) body.get("text") : null;
        return doTts(text, request);
    }

    @GetMapping("/tts")
    public ResponseEntity<Map<String, Object>> ttsGet(@RequestParam("text") String text, HttpServletRequest request) {
        return doTts(text, request);
    }

    private ResponseEntity<Map<String, Object>> doTts(String text, HttpServletRequest request) {
        log.info("[API] 文字转语音 textLength={}", text != null ? text.length() : 0);
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }
        String filename = pipelineService.synthesize(text);
        if (filename == null) {
            log.warn("[API] /tts 合成失败");
            return ResponseEntity.status(502).body(Map.of("error", "TTS 合成失败，请检查本地 PaddleSpeech 或线上 qwen-api-key"));
        }
        String baseUrl = buildBaseUrl(request);
        String audioUrl = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "tts/" + filename;
        log.info("[API] /tts 成功 audioUrl={}", audioUrl);
        return ResponseEntity.ok(Map.of("audioUrl", audioUrl, "filename", filename));
    }

    /**
     * 直接调用千问大模型（DashScope OpenAI 兼容接口）。
     * GET /api/voice/qwen?text=xxx
     * 流程：文字 → Qwen LLM → Qwen-TTS → 本地 /tts/xxx.wav。
     */
    @GetMapping("/qwen")
    public ResponseEntity<Map<String, Object>> callQwen(@RequestParam("text") String text,
                                                        HttpServletRequest request) {
        log.info("[API] GET /api/voice/qwen text={}", text != null && text.length() > 50 ? text.substring(0, 50) + "..." : text);
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }

        // RAG：检索向量库，将相关文档拼入 system prompt
        String systemContent = "You are a helpful assistant.";
        String context = null;
        if (voiceProperties.isRagEnabled()) {
            context = vectorStoreService.buildRagContext(text, voiceProperties.getRagTopK());
            if (context != null && !context.isBlank()) {
                systemContent = "参考以下知识库内容回答用户问题。如知识库无相关内容，可凭自身知识回答。\n\n【知识库】\n" + context;
            }
        }
        var messages = java.util.List.of(
                new ChatRequest.Message("system", systemContent),
                new ChatRequest.Message("user", text)
        );

        String baseUrl = voiceProperties.getQwenBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
        }
        // 去掉末尾斜杠，拼接 /v1/chat/completions
        baseUrl = baseUrl.replaceAll("/$", "");
        String url = baseUrl + "/v1/chat/completions";

        String model = voiceProperties.getQwenModel();
        if (model == null || model.isBlank()) {
            model = "qwen-plus";
        }

        ChatRequest req = ChatRequest.builder()
                .model(model)
                .messages(messages)
                .maxTokens(1024)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String apiKey = voiceProperties.getQwenApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(500).body(Map.of("error", "未配置 DashScope API Key（voice.qwen-api-key）"));
        }
        headers.setBearerAuth(apiKey);

        HttpEntity<ChatRequest> entity = new HttpEntity<>(req, headers);

        ResponseEntity<ChatResponse> res = restTemplate.postForEntity(url, entity, ChatResponse.class);
        if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
            return ResponseEntity.status(502).body(Map.of("error", "调用千问文本接口失败，HTTP 状态：" + res.getStatusCode()));
        }

        String content = res.getBody().getFirstContent();
        if (content == null || content.isBlank()) {
            return ResponseEntity.status(502).body(Map.of("error", "千问文本接口未返回内容"));
        }
        String replyText = content.trim();

        // 2. 调用千问 TTS，将文本转为音频（阿里云 Base64 或云端 URL）
        TtsResult ttsResult = callQwenTts(replyText, apiKey);

        // 3. 将云端音频「落地」到本地 tts 目录，并暴露为 /tts/xxx.wav（与 PaddleSpeech 情况保持一致）
        String serviceBaseUrl = buildBaseUrl(request);
        String localAudioUrl = null;
        if (ttsResult != null && (!isBlank(ttsResult.base64) || !isBlank(ttsResult.url))) {
            String localFileName = "qwen_" + System.currentTimeMillis() + "_" +
                    UUID.randomUUID().toString().substring(0, 8) + ".wav";
            Path ttsDir = voiceProperties.getTtsDirPath();
            Path ttsPath = ttsDir.resolve(localFileName);
            try {
                Files.createDirectories(ttsDir);
                byte[] audioBytes = null;
                if (!isBlank(ttsResult.base64)) {
                    audioBytes = Base64.getDecoder().decode(ttsResult.base64);
                } else if (!isBlank(ttsResult.url)) {
                    // 使用 URI 避免 RestTemplate 对已签名 URL 再次编码，导致 SignatureDoesNotMatch
                    ResponseEntity<byte[]> fileRes = restTemplate.getForEntity(URI.create(ttsResult.url), byte[].class);
                    if (fileRes.getStatusCode().is2xxSuccessful() && fileRes.getBody() != null) {
                        audioBytes = fileRes.getBody();
                    }
                }
                if (audioBytes != null && audioBytes.length > 0) {
                    Files.write(ttsPath, audioBytes);
                    localAudioUrl = (serviceBaseUrl.endsWith("/") ? serviceBaseUrl : serviceBaseUrl + "/") + "tts/" + localFileName;
                } else {
                    log.warn("[API] /qwen 千问 TTS 未返回有效音频数据，仅返回文本");
                }
            } catch (IOException e) {
                log.warn("[API] /qwen 保存本地 TTS 文件失败，仅返回文本", e);
            }
        } else {
            log.warn("[API] /qwen 千问 TTS 接口未返回音频数据，仅返回文本");
        }

        // 记录聊天到数据库与向量库
        try {
            boolean ragUsed = voiceProperties.isRagEnabled();
            String answerSource = ragUsed ? "RAG-知识库" : "LLM";
            chatHistoryService.logChat(
                    text,
                    replyText,
                    "qwen-text",
                    answerSource,
                    context
            );
        } catch (Exception e) {
            log.warn("[API] /qwen 写入聊天记录失败", e);
        }

        // 返回文本 + 本地音频 URL（如 TTS 失败则 audioUrl 可能为空）
        if (localAudioUrl != null) {
            return ResponseEntity.ok(Map.of(
                    "text", replyText,
                    "audioUrl", localAudioUrl
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                    "text", replyText
            ));
        }
    }

    /**
     * 新接口：上传麦克风录音 → 使用 Qwen3-ASR-Flash 识别文字 → 复用 /qwen 流程（LLM + Qwen-TTS + 本地 /tts/xxx.wav）。
     * POST /api/voice/qwen-asr-upload
     * form-data 字段名：file（WAV/MP3 等小音频）
     */
    @PostMapping(value = "/qwen-asr-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadAndCallQwen(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {

        log.info("[API] POST /api/voice/qwen-asr-upload fileSize={}", file != null ? file.getSize() : 0);
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请上传音频文件"));
        }
        if (file.getSize() > voiceProperties.getMaxUploadSize()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "文件过大，最大 " + (voiceProperties.getMaxUploadSize() / 1024 / 1024) + "MB"));
        }

        String apiKey = voiceProperties.getQwenApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(500).body(Map.of("error", "未配置 DashScope API Key（voice.qwen-api-key）"));
        }

        // 1. 使用 Qwen3-ASR-Flash 将音频转成文本
        String asrText = callQwenAsr(file, apiKey);
        if (asrText == null || asrText.isBlank()) {
            log.warn("[API] /qwen-asr-upload ASR 无结果");
            return ResponseEntity.status(502).body(Map.of("error", "Qwen3-ASR-Flash 识别失败或无结果"));
        }
        log.info("[API] /qwen-asr-upload ASR 结果: {}", asrText);

        // 2. 复用 callQwen 流程：ASR 文本 → LLM → Qwen-TTS → 本地 /tts/xxx.wav
        return callQwen(asrText.trim(), request);
    }

    private record TtsResult(String base64, String url) {}

    /**
     * 通过 DashScope Qwen-TTS HTTP 接口调用 qwen3-tts-flash，将文本转为音频。
     * 参考官方文档：https://www.alibabacloud.com/help/en/model-studio/qwen-tts-api
     */
    private TtsResult callQwenTts(String text, String apiKey) {
        if (isBlank(text) || isBlank(apiKey)) {
            return null;
        }
        // 为避免超过千问 TTS 的长度限制，这里做一次保守截断（约 300 字作为语音预览）
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }
        String ttsUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

        // 按官方 curl 示例构造 input
        Map<String, Object> input = Map.of(
                "text", text,
                "voice", "Cherry",
                "language_type", "Chinese"
        );
        // 使用 qwen3-tts-flash；如需指令控制，可换成 qwen3-tts-instruct-flash 并增加 instructions
        Map<String, Object> body = Map.of(
                "model", "qwen3-tts-flash",
                "input", input
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<QwenTtsResponse> res =
                    restTemplate.postForEntity(ttsUrl, entity, QwenTtsResponse.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                return null;
            }
            QwenTtsResponse bodyObj = res.getBody();
            String data = bodyObj.getAudioData();
            String url = bodyObj.getAudioUrl();
            return new TtsResult(data, url);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 使用 Qwen3-ASR-Flash 将上传的音频转为文本（OpenAI 兼容模式，Base64 Data URL）。
     * 参考官方文档：https://www.alibabacloud.com/help/en/model-studio/qwen-asr-api-reference
     */
    private String callQwenAsr(MultipartFile file, String apiKey) {
        try {
            byte[] audioBytes = file.getBytes();
            if (audioBytes.length == 0) return null;

            String base64 = Base64.getEncoder().encodeToString(audioBytes);
            // 这里假设上传的是 WAV，如为 MP3 可改为 audio/mpeg
            String dataUri = "data:audio/wav;base64," + base64;

            String baseUrl = voiceProperties.getQwenBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
            }
            baseUrl = baseUrl.replaceAll("/$", "");
            String url = baseUrl + "/v1/chat/completions";

            // 构造 OpenAI 兼容请求体
            Map<String, Object> inputAudio = Map.of(
                    "type", "input_audio",
                    "input_audio", Map.of("data", dataUri)
            );
            Map<String, Object> userMsg = Map.of(
                    "role", "user",
                    "content", java.util.List.of(inputAudio)
            );

            Map<String, Object> body = Map.of(
                    "model", "qwen3-asr-flash",
                    "messages", java.util.List.of(userMsg),
                    "stream", false,
                    "asr_options", Map.of("enable_itn", false)
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<ChatResponse> res =
                    restTemplate.postForEntity(url, entity, ChatResponse.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                return null;
            }
            return res.getBody().getFirstContent();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String buildBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int port = request.getServerPort();
        String contextPath = request.getContextPath();
        if ("http".equals(scheme) && port == 80 || "https".equals(scheme) && port == 443) {
            return scheme + "://" + serverName + (contextPath.isEmpty() ? "" : contextPath);
        }
        return scheme + "://" + serverName + ":" + port + (contextPath.isEmpty() ? "" : contextPath);
    }
}
