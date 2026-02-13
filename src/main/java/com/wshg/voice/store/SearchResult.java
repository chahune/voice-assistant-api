package com.wshg.voice.store;

import lombok.Builder;
import lombok.Data;

/**
 * 向量检索结果。
 */
@Data
@Builder
public class SearchResult {

    private VectorDocument document;
    private double score;  // 相似度得分，余弦相似度 [-1, 1]
}
