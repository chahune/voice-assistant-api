package com.wshg.voice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * vLLM OpenAI 兼容接口 /v1/chat/completions 响应体。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatResponse {

    private List<Choice> choices;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private Message message;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role;
        private String content;
    }

    public String getFirstContent() {
        if (choices == null || choices.isEmpty()) return null;
        Message msg = choices.get(0).getMessage();
        return msg == null ? null : msg.getContent();
    }
}
