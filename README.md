# 语音对话端到端后端 API

实现《语音对话端到端实现计划》中的 Spring Boot 接口：接收 ESP32 上传的 WAV，经 PaddleSpeech ASR → DeepSeek-R1（vLLM）→ PaddleSpeech TTS，返回识别文字、回复文字与 TTS 音频 URL，供 ESP32 下载播放。

## 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/voice/upload` | POST | 上传 WAV（multipart 字段 `file`），返回 `text`、`reply`、`audioUrl` 或 `error` |
| `/tts/{filename}` | GET | 根据 `audioUrl` 下载 TTS 音频（WAV） |

## 运行前准备

1. **JDK 17+**，Maven 3.6+
2. **PaddleSpeech**：已安装并加入 PATH，支持 `paddlespeech asr`、`paddlespeech tts`（见项目内 `doc/PaddleSpeech-本地部署.md`）
3. **vLLM + DeepSeek-R1**：已启动，提供 `http://<host>:8000/v1/chat/completions`（见 `doc/vllm-deepseek-r1-windows.md`）

## 无环境时快速联调（Mock）

在 `application.yml` 中设置：

```yaml
voice:
  mock: true
```

此时不调用 ASR/LLM/TTS，接口直接返回示例 `text`、`reply` 和 `audioUrl`。若访问 `/tts/mock_reply.wav` 需自行在 `tts-output/` 下放置同名文件，或仅测试上传与 JSON 响应。

## 构建与运行

```bash
cd voice-assistant-api
mvn -q clean package -DskipTests
java -jar target/voice-assistant-api-1.0.0.jar
```

或使用 IDE 运行 `VoiceAssistantApplication`。

默认端口 **8080**。上传测试：

```bash
curl -X POST -F "file=@你的音频.wav" http://localhost:8080/api/voice/upload
```

响应示例：

```json
{
  "text": "今天天气怎么样",
  "reply": "今天天气不错，适合出门。",
  "audioUrl": "http://localhost:8080/tts/voice_1234567890_abc12345.wav"
}
```

使用返回的 `audioUrl` 在浏览器或 ESP32 中 GET 即可下载播放。

## 配置说明

在 `application.yml` 的 `voice` 下可修改：

- `paddlespeech-cmd`：PaddleSpeech 命令（如 `python -m paddlespeech` 或 conda 环境下的全路径）
- `vllm-base-url`：vLLM 服务地址
- `vllm-model`：模型名，需与 vLLM 加载的模型一致
- `temp-dir` / `tts-dir`：临时目录与 TTS 输出目录（相对或绝对路径）
- `mock`：是否启用 Mock 响应

## 文档

- 端到端计划：`doc/语音对话端到端实现计划.md`
- PaddleSpeech 部署：`doc/PaddleSpeech-本地部署.md`
- vLLM DeepSeek-R1：`doc/vllm-deepseek-r1-windows.md`
