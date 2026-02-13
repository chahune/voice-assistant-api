package com.wshg.voice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * POST /api/voice/upload 成功响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VoiceUploadResponse {

    /** ASR 识别出的用户文字 */
    private String text;

    /** 大模型回复文字 */
    private String reply;

    /** TTS 音频下载地址，ESP32 用此 URL GET 下载播放 */
    private String audioUrl;

    /** 错误信息（仅当 error 存在时返回） */
    private String error;

    public static VoiceUploadResponse error(String message) {
        return VoiceUploadResponse.builder().error(message).build();
    }
}
