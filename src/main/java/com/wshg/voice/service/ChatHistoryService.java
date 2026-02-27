package com.wshg.voice.service;

import com.wshg.voice.entity.ChatHistory;
import com.wshg.voice.repository.ChatHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天记录服务：负责将问答保存到 MySQL，并按指定格式写入知识库向量文档表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatHistoryRepository chatHistoryRepository;
    private final VectorStoreService vectorStoreService;

    /**
     * 保存一次问答记录，并同步写入向量库（vector_document）。
     *
     * @param question      用户问题（必填）
     * @param answer        模型回答
     * @param mode          调用模式：voice-local / voice-online / qwen-text / qwen-asr 等
     * @param answerSource  回答来源描述：如 RAG-知识库 / LLM
     * @param ragContext    参与回答的知识库上下文原文，可为空
     */
    @Transactional
    public void logChat(String question,
                        String answer,
                        String mode,
                        String answerSource,
                        String ragContext) {
        if (question == null || question.isBlank()) {
            return;
        }

        boolean ragUsed = ragContext != null && !ragContext.isBlank();

        ChatHistory history = ChatHistory.builder()
                .question(question)
                .answer(answer)
                .mode(mode)
                .answerSource(answerSource)
                .ragUsed(ragUsed)
                .ragContext(ragContext)
                .build();
        ChatHistory saved = chatHistoryRepository.save(history);

        // 按“智能家居设备表同步到向量库”的思路，将聊天记录写入知识库向量文档表
        try {
            StringBuilder textBuilder = new StringBuilder();
            textBuilder.append("问：").append(question.trim());
            if (answer != null && !answer.isBlank()) {
                textBuilder.append("\n答：").append(answer.trim());
            }
            String docText = textBuilder.toString();

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "chat");
            metadata.put("chatId", saved.getId());
            metadata.put("mode", mode != null ? mode : "");
            metadata.put("answerSource", answerSource != null ? answerSource : "");
            metadata.put("category", "对话记录");

            if (ragUsed) {
                metadata.put("ragUsed", true);
            }

            vectorStoreService.addDocument(docText, metadata);
        } catch (Exception e) {
            // 不影响主流程：写向量库失败时仅记录日志
            log.warn("[聊天记录] 写入向量库失败", e);
        }
    }
}

