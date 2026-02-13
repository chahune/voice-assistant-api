package com.wshg.voice.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Ollama /api/embed 请求体。
 * 文档：https://docs.ollama.com/capabilities/embeddings
 */
@Data
@Builder
public class OllamaEmbedRequest {
    private String model;
    private Object input;  // String 或 List<String>

    public static OllamaEmbedRequest single(String model, String text) {
        return OllamaEmbedRequest.builder().model(model).input(text).build();
    }

    public static OllamaEmbedRequest batch(String model, List<String> texts) {
        return OllamaEmbedRequest.builder().model(model).input(texts).build();
    }
}
