# Ollama Embedding 本地部署指南

> 本地运行 Embedding 模型，用于向量库 / RAG，无需调用云端 API

---

## 一、安装 Ollama

### Windows

1. 打开 [https://ollama.com/download](https://ollama.com/download)
2. 下载 Windows 安装包并安装
3. 安装后 Ollama 会作为后台服务运行，默认地址 `http://localhost:11434`

### macOS / Linux

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

---

## 二、拉取 Embedding 模型

推荐模型：

| 模型 | 大小 | 维度 | 说明 |
|------|------|------|------|
| **nomic-embed-text** | ~274MB | 768 | 默认推荐，效果好、体积小 |
| **qwen3-embedding** | ~400MB | 1024 | 千问系列，中文表现好 |
| **embeddinggemma** | ~1GB | 1024 | Google 出品 |
| **all-minilm** | ~23MB | 384 | 极小，适合资源有限 |

```bash
# 拉取模型（任选其一）
ollama pull nomic-embed-text
# 或
ollama pull qwen3-embedding
# 或
ollama pull embeddinggemma
```

---

## 三、配置 voice-assistant-api

在 `application.yml` 中设置：

```yaml
voice:
  # 使用本地 Ollama 而非 DashScope
  embedding-provider: ollama
  ollama-base-url: http://localhost:11434
  ollama-embedding-model: nomic-embed-text
```

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| embedding-provider | `ollama` 或 `dashscope` | dashscope |
| ollama-base-url | Ollama 服务地址 | http://localhost:11434 |
| ollama-embedding-model | 模型名称 | nomic-embed-text |

---

## 四、验证

```bash
# 1. 确认 Ollama 服务
curl http://localhost:11434/api/tags

# 2. 测试 Embedding
curl -X POST http://localhost:11434/api/embed \
  -H "Content-Type: application/json" \
  -d '{"model": "nomic-embed-text", "input": "你好"}'

# 3. 重启 voice-assistant-api，调用向量库接口
curl -X POST http://localhost:8080/api/vector/documents \
  -H "Content-Type: application/json" \
  -d '{"text": "打开客厅灯"}'
```

---

## 五、切换回 DashScope

如需改用阿里云 Embedding：

```yaml
voice:
  embedding-provider: dashscope
  embedding-model: text-embedding-v3
  embedding-dimensions: 1024
  qwen-api-key: Bearer sk-xxx  # 必填
```

**注意**：切换 provider 后，向量库中的向量维度可能变化（如 nomic=768、DashScope=1024），建议清空向量库后重新入库：

```bash
curl -X POST http://localhost:8080/api/vector/clear
```
