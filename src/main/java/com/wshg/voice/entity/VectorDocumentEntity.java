package com.wshg.voice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 知识库向量文档表（MySQL），用于 vector-store-type=mysql 时持久化。
 */
@Entity
@Table(name = "vector_document", indexes = @Index(name = "idx_source", columnList = "source"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VectorDocumentEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String text;

    @Column(name = "embedding_json", columnDefinition = "TEXT", nullable = false)
    private String embeddingJson;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(length = 64)
    private String source;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
