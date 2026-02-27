package com.wshg.voice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 聊天记录表：保存每次问题、回答以及回答来源信息。
 */
@Entity
@Table(name = "chat_history", indexes = {
        @Index(name = "idx_chat_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用户原始问题（语音识别后的文本或前端传入的文本）。
     */
    @Column(name = "question", columnDefinition = "TEXT", nullable = false)
    private String question;

    /**
     * 大模型最终回复（设备控制指令行已剥离后用于播报的文本）。
     */
    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;

    /**
     * 回答来源：如 RAG-知识库 / LLM。
     */
    @Column(name = "answer_source", length = 64)
    private String answerSource;

    /**
     * 调用模式：voice-local / voice-online / qwen-text / qwen-asr 等。
     */
    @Column(name = "mode", length = 64)
    private String mode;

    /**
     * 是否使用了 RAG（知识库检索）参与回答。
     */
    @Column(name = "rag_used")
    private Boolean ragUsed;

    /**
     * 参与回答的知识库上下文原文（可为空）。
     */
    @Column(name = "rag_context", columnDefinition = "TEXT")
    private String ragContext;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}

