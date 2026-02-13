package com.wshg.voice.controller;

import com.wshg.voice.config.VoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

/**
 * 提供 TTS 生成音频的下载，供 ESP32 根据 audioUrl 拉取播放。
 * GET /tts/{filename} 返回音频文件流。
 */
@Slf4j
@RestController
@RequestMapping("/tts")
@RequiredArgsConstructor
public class TtsResourceController {

    private final VoiceProperties voiceProperties;

    @GetMapping("/{filename:.+}")
    public ResponseEntity<Resource> getTtsFile(@PathVariable String filename) {
        Path dir = voiceProperties.getTtsDirPath();
        Path file = dir.resolve(filename).normalize();
        if (!file.startsWith(dir) || !file.toFile().exists()) {
            log.warn("[API] GET /tts/{} 文件不存在或越界", filename);
            return ResponseEntity.notFound().build();
        }
        log.debug("[API] GET /tts/{} 返回音频", filename);
        Resource resource = new PathResource(file);
        String contentType = filename.toLowerCase().endsWith(".mp3")
                ? "audio/mpeg"
                : "audio/wav";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);
    }
}
