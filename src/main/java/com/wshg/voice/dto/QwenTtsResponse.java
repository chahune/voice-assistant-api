package com.wshg.voice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 千问 TTS HTTP 接口响应体，对应文档「Qwen-TTS API」：
 * output.audio.data 为 Base64 流式片段，output.audio.url 为完整音频地址。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QwenTtsResponse {

    private Output output;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Output {
        private Audio audio;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Audio {
        /** Base64 编码音频数据（非流式场景通常为空） */
        private String data;
        /** 完整音频文件的 URL（有效期 24 小时） */
        private String url;
    }

    public String getAudioData() {
        return output == null || output.getAudio() == null ? null : output.getAudio().getData();
    }

    public String getAudioUrl() {
        return output == null || output.getAudio() == null ? null : output.getAudio().getUrl();
    }
}

