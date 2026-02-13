package com.wshg.voice.store;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 向量库中的文档条目。
 */
@Data
@Builder
public class VectorDocument {

    private String id;
    private String text;
    private float[] embedding;
    private Map<String, Object> metadata;

    public static VectorDocument of(String id, String text, float[] embedding) {
        return VectorDocument.builder()
                .id(id)
                .text(text)
                .embedding(embedding)
                .build();
    }

    public static VectorDocument of(String id, String text, float[] embedding, Map<String, Object> metadata) {
        return VectorDocument.builder()
                .id(id)
                .text(text)
                .embedding(embedding)
                .metadata(metadata)
                .build();
    }
}
