package com.wshg.voice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Ollama /api/embed 响应体。
 * 返回格式：{ "embeddings": [[0.1, -0.2, ...], [...]] }
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaEmbedResponse {
    private List<List<Double>> embeddings;

    public float[] getFirstEmbedding() {
        if (embeddings == null || embeddings.isEmpty()) return null;
        List<Double> list = embeddings.get(0);
        if (list == null) return null;
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i).floatValue();
        }
        return arr;
    }

    public List<float[]> getAllEmbeddings() {
        if (embeddings == null || embeddings.isEmpty()) return List.of();
        return embeddings.stream()
                .map(list -> {
                    if (list == null) return new float[0];
                    float[] arr = new float[list.size()];
                    for (int i = 0; i < list.size(); i++) arr[i] = list.get(i).floatValue();
                    return arr;
                })
                .collect(Collectors.toList());
    }
}
