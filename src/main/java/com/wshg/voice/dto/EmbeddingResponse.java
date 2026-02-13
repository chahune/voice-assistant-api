package com.wshg.voice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * DashScope / OpenAI 兼容 Embedding 响应体。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddingResponse {

    private List<EmbeddingData> data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbeddingData {
        private int index;
        private List<Float> embedding;
        @JsonProperty("object")
        private String objectType;
    }

    public float[] getFirstEmbedding() {
        if (data == null || data.isEmpty()) return null;
        List<Float> list = data.get(0).getEmbedding();
        if (list == null) return null;
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    public List<float[]> getAllEmbeddings() {
        if (data == null || data.isEmpty()) return List.of();
        return data.stream()
                .map(d -> {
                    List<Float> list = d.getEmbedding();
                    if (list == null) return new float[0];
                    float[] arr = new float[list.size()];
                    for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
                    return arr;
                })
                .toList();
    }
}
