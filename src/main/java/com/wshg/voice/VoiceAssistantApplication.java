package com.wshg.voice;

import com.wshg.voice.config.VoiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableConfigurationProperties(VoiceProperties.class)
@EnableAsync
public class VoiceAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoiceAssistantApplication.class, args);
    }
}
