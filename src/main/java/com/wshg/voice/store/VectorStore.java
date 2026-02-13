package com.wshg.voice.store;

import java.util.List;

/**
 * 向量库抽象：支持内存+文件或 MySQL 持久化。
 */
public interface VectorStore {

    void add(VectorDocument doc);

    void addAll(List<VectorDocument> docs);

    void remove(String id);

    /** 按 metadata.source 删除，用于同步前清理 */
    void removeBySource(String source);

    void clear();

    int size();

    List<SearchResult> search(float[] queryEmbedding, int topK);
}
