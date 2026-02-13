package com.wshg.voice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * DashScope / OpenAI 兼容 Embedding 请求体。
 */
@Data
@Builder
public class EmbeddingRequest {

    private String model;
    private Object input;  // String 或 List<String>
    private Integer dimensions;
    @JsonProperty("encoding_format")
    private String encodingFormat;

    public static EmbeddingRequest single(String model, String text, Integer dimensions) {
        return EmbeddingRequest.builder()
                .model(model)
                .input(text)
                .dimensions(dimensions != null ? dimensions : 1024)
                .encodingFormat("float")
                .build();
    }

    public static EmbeddingRequest batch(String model, List<String> texts, Integer dimensions) {
        return EmbeddingRequest.builder()
                .model(model)
                .input(texts)
                .dimensions(dimensions != null ? dimensions : 1024)
                .encodingFormat("float")
                .build();
    }
}
