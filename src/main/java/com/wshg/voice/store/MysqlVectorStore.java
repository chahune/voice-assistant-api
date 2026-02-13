package com.wshg.voice.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wshg.voice.entity.VectorDocumentEntity;
import com.wshg.voice.repository.VectorDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 向量库 MySQL 实现：启动时从表加载，增删改同步到 MySQL。
 */
@Slf4j
public class MysqlVectorStore implements VectorStore {

    private final VectorDocumentRepository repository;
    private final ObjectMapper objectMapper;

    private final Map<String, VectorDocument> cache = new ConcurrentHashMap<>();

    public MysqlVectorStore(VectorDocumentRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadFromDb() {
        repository.findAll().forEach(e -> {
            VectorDocument doc = toDocument(e);
            if (doc != null) cache.put(doc.getId(), doc);
        });
        log.info("向量库已从 MySQL 加载: {} 条", cache.size());
    }

    private VectorDocument toDocument(VectorDocumentEntity e) {
        if (e == null || e.getId() == null) return null;
        float[] emb = parseEmbedding(e.getEmbeddingJson());
        if (emb == null) return null;
        Map<String, Object> meta = parseMetadata(e.getMetadataJson());
        return VectorDocument.of(e.getId(), e.getText(), emb, meta);
    }

    private float[] parseEmbedding(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            List<Number> list = objectMapper.readValue(json, new TypeReference<>() {});
            if (list == null || list.isEmpty()) return null;
            float[] a = new float[list.size()];
            for (int i = 0; i < list.size(); i++) a[i] = list.get(i).floatValue();
            return a;
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return null;
        }
    }

    private VectorDocumentEntity toEntity(VectorDocument doc) {
        if (doc == null || doc.getId() == null) return null;
        String embJson;
        try {
            embJson = objectMapper.writeValueAsString(doc.getEmbedding() != null ? doc.getEmbedding() : new float[0]);
        } catch (Exception e) {
            return null;
        }
        String metaJson = null;
        if (doc.getMetadata() != null && !doc.getMetadata().isEmpty()) {
            try {
                metaJson = objectMapper.writeValueAsString(doc.getMetadata());
            } catch (Exception ignored) {}
        }
        String source = doc.getMetadata() != null && doc.getMetadata().get("source") != null
                ? String.valueOf(doc.getMetadata().get("source")) : null;
        return VectorDocumentEntity.builder()
                .id(doc.getId())
                .text(doc.getText())
                .embeddingJson(embJson)
                .metadataJson(metaJson)
                .source(source)
                .createdAt(java.time.Instant.now())
                .build();
    }

    @Override
    public void add(VectorDocument doc) {
        if (doc == null || doc.getId() == null) return;
        VectorDocumentEntity e = toEntity(doc);
        if (e == null) return;
        repository.save(e);
        cache.put(doc.getId(), doc);
        log.debug("[向量库-MySQL] 添加 id={}, source={}", doc.getId(), e.getSource());
    }

    @Override
    public void addAll(List<VectorDocument> docs) {
        if (docs == null) return;
        for (VectorDocument d : docs) {
            add(d);
        }
    }

    @Override
    public void remove(String id) {
        repository.deleteById(id);
        cache.remove(id);
    }

    @Override
    public void removeBySource(String source) {
        if (source == null) return;
        List<VectorDocumentEntity> list = repository.findBySource(source);
        int n = list.size();
        for (VectorDocumentEntity e : list) {
            cache.remove(e.getId());
        }
        repository.deleteBySource(source);
        log.info("[向量库-MySQL] removeBySource source={}, 删除数={}", source, n);
    }

    @Override
    public void clear() {
        long n = repository.count();
        repository.deleteAll();
        cache.clear();
        log.info("[向量库-MySQL] clear 已清空, 原文档数={}", n);
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        if (queryEmbedding == null || cache.isEmpty()) return List.of();
        List<SearchResult> results = new ArrayList<>();
        for (VectorDocument doc : cache.values()) {
            if (doc.getEmbedding() == null || doc.getEmbedding().length != queryEmbedding.length) continue;
            double score = cosineSimilarity(queryEmbedding, doc.getEmbedding());
            results.add(SearchResult.builder().document(doc).score(score).build());
        }
        return results.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .collect(Collectors.toList());
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }
}
