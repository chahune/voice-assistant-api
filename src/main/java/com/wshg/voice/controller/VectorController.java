package com.wshg.voice.controller;

import com.wshg.voice.service.VectorStoreService;
import com.wshg.voice.store.SearchResult;
import com.wshg.voice.store.VectorDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 向量库 API：文档入库、语义检索。
 */
@Slf4j
@RestController
@RequestMapping("/api/vector")
@RequiredArgsConstructor
public class VectorController {

    private final VectorStoreService vectorStoreService;

    /**
     * 添加单条文档。
     * POST /api/vector/documents
     * Body: { "text": "文档内容", "metadata": { ... } }
     */
    @PostMapping("/documents")
    public ResponseEntity<Map<String, Object>> addDocument(@RequestBody Map<String, Object> body) {
        String text = (String) body.get("text");
        log.info("[API] POST /api/vector/documents textLength={}", text != null ? text.length() : 0);
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) body.get("metadata");
        String id = vectorStoreService.addDocument(text, metadata);
        if (id == null) {
            log.warn("[API] /vector/documents Embedding 失败");
            return ResponseEntity.status(502).body(Map.of("error",
                    "Embedding 调用失败。本地模式请确认 Ollama 已启动(ollama serve)；线上模式请配置 voice.qwen-api-key"));
        }
        log.info("[API] /vector/documents 成功 id={}", id);
        return ResponseEntity.ok(Map.of("id", id, "text", text));
    }

    /**
     * 批量添加文档。
     * POST /api/vector/documents/batch
     * Body: { "texts": ["文档1", "文档2", ...] }
     */
    @PostMapping("/documents/batch")
    public ResponseEntity<Map<String, Object>> addDocumentsBatch(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> texts = (List<String>) body.get("texts");
        log.info("[API] POST /api/vector/documents/batch count={}", texts != null ? texts.size() : 0);
        if (texts == null || texts.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "texts 不能为空"));
        }
        List<String> ids = vectorStoreService.addDocuments(texts);
        log.info("[API] /vector/documents/batch 成功 count={}", ids.size());
        return ResponseEntity.ok(Map.of("ids", ids, "count", ids.size()));
    }

    /**
     * 语义检索。
     * GET /api/vector/search?query=xxx&topK=5
     * 或 POST /api/vector/search Body: { "query": "xxx", "topK": 5 }
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchGet(
            @RequestParam("query") String query,
            @RequestParam(value = "topK", defaultValue = "5") int topK) {
        return doSearch(query, topK);
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> searchPost(@RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        Object topKObj = body.get("topK");
        int topK = topKObj instanceof Number ? ((Number) topKObj).intValue() : 5;
        return doSearch(query, topK);
    }

    private ResponseEntity<Map<String, Object>> doSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "query 不能为空"));
        }
        topK = Math.min(Math.max(topK, 1), 50);
        log.info("[API] /vector/search query={}, topK={}", query.length() > 30 ? query.substring(0, 30) + "..." : query, topK);
        List<SearchResult> results = vectorStoreService.search(query, topK);
        log.info("[API] /vector/search 命中数={}", results.size());
        List<Map<String, Object>> items = results.stream()
                .map(r -> {
                    VectorDocument doc = r.getDocument();
                    return Map.<String, Object>of(
                            "id", doc.getId(),
                            "text", doc.getText(),
                            "score", r.getScore(),
                            "metadata", doc.getMetadata() != null ? doc.getMetadata() : Map.of()
                    );
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of(
                "query", query,
                "results", items,
                "count", items.size()
        ));
    }

    /**
     * 获取向量库统计。
     * GET /api/vector/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of("count", vectorStoreService.count()));
    }

    /**
     * 清空向量库。
     * POST /api/vector/clear
     */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, String>> clear() {
        log.info("[API] POST /api/vector/clear 清空向量库");
        vectorStoreService.clear();
        return ResponseEntity.ok(Map.of("message", "向量库已清空"));
    }

    /**
     * 将智能家居设备表（smart_home_device）信息同步到知识库。
     * 先删除 source=device 的旧文档，再为每个已启用设备生成一条文档（含开/关灯指令、房间、操作步骤、调用方法）。
     * POST /api/vector/sync-from-devices
     */
    @PostMapping("/sync-from-devices")
    public ResponseEntity<Map<String, Object>> syncFromDevices() {
        log.info("[API] POST /api/vector/sync-from-devices 开始同步");
        int added = vectorStoreService.syncFromDevices();
        log.info("[API] /vector/sync-from-devices 完成 documentsAdded={}", added);
        return ResponseEntity.ok(Map.of(
                "message", "已从设备表同步到知识库",
                "documentsAdded", added
        ));
    }
}
