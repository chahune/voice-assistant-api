package com.wshg.voice.service;

import com.wshg.voice.config.VoiceProperties;
import com.wshg.voice.entity.SmartHomeDevice;
import com.wshg.voice.repository.SmartHomeDeviceRepository;
import com.wshg.voice.store.SearchResult;
import com.wshg.voice.store.VectorDocument;
import com.wshg.voice.store.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 向量库服务：文档入库、语义检索。封装 Embedding + VectorStore。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final SmartHomeDeviceRepository deviceRepository;
    private final VoiceProperties voiceProperties;

    /**
     * 添加单条文档（自动生成 embedding 并入库）。
     */
    public String addDocument(String text) {
        return addDocument(text, null);
    }

    /**
     * 添加单条文档，可带元数据。
     */
    public String addDocument(String text, Map<String, Object> metadata) {
        if (text == null || text.isBlank()) return null;
        float[] emb = embeddingService.embed(text);
        if (emb == null) {
            log.warn("[向量库] 文档 embedding 失败: {}", text.substring(0, Math.min(50, text.length())));
            return null;
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        VectorDocument doc = VectorDocument.of(id, text, emb, metadata);
        vectorStore.add(doc);
        log.debug("[向量库] 添加文档 id={}, textLen={}", id, text.length());
        return id;
    }

    /**
     * 批量添加文档。
     */
    public List<String> addDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        List<float[]> embeddings = embeddingService.embedBatch(texts);
        if (embeddings.size() != texts.size()) {
            log.warn("批量 embedding 数量不匹配: texts={}, embeddings={}", texts.size(), embeddings.size());
        }
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < texts.size() && i < embeddings.size(); i++) {
            if (embeddings.get(i) == null) continue;
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            vectorStore.add(VectorDocument.of(id, texts.get(i), embeddings.get(i)));
            ids.add(id);
        }
        return ids;
    }

    /**
     * 语义检索：根据查询文本，返回最相似的 TopK 文档。
     */
    public List<SearchResult> search(String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        float[] queryEmb = embeddingService.embed(query);
        if (queryEmb == null) return List.of();
        return vectorStore.search(queryEmb, topK);
    }

    /**
     * 构建 RAG 上下文：根据查询检索相关文档，格式化为 prompt 片段。
     * 若向量库为空或检索无结果，返回空字符串。
     */
    public String buildRagContext(String query, int topK) {
        if (query == null || query.isBlank() || vectorStore.size() == 0) {
            log.debug("[向量库] RAG 上下文: 库空或查询空, size={}", vectorStore.size());
            return "";
        }
        List<SearchResult> results = search(query, topK);
        if (results.isEmpty()) {
            log.debug("[向量库] RAG 检索无命中 query={}", query.length() > 30 ? query.substring(0, 30) + "..." : query);
            return "";
        }
        double minScore = voiceProperties.getRagMinScore();
        List<SearchResult> filtered = results.stream()
                .filter(r -> r.getScore() >= minScore)
                .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            log.info("[向量库] RAG 检索结果相似度均低于阈值 minScore={}, 原始命中数={}, 最高分={}", minScore, results.size(), results.get(0).getScore());
            return "";
        }
        String context = filtered.stream()
                .map(r -> r.getDocument().getText())
                .collect(Collectors.joining("\n\n"));
        log.info("[向量库] RAG 命中数={}, contextLen={} (过滤后, minScore={})", filtered.size(), context.length(), minScore);
        return context;
    }

    /**
     * 清空向量库。
     */
    public void clear() {
        vectorStore.clear();
    }

    /**
     * 获取当前文档数量。
     */
    public int count() {
        return vectorStore.size();
    }

    /**
     * 按来源删除文档（如 source=device 时删除所有从设备表同步的文档）。
     */
    public void removeBySource(String source) {
        vectorStore.removeBySource(source);
    }

    /** 设备控制输出格式说明，写入向量库后可在用户问开灯/关灯时被检索到，替代原系统提示中的固定段落 */
    private static final String DEVICE_CTL_RULE_TEXT =
            "设备控制输出格式：当用户要求开灯、关灯、打开或关闭某房间灯光时，先正常回复一句话（如「好的，已打开客厅灯」），然后在回复的最后一行的下一行单独输出一行：[DEVICE_CTL] room=房间名 action=on 或 action=off。房间名从用户话中识别（如客厅、卧室），未指定房间则写 room=all。例如：[DEVICE_CTL] room=客厅 action=on。开灯、关灯、打开灯光、关闭灯光、打开客厅灯、关卧室灯 等说法都会触发此格式。";

    /**
     * 将设备表（smart_home_device）同步到知识库：先删 source=device 与 device_rule，再为每个已启用设备生成一条文档，并写入一条「设备控制输出格式」说明供 RAG 命中。
     * 设备增删改后可调用此方法，或由接口在变更后自动调用。
     * @return 本次写入知识库的文档数量（含设备文档 + 1 条格式说明）
     */
    @Transactional
    public int syncFromDevices() {
        log.info("[向量库] 开始设备表同步, 先删除 source=device 与 device_rule");
        vectorStore.removeBySource("device");
        vectorStore.removeBySource("device_rule");
        List<SmartHomeDevice> devices = deviceRepository.findAll();
        int added = 0;
        for (SmartHomeDevice d : devices) {
            if (!Boolean.TRUE.equals(d.getEnabled())) continue;
            String text = DeviceToVectorHelper.buildText(d);
            Map<String, Object> metadata = DeviceToVectorHelper.buildMetadata(d);
            if (addDocument(text, metadata) != null) added++;
        }
        if (addDocument(DEVICE_CTL_RULE_TEXT, Map.of("source", "device_rule")) != null) added++;
        log.info("[向量库] 设备表同步完成: 设备总数={}, 写入文档数={}, 当前库总量={}", devices.size(), added, vectorStore.size());
        return added;
    }
}
