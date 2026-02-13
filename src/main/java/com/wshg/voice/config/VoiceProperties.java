package com.wshg.voice.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

/**
 * 语音管道配置：仅支持两种模式。
 * - local：vLLM(DeepSeek-R1) + Ollama(qwen3-embedding) + PaddleSpeech(语音转文本/文本转语音，百度开源)
 * - online：阿里云 语音转文本、文本转语音、向量模型、千问大模型
 */
@ConfigurationProperties(prefix = "voice")
public class VoiceProperties {

    private Environment environment;

    @Autowired
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /** 是否本地模式（否则为线上阿里云） */
    public boolean isLocal() {
        if (environment == null) return false;
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            String def = environment.getProperty("spring.profiles.active", "local");
            return "local".equalsIgnoreCase(def);
        }
        return "local".equalsIgnoreCase(active[0]);
    }

    // ---------- 公共 ----------
    private String tempDir = "temp";
    private String ttsDir = "tts-output";
    private long maxUploadSize = 5 * 1024 * 1024;
    private boolean mock = false;
    private boolean ragEnabled = true;
    private int ragTopK = 5;
    /** RAG 检索最低相似度（余弦），低于此值的文档不进入上下文，避免无关命中。建议 0.45~0.6 */
    private double ragMinScore = 0.5;
    private String vectorStoreType = "mysql";
    /** 向量库文件路径（仅 vector-store-type=file 时生效） */
    private String vectorStorePath = "data/vector-store.json";

    // ---------- 本地（local）：ASR + TTS 均用 PaddleSpeech ----------
    /** PaddleSpeech 命令行（pip/conda 安装后为 paddlespeech） */
    private String paddlespeechCmd = "paddlespeech";
    private String vllmBaseUrl = "http://localhost:8000";
    private String vllmModel = "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B";
    private String ollamaBaseUrl = "http://localhost:11434";
    private String ollamaEmbeddingModel = "qwen3-embedding";

    // ---------- 线上（online）----------
    private String qwenBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
    private String qwenModel = "qwen-plus";
    private String qwenApiKey;
    private String embeddingModel = "text-embedding-v3";
    private int embeddingDimensions = 1024;

    public String getTempDir() { return tempDir; }
    public void setTempDir(String tempDir) { this.tempDir = tempDir; }
    public String getTtsDir() { return ttsDir; }
    public void setTtsDir(String ttsDir) { this.ttsDir = ttsDir; }
    public long getMaxUploadSize() { return maxUploadSize; }
    public void setMaxUploadSize(long maxUploadSize) { this.maxUploadSize = maxUploadSize; }
    public boolean isMock() { return mock; }
    public void setMock(boolean mock) { this.mock = mock; }
    public boolean isRagEnabled() { return ragEnabled; }
    public void setRagEnabled(boolean ragEnabled) { this.ragEnabled = ragEnabled; }
    public int getRagTopK() { return ragTopK; }
    public void setRagTopK(int ragTopK) { this.ragTopK = ragTopK; }
    public double getRagMinScore() { return ragMinScore; }
    public void setRagMinScore(double ragMinScore) { this.ragMinScore = ragMinScore; }
    public String getVectorStoreType() { return vectorStoreType; }
    public void setVectorStoreType(String vectorStoreType) { this.vectorStoreType = vectorStoreType; }
    public String getVectorStorePath() { return vectorStorePath; }
    public void setVectorStorePath(String vectorStorePath) { this.vectorStorePath = vectorStorePath; }

    public String getPaddlespeechCmd() { return paddlespeechCmd; }
    public void setPaddlespeechCmd(String paddlespeechCmd) { this.paddlespeechCmd = paddlespeechCmd; }
    public String getVllmBaseUrl() { return vllmBaseUrl; }
    public void setVllmBaseUrl(String vllmBaseUrl) { this.vllmBaseUrl = vllmBaseUrl; }
    public String getVllmModel() { return vllmModel; }
    public void setVllmModel(String vllmModel) { this.vllmModel = vllmModel; }
    public String getOllamaBaseUrl() { return ollamaBaseUrl; }
    public void setOllamaBaseUrl(String ollamaBaseUrl) { this.ollamaBaseUrl = ollamaBaseUrl; }
    public String getOllamaEmbeddingModel() { return ollamaEmbeddingModel; }
    public void setOllamaEmbeddingModel(String ollamaEmbeddingModel) { this.ollamaEmbeddingModel = ollamaEmbeddingModel; }

    public String getQwenBaseUrl() { return qwenBaseUrl; }
    public void setQwenBaseUrl(String qwenBaseUrl) { this.qwenBaseUrl = qwenBaseUrl; }
    public String getQwenModel() { return qwenModel; }
    public void setQwenModel(String qwenModel) { this.qwenModel = qwenModel; }
    public String getQwenApiKey() { return qwenApiKey; }
    public void setQwenApiKey(String qwenApiKey) { this.qwenApiKey = qwenApiKey; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getEmbeddingDimensions() { return embeddingDimensions; }
    public void setEmbeddingDimensions(int embeddingDimensions) { this.embeddingDimensions = embeddingDimensions; }

    public Path getTempDirPath() { return Path.of(tempDir).toAbsolutePath(); }
    public Path getTtsDirPath() { return Path.of(ttsDir).toAbsolutePath(); }
}
