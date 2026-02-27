package com.wshg.voice.service;

import com.wshg.voice.config.VoiceProperties;
import com.wshg.voice.dto.ChatRequest;
import com.wshg.voice.dto.ChatResponse;
import com.wshg.voice.dto.QwenTtsResponse;
import com.wshg.voice.dto.VoiceUploadResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 语音管道：上传 WAV → ASR → 大模型 → TTS → 返回音频 URL。
 * 本地模式：PaddleSpeech(语音转文本/文本转语音) + vLLM(DeepSeek-R1)；线上模式：阿里云 ASR/LLM/TTS。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoicePipelineService {

    private final VoiceProperties props;
    private final RestTemplate restTemplate;
    private final VectorStoreService vectorStoreService;
    private final DeviceControlService deviceControlService;
    private final ChatHistoryService chatHistoryService;

    /**
     * 执行完整管道，返回识别文字、回复文字、TTS 文件名（不含路径，用于拼 audioUrl）。
     * 根据配置自动切换本地（PaddleSpeech+vLLM）或线上（阿里云）。
     */
    public VoiceUploadResponse process(MultipartFile file, String audioBaseUrl) {
        String mode = props.isLocal() ? "local" : "online";
        log.info("[管道] 开始处理, mode={}, fileSize={} bytes", mode, file != null ? file.getSize() : 0);

        if (props.isMock()) {
            log.info("[管道] Mock 模式，直接返回示例");
            return mockResponse(audioBaseUrl);
        }

        Path tempDir = props.getTempDirPath();
        Path ttsDir = props.getTtsDirPath();
        try {
            Files.createDirectories(tempDir);
            Files.createDirectories(ttsDir);
        } catch (IOException e) {
            log.warn("创建目录失败", e);
            return VoiceUploadResponse.error("创建临时目录失败: " + e.getMessage());
        }

        String prefix = "voice_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        Path wavPath = tempDir.resolve(prefix + ".wav");
        Path ttsInputPath = tempDir.resolve(prefix + "_tts_input.txt");

        try {
            file.transferTo(wavPath.toFile());
        } catch (IOException e) {
            log.warn("保存上传文件失败", e);
            return VoiceUploadResponse.error("保存上传文件失败: " + e.getMessage());
        }

        try {
            String userText;
            String reply;
            String ttsFileName = prefix + ".wav";
            Path ttsPath = ttsDir.resolve(ttsFileName);

            String ragContext = null;

            if (props.isLocal()) {
                // 本地：PaddleSpeech(ASR + TTS) → vLLM
                userText = runAsrLocal(wavPath);
                if (userText == null || userText.isBlank()) {
                    return VoiceUploadResponse.error("语音识别无结果，请重试");
                }
                userText = userText.trim();
                log.info("[管道] ASR 结果: {}", userText);

                reply = callLlmLocal(userText);
                if (reply == null || reply.isBlank()) {
                    return VoiceUploadResponse.error("大模型无回复");
                }
                reply = reply.trim();
                log.info("[管道] LLM 回复(length={}): {}", reply.length(), reply);
                reply = executeDeviceControlAndStrip(reply);
                log.info("[管道] 设备控制处理后 TTS 文案(length={}): {}", reply != null ? reply.length() : 0, reply);

                Files.writeString(ttsInputPath, reply, StandardCharsets.UTF_8);
                if (!runTtsLocal(ttsInputPath, ttsPath)) {
                    log.warn("[管道] 本地 TTS 合成失败");
                    return VoiceUploadResponse.error("TTS 合成失败");
                }
            } else {
                // 线上：阿里云 ASR → 千问 LLM → 阿里云 TTS
                String apiKey = props.getQwenApiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    return VoiceUploadResponse.error("线上模式请配置 voice.qwen-api-key");
                }
                userText = runAsrOnline(wavPath, apiKey);
                if (userText == null || userText.isBlank()) {
                    return VoiceUploadResponse.error("语音识别无结果，请重试");
                }
                userText = userText.trim();
                log.info("[管道] ASR 结果: {}", userText);

                reply = callLlmOnline(userText, apiKey);
                if (reply == null || reply.isBlank()) {
                    return VoiceUploadResponse.error("大模型无回复");
                }
                reply = reply.trim();
                log.info("[管道] LLM 回复(length={}): {}", reply.length(), reply);
                reply = executeDeviceControlAndStrip(reply);
                log.info("[管道] 设备控制处理后 TTS 文案(length={}): {}", reply != null ? reply.length() : 0, reply);

                if (!runTtsOnline(reply, apiKey, ttsPath)) {
                    log.warn("[管道] 线上 TTS 合成失败");
                    return VoiceUploadResponse.error("TTS 合成失败");
                }
            }

            String audioUrl = (audioBaseUrl.endsWith("/") ? audioBaseUrl : audioBaseUrl + "/") + "tts/" + ttsFileName;
            log.info("[管道] 处理完成, userText={}, replyLength={}, audioUrl={}", userText, reply != null ? reply.length() : 0, audioUrl);

            // 记录聊天到数据库与向量库
            try {
                boolean ragUsed = props.isRagEnabled();
                String answerSource = ragUsed ? "RAG-知识库" : "LLM";
                chatHistoryService.logChat(
                        userText,
                        reply,
                        props.isLocal() ? "voice-local" : "voice-online",
                        answerSource,
                        ragContext
                );
            } catch (Exception e) {
                log.warn("[管道] 写入聊天记录失败", e);
            }
            return VoiceUploadResponse.builder()
                    .text(userText)
                    .reply(reply)
                    .audioUrl(audioUrl)
                    .build();
        } catch (Exception e) {
            log.error("[管道] 执行异常", e);
            return VoiceUploadResponse.error("处理失败: " + e.getMessage());
        } finally {
            safeDelete(wavPath);
            safeDelete(ttsInputPath);
        }
    }

    private VoiceUploadResponse mockResponse(String audioBaseUrl) {
        String filename = "mock_reply.wav";
        return VoiceUploadResponse.builder()
                .text("（Mock）你好")
                .reply("（Mock）你好，我是语音助手。")
                .audioUrl((audioBaseUrl.endsWith("/") ? audioBaseUrl : audioBaseUrl + "/") + "tts/" + filename)
                .build();
    }

    /**
     * 本地语音转文本：PaddleSpeech（百度开源，pip/conda 安装）。
     * 命令：paddlespeech asr --lang zh --input file.wav
     */
    private String runAsrLocal(Path wavPath) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                props.getPaddlespeechCmd(),
                "asr",
                "--lang", "zh",
                "--input", wavPath.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean ok = p.waitFor(60, TimeUnit.SECONDS);
        if (!ok) {
            p.destroyForcibly();
            throw new IOException("ASR 超时");
        }
        if (p.exitValue() != 0) {
            log.warn("ASR 非零退出: {}, 输出: {}", p.exitValue(), out);
        }
        return out;
    }

    private String callLlmLocal(String userText) {
        List<ChatRequest.Message> messages = buildMessagesWithRag(userText);
        logMessagesToLlm(messages);
        String url = props.getVllmBaseUrl().replaceAll("/$", "") + "/v1/chat/completions";
        ChatRequest req = ChatRequest.builder()
                .model(props.getVllmModel())
                .messages(messages)
                .maxTokens(512)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<ChatRequest> entity = new HttpEntity<>(req, headers);
        ResponseEntity<ChatResponse> res = restTemplate.postForEntity(url, entity, ChatResponse.class);
        if (res.getBody() == null) return null;
        return res.getBody().getFirstContent();
    }

    /** 从已落盘的 WAV 文件调用线上 ASR，避免 MultipartFile 临时文件被清理导致 NoSuchFileException */
    private String runAsrOnline(Path wavPath, String apiKey) {
        try {
            byte[] audioBytes = Files.readAllBytes(wavPath);
            if (audioBytes.length == 0) return null;
            return runAsrOnlineWithBytes(audioBytes, apiKey);
        } catch (IOException e) {
            log.warn("线上 ASR 读取文件异常", e);
            return null;
        }
    }

    private String runAsrOnlineWithBytes(byte[] audioBytes, String apiKey) {
        try {
            String base64 = Base64.getEncoder().encodeToString(audioBytes);
            String dataUri = "data:audio/wav;base64," + base64;
            String baseUrl = props.getQwenBaseUrl() != null && !props.getQwenBaseUrl().isBlank()
                    ? props.getQwenBaseUrl() : "https://dashscope.aliyuncs.com/compatible-mode";
            baseUrl = baseUrl.replaceAll("/$", "");
            String url = baseUrl + "/v1/chat/completions";
            Map<String, Object> inputAudio = Map.of(
                    "type", "input_audio",
                    "input_audio", Map.of("data", dataUri)
            );
            Map<String, Object> userMsg = Map.of(
                    "role", "user",
                    "content", List.of(inputAudio)
            );
            Map<String, Object> body = Map.of(
                    "model", "qwen3-asr-flash",
                    "messages", List.of(userMsg),
                    "stream", false,
                    "asr_options", Map.of("enable_itn", false)
            );
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<ChatResponse> res = restTemplate.postForEntity(url, entity, ChatResponse.class);
            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                return res.getBody().getFirstContent();
            }
        } catch (Exception e) {
            log.warn("线上 ASR 异常", e);
        }
        return null;
    }

    private String callLlmOnline(String userText, String apiKey) {
        List<ChatRequest.Message> messages = buildMessagesWithRag(userText);
        logMessagesToLlm(messages);
        String baseUrl = props.getQwenBaseUrl() != null && !props.getQwenBaseUrl().isBlank()
                ? props.getQwenBaseUrl() : "https://dashscope.aliyuncs.com/compatible-mode";
        String url = baseUrl.replaceAll("/$", "") + "/v1/chat/completions";
        String model = props.getQwenModel() != null && !props.getQwenModel().isBlank() ? props.getQwenModel() : "qwen-plus";
        ChatRequest req = ChatRequest.builder()
                .model(model)
                .messages(messages)
                .maxTokens(512)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<ChatRequest> entity = new HttpEntity<>(req, headers);
        ResponseEntity<ChatResponse> res = restTemplate.postForEntity(url, entity, ChatResponse.class);
        if (res.getBody() == null) return null;
        return res.getBody().getFirstContent();
    }

    private boolean runTtsOnline(String text, String apiKey, Path outWavPath) {
        try {
            byte[] merged = synthesizeOnlineToBytes(text, apiKey);
            if (merged == null || merged.length == 0) {
                return false;
            }
            Files.createDirectories(outWavPath.getParent());
            Files.write(outWavPath, merged);
            return true;
        } catch (Exception e) {
            log.warn("线上 TTS 异常", e);
            return false;
        }
    }

    /** 打印最终发送给大模型的消息（system + user），便于排查 */
    private void logMessagesToLlm(List<ChatRequest.Message> messages) {
        if (messages == null) return;
        for (int i = 0; i < messages.size(); i++) {
            ChatRequest.Message m = messages.get(i);
            String role = m != null && m.getRole() != null ? m.getRole() : "";
            String content = m != null && m.getContent() != null ? m.getContent() : "";
            log.info("[管道] 发送给大模型 message[{}] role={} contentLen={} content={}", i, role, content.length(), content);
        }
    }

    private List<ChatRequest.Message> buildMessagesWithRag(String userText) {
        String systemPrompt;
        if (props.isRagEnabled()) {
            String context = vectorStoreService.buildRagContext(userText, props.getRagTopK());
            systemPrompt = (context != null && !context.isBlank())
                    ? "严格根据以下【知识库】内容和用户问题作答：仅使用知识库中已有的信息，不要编造、不要猜测。若知识库中无与问题相关的内容，请明确回答「根据当前知识库暂无相关内容」或「不知道」，不要胡说八道。\n\n【知识库】\n" + context
                    : "你是一个助手。若无法确定答案，请明确说不知道，不要编造。";
        } else {
            systemPrompt = "You are a helpful assistant.";
        }
        return List.of(
                new ChatRequest.Message("system", systemPrompt),
                new ChatRequest.Message("user", userText)
        );
    }

    /** 若 LLM 回复中含 [DEVICE_CTL]，则异步下发设备指令（不阻塞），并移除该行后返回用于 TTS 的文案 */
    private String executeDeviceControlAndStrip(String reply) {
        var intent = deviceControlService.parseIntent(reply);
        if (intent != null) {
            log.info("[设备控制] 解析到意图: room={}, action={}，异步下发", intent.room(), intent.turnOn() ? "on" : "off");
            deviceControlService.executeByRoomAsync(intent.room(), intent.turnOn());
        } else {
            log.debug("[设备控制] 回复中无 [DEVICE_CTL]，跳过设备控制");
        }
        return deviceControlService.stripDeviceControlLine(reply);
    }

    private static final int TTS_MAX_INPUT_LENGTH = 500;

    /**
     * 单独文字转语音：根据当前配置（本地 PaddleSpeech / 线上阿里云）合成并保存到 tts 目录。
     * @param text 待合成文本，过长会截断（本地 500 字，线上 500 字）
     * @return 生成的文件名（如 tts_123_abc.wav），供拼 audioUrl；失败返回 null
     */
    public String synthesize(String text) {
        if (text == null || text.isBlank()) {
            log.warn("[TTS] 文本为空");
            return null;
        }
        Path ttsDir = props.getTtsDirPath();
        try {
            Files.createDirectories(ttsDir);
        } catch (IOException e) {
            log.warn("[TTS] 创建 tts 目录失败", e);
            return null;
        }
        String filename = "tts_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + ".wav";
        Path ttsPath = ttsDir.resolve(filename);

        boolean ok;
        if (props.isLocal()) {
            Path tempInput = props.getTempDirPath().resolve("tts_in_" + System.currentTimeMillis() + ".txt");
            try {
                Files.writeString(tempInput, text, StandardCharsets.UTF_8);
                ok = runTtsLocal(tempInput, ttsPath);
            } catch (IOException e) {
                log.warn("[TTS] 写入临时文件失败", e);
                ok = false;
            } finally {
                safeDelete(tempInput);
            }
        } else {
            String apiKey = props.getQwenApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("[TTS] 线上模式未配置 qwen-api-key");
                return null;
            }
            ok = runTtsOnline(text, apiKey, ttsPath);
        }
        if (ok) {
            log.info("[TTS] 合成成功 filename={}", filename);
            return filename;
        }
        return null;
    }

    private boolean runTtsLocal(Path textInputPath, Path outWavPath) {
        try {
            String text = Files.readString(textInputPath, StandardCharsets.UTF_8);
            if (text.length() > TTS_MAX_INPUT_LENGTH) {
                text = text.substring(0, TTS_MAX_INPUT_LENGTH);
            }
            text = text.replace("\"", "'").replace("\r", " ").replace("\n", " ");
            ProcessBuilder pb = new ProcessBuilder(
                    props.getPaddlespeechCmd(),
                    "tts",
                    "--input", text,
                    "--output", outWavPath.toAbsolutePath().toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String err = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean ok = p.waitFor(120, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                log.warn("TTS 超时: {}", err);
                return false;
            }
            if (p.exitValue() != 0) {
                log.warn("TTS 非零退出: {}, 输出: {}", p.exitValue(), err);
                return false;
            }
            return Files.exists(outWavPath);
        } catch (Exception e) {
            log.warn("TTS 执行异常", e);
            return false;
        }
    }

    /**
     * 多段在线 TTS：将长文本按 TTS_MAX_INPUT_LENGTH 分段，多次调用千问 TTS，并将多个 WAV 片段合并为一个完整 WAV。
     */
    private byte[] synthesizeOnlineToBytes(String text, String apiKey) {
        if (text == null || text.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        int segmentLen = TTS_MAX_INPUT_LENGTH;
        java.util.List<byte[]> segments = new java.util.ArrayList<>();
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + segmentLen, text.length());
            String segText = text.substring(offset, end);
            byte[] wav = callQwenTtsOnce(segText, apiKey);
            if (wav != null && wav.length > 0) {
                segments.add(wav);
            }
            offset = end;
        }
        if (segments.isEmpty()) {
            return null;
        }
        return mergeWavSegments(segments);
    }

    /**
     * 调用千问 TTS 生成单段 WAV（完整文件，包含 RIFF 头）。
     */
    private byte[] callQwenTtsOnce(String text, String apiKey) {
        if (text == null || text.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String ttsUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
        Map<String, Object> input = Map.of(
                "text", text,
                "voice", "Cherry",
                "language_type", "Chinese"
        );
        Map<String, Object> body = Map.of("model", "qwen3-tts-flash", "input", input);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<QwenTtsResponse> res = restTemplate.postForEntity(ttsUrl, entity, QwenTtsResponse.class);
            if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                return null;
            }
            QwenTtsResponse bodyObj = res.getBody();
            String data = bodyObj.getAudioData();
            String url = bodyObj.getAudioUrl();
            byte[] audioBytes = null;
            if (data != null && !data.isBlank()) {
                audioBytes = Base64.getDecoder().decode(data);
            } else if (url != null && !url.isBlank()) {
                ResponseEntity<byte[]> fileRes = restTemplate.getForEntity(URI.create(url), byte[].class);
                if (fileRes.getStatusCode().is2xxSuccessful() && fileRes.getBody() != null) {
                    audioBytes = fileRes.getBody();
                }
            }
            return (audioBytes != null && audioBytes.length > 0) ? audioBytes : null;
        } catch (Exception e) {
            log.warn("线上 TTS 单段调用异常", e);
            return null;
        }
    }

    /**
     * 合并多个 WAV 片段：保留第一段头部，后续段去掉 44 字节头部，仅拼接数据，并重写 RIFF 头中的长度字段。
     */
    private byte[] mergeWavSegments(java.util.List<byte[]> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        byte[] first = segments.get(0);
        if (first.length <= 44) {
            return first;
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 写入第一段头部 + 数据
            out.write(first, 0, 44);
            out.write(first, 44, first.length - 44);
            // 追加后续段的数据（跳过各自的头部）
            for (int i = 1; i < segments.size(); i++) {
                byte[] seg = segments.get(i);
                if (seg == null || seg.length <= 44) continue;
                out.write(seg, 44, seg.length - 44);
            }
            byte[] merged = out.toByteArray();
            int fileSizeMinus8 = merged.length - 8;
            int dataSize = merged.length - 44;
            writeLittleEndianInt(merged, 4, fileSizeMinus8);
            writeLittleEndianInt(merged, 40, dataSize);
            return merged;
        } catch (IOException e) {
            log.warn("合并 TTS WAV 片段失败", e);
            return first;
        }
    }

    private static void writeLittleEndianInt(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void safeDelete(Path path) {
        try {
            if (path != null && Files.exists(path)) {
                Files.delete(path);
            }
        } catch (IOException ignored) {
        }
    }
}
