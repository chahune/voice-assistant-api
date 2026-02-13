package com.wshg.voice.service;

import com.wshg.voice.config.VoiceProperties;
import com.wshg.voice.dto.EmbeddingRequest;
import com.wshg.voice.dto.EmbeddingResponse;
import com.wshg.voice.dto.OllamaEmbedRequest;
import com.wshg.voice.dto.OllamaEmbedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Embedding 服务：本地模式用 Ollama(qwen3-embedding)，线上模式用阿里云 DashScope。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final VoiceProperties props;
    private final RestTemplate restTemplate;

    private static final String EMBEDDINGS_PATH = "/v1/embeddings";
    private static final String OLLAMA_EMBED_PATH = "/api/embed";

    /**
     * 单条文本生成向量。
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;
        if (isOllama()) {
            return embedOllama(text);
        }
        return embedDashScope(text);
    }

    /**
     * 批量文本生成向量。
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (isOllama()) {
            return embedBatchOllama(texts);
        }
        return embedBatchDashScope(texts);
    }

    private boolean isOllama() {
        return props.isLocal();
    }

    private float[] embedDashScope(String text) {
        String apiKey = props.getQwenApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 qwen-api-key，无法调用 DashScope Embedding");
            return null;
        }
        String url = buildDashScopeUrl();
        EmbeddingRequest req = EmbeddingRequest.single(
                props.getEmbeddingModel(),
                text,
                props.getEmbeddingDimensions()
        );
        try {
            ResponseEntity<EmbeddingResponse> res = postDashScope(url, apiKey, req);
            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                float[] emb = res.getBody().getFirstEmbedding();
                log.debug("[Embedding] DashScope 单条成功 dim={}", emb != null ? emb.length : 0);
                return emb;
            }
        } catch (Exception e) {
            log.error("[Embedding] DashScope 调用失败", e);
        }
        return null;
    }

    private List<float[]> embedBatchDashScope(List<String> texts) {
        String apiKey = props.getQwenApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("未配置 qwen-api-key，无法调用 DashScope Embedding");
            return List.of();
        }
        String url = buildDashScopeUrl();
        EmbeddingRequest req = EmbeddingRequest.batch(
                props.getEmbeddingModel(),
                texts,
                props.getEmbeddingDimensions()
        );
        try {
            ResponseEntity<EmbeddingResponse> res = postDashScope(url, apiKey, req);
            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                return res.getBody().getAllEmbeddings();
            }
        } catch (Exception e) {
            log.error("DashScope Embedding 批量调用失败", e);
        }
        return List.of();
    }

    private float[] embedOllama(String text) {
        String url = buildOllamaUrl();
        OllamaEmbedRequest req = OllamaEmbedRequest.single(props.getOllamaEmbeddingModel(), text);
        try {
            ResponseEntity<OllamaEmbedResponse> res = restTemplate.postForEntity(
                    url, new HttpEntity<>(req, jsonHeaders()), OllamaEmbedResponse.class);
            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                float[] emb = res.getBody().getFirstEmbedding();
                log.debug("[Embedding] Ollama 单条成功 url={}, dim={}", url, emb != null ? emb.length : 0);
                return emb;
            }
        } catch (Exception e) {
            log.error("[Embedding] Ollama 调用失败 url={}", url, e);
        }
        return null;
    }

    private List<float[]> embedBatchOllama(List<String> texts) {
        String url = buildOllamaUrl();
        OllamaEmbedRequest req = OllamaEmbedRequest.batch(props.getOllamaEmbeddingModel(), texts);
        try {
            ResponseEntity<OllamaEmbedResponse> res = restTemplate.postForEntity(
                    url, new HttpEntity<>(req, jsonHeaders()), OllamaEmbedResponse.class);
            if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                return res.getBody().getAllEmbeddings();
            }
        } catch (Exception e) {
            log.error("Ollama Embedding 批量调用失败", e);
        }
        return List.of();
    }

    private String buildDashScopeUrl() {
        String base = props.getQwenBaseUrl();
        if (base == null || base.isBlank()) base = "https://dashscope.aliyuncs.com/compatible-mode";
        return base.replaceAll("/$", "") + EMBEDDINGS_PATH;
    }

    private String buildOllamaUrl() {
        String base = props.getOllamaBaseUrl();
        if (base == null || base.isBlank()) base = "http://localhost:11434";
        return base.replaceAll("/$", "") + OLLAMA_EMBED_PATH;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private ResponseEntity<EmbeddingResponse> postDashScope(String url, String apiKey, EmbeddingRequest req) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(apiKey);
        return restTemplate.postForEntity(url, new HttpEntity<>(req, headers), EmbeddingResponse.class);
    }
}
