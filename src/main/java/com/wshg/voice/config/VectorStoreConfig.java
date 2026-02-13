package com.wshg.voice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wshg.voice.repository.VectorDocumentRepository;
import com.wshg.voice.store.InMemoryVectorStore;
import com.wshg.voice.store.MysqlVectorStore;
import com.wshg.voice.store.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "voice.vector-store-type", havingValue = "file")
    public VectorStore inMemoryVectorStore(VoiceProperties voiceProperties, ObjectMapper objectMapper) {
        return new InMemoryVectorStore(voiceProperties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "voice.vector-store-type", havingValue = "mysql", matchIfMissing = true)
    public VectorStore mysqlVectorStore(VectorDocumentRepository vectorDocumentRepository, ObjectMapper objectMapper) {
        return new MysqlVectorStore(vectorDocumentRepository, objectMapper);
    }
}
