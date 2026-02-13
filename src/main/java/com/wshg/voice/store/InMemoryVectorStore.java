package com.wshg.voice.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wshg.voice.config.VoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存向量库，支持余弦相似度检索。
 * 当 voice.vector-store-type=file 且配置 vector-store-path 时，启动时从文件加载、变更时持久化到文件。
 */
@Slf4j
@RequiredArgsConstructor
public class InMemoryVectorStore implements VectorStore {

    private final VoiceProperties voiceProperties;
    private final ObjectMapper objectMapper;

    private final Map<String, VectorDocument> store = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadFromFile() {
        String pathStr = voiceProperties.getVectorStorePath();
        if (pathStr == null || pathStr.isBlank()) return;
        Path path = Path.of(pathStr).toAbsolutePath();
        if (!Files.exists(path)) return;
        try {
            String json = Files.readString(path);
            List<VectorDocument> list = objectMapper.readValue(json, new TypeReference<>() {});
            if (list != null) {
                for (VectorDocument d : list) {
                    if (d != null && d.getId() != null) store.put(d.getId(), d);
                }
                log.info("向量库已从文件加载: {} 条, 路径: {}", store.size(), path);
            }
        } catch (Exception e) {
            log.warn("向量库加载失败: {}", path, e);
        }
    }

    private void saveToFile() {
        String pathStr = voiceProperties.getVectorStorePath();
        if (pathStr == null || pathStr.isBlank()) return;
        Path path = Path.of(pathStr).toAbsolutePath();
        try {
            Files.createDirectories(path.getParent());
            List<VectorDocument> list = new ArrayList<>(store.values());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
            Files.writeString(path, json);
        } catch (IOException e) {
            log.warn("向量库持久化失败: {}", path, e);
        }
    }

    @Override
    public void add(VectorDocument doc) {
        if (doc == null || doc.getId() == null) return;
        store.put(doc.getId(), doc);
        saveToFile();
    }

    @Override
    public void addAll(List<VectorDocument> docs) {
        if (docs == null) return;
        for (VectorDocument d : docs) {
            if (d != null && d.getId() != null) store.put(d.getId(), d);
        }
        saveToFile();
    }

    @Override
    public void remove(String id) {
        store.remove(id);
        saveToFile();
    }

    /**
     * 清空所有文档。
     */
    @Override
    public void clear() {
        int n = store.size();
        store.clear();
        if (n > 0) log.info("[向量库-文件] clear 已清空, 原文档数={}", n);
        saveToFile();
    }

    /**
     * 按 metadata.source 删除，用于同步设备前清理旧文档。
     */
    @Override
    public void removeBySource(String source) {
        if (source == null) return;
        int before = store.size();
        store.entrySet().removeIf(e -> {
            Map<String, Object> m = e.getValue().getMetadata();
            if (m == null) return false;
            Object v = m.get("source");
            return source.equals(v);
        });
        int removed = before - store.size();
        if (removed > 0) log.info("[向量库-文件] removeBySource source={}, 删除数={}", source, removed);
        saveToFile();
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        if (queryEmbedding == null || store.isEmpty()) return List.of();
        List<SearchResult> results = new ArrayList<>();
        for (VectorDocument doc : store.values()) {
            if (doc.getEmbedding() == null || doc.getEmbedding().length != queryEmbedding.length) continue;
            double score = cosineSimilarity(queryEmbedding, doc.getEmbedding());
            results.add(SearchResult.builder()
                    .document(doc)
                    .score(score)
                    .build());
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
