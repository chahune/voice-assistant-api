## Windows 本地部署 DeepSeek‑R1 蒸馏模型（7B）+ qwen3-embedding 实战指南

本文介绍如何在 **Windows 10/11** 上，本地部署：

1. **DeepSeek‑R1 蒸馏模型（7B）**：通过 WSL2 + Ubuntu + vLLM，提供大模型推理
2. **qwen3-embedding**：通过 Ollama，提供向量 Embedding（用于 RAG / 向量库）
3. **PaddleSpeech**：语音转文本(ASR) + 文本转语音(TTS)，本地模式在 voice-assistant-api 中通过 `paddlespeech-cmd` 配置（见下方）

二者搭配使用，可实现完全本地的语音助手 / RAG 流水线。

#### paddlespeech-cmd 配置与使用

- **含义**：`paddlespeech-cmd: paddlespeech` 表示本地模式下，ASR 和 TTS 在系统里执行该命令。代码会执行：
  - 语音转文本：`paddlespeech asr --lang zh --input <wav 文件>`
  - 文本转语音：`paddlespeech tts --input "文本" --output <输出 wav>`
- **使用步骤**：
  1. 安装 PaddleSpeech（Python 3.8+）：`pip install paddlepaddle` → `pip install paddlespeech`（或 conda）
  2. 确认命令可用：终端执行 `paddlespeech asr --help` 能出帮助即可，配置保持 `paddlespeech-cmd: paddlespeech`
  3. 若命令不在 PATH：在 application.yml 的 local 段把 `paddlespeech-cmd` 改为可执行文件绝对路径，如 Windows `"C:/Python311/Scripts/paddlespeech"`，Linux `/usr/local/bin/paddlespeech`
  4. 保持 `spring.profiles.active: local`，启动 voice-assistant-api 后，通过 **POST /api/voice/upload** 上传音频，本地会自动用 PaddleSpeech 做 ASR 和 TTS。

---

### 一、两个模型安装步骤

#### 1.1 DeepSeek‑R1 蒸馏模型（7B）

**WSL2 + Ubuntu 下载安装**：以管理员身份打开 PowerShell，执行 `wsl --install -d Ubuntu-22.04`，按提示重启后完成 Ubuntu 初始化。

在 **WSL2 Ubuntu** 终端执行：

```bash
# 安装环境
sudo apt update && sudo apt install -y python3.10-venv git
python3 -m venv vllm-venv && source vllm-venv/bin/activate
pip install --upgrade pip && pip install "vllm[all]" huggingface_hub

# 启动服务（首次会下载模型，需较长时间）
python -m vllm.entrypoints.openai.api_server \
  --model deepseek-ai/DeepSeek-R1-Distill-Qwen-7B \
  --trust-remote-code \
  --host 0.0.0.0 \
  --port 8000
```

看到 `Uvicorn running on http://0.0.0.0:8000` 即表示大模型服务已就绪。

#### 1.2 qwen3-embedding

在 **Windows PowerShell** 执行：

```powershell
# 1. 安装 Ollama：打开 https://ollama.com/download 下载并安装

# 2. 拉取模型
ollama pull qwen3-embedding
```

Ollama 安装后作为后台服务运行，默认 `http://localhost:11434`。

---

### 二、使用示例

#### 2.1 大模型（DeepSeek-R1）

```powershell
$json = '{"model":"deepseek-ai/DeepSeek-R1-Distill-Qwen-7B","messages":[{"role":"user","content":"用中文简单自我介绍一下"}]}'
curl.exe http://localhost:8000/v1/chat/completions -H "Content-Type: application/json" -d $json
```

#### 2.2 向量模型（qwen3-embedding）

```powershell
curl.exe -X POST http://localhost:11434/api/embed -H "Content-Type: application/json" -d "{\"model\":\"qwen3-embedding\",\"input\":\"你好\"}"
```

#### 2.3 voice-assistant-api 集成（RAG）

配置 `application.yml`：

```yaml
voice:
  vllm-base-url: http://localhost:8000
  vllm-model: deepseek-ai/DeepSeek-R1-Distill-Qwen-7B
  embedding-provider: ollama
  ollama-base-url: http://localhost:11434
  ollama-embedding-model: qwen3-embedding
  rag-enabled: true
  rag-top-k: 5
```

入库与检索：

```powershell
# 添加知识文档
curl.exe -X POST http://localhost:8080/api/vector/documents -H "Content-Type: application/json" -d "{\"text\":\"打开客厅灯：说「开灯」\"}"

# 语义检索
curl.exe "http://localhost:8080/api/vector/search?query=怎么开灯&topK=3"

# 语音对话（上传 WAV）
curl.exe -X POST http://localhost:8080/api/voice/upload -F "file=@recording.wav"
```

---

### 三、前提条件

在开始之前，请确认：

- **操作系统**：Windows 10 / 11（推荐 11）
- **显卡**：NVIDIA GPU（建议显存 ≥ 16 GB）
- **驱动**：Windows 上已安装好 NVIDIA 显卡驱动
- **网络**：能够访问 Hugging Face 下载模型（或配置镜像）

---

### 四、在 Windows 开启 WSL2 并安装 Ubuntu（大模型所需）

1. 以 **管理员身份** 打开 PowerShell（开始菜单右键 → Windows PowerShell(管理员)）。

2. 执行以下命令安装 WSL 和 Ubuntu 22.04：

```powershell
wsl --install -d Ubuntu-22.04
```

3. 按提示 **重启电脑**。

4. 重启后会自动弹出一个 Ubuntu 窗口，按提示：

   - 设置一个 **用户名**（例如 `ubuntu`）
   - 设置一个 **密码**（后面 `sudo` 会用）

之后，Ubuntu 环境就准备好了。以后可以在开始菜单搜索“Ubuntu”打开。

#### 4.1 WSL2 下 GPU 可用性检查

WSL2 使用 **Windows 侧的 NVIDIA 驱动**，无需在 Ubuntu 内单独安装驱动。要求：

- **驱动版本**：建议 525+，最好是最新 Game Ready / Studio 驱动
- **安装来源**：<https://www.nvidia.com/Download/index.aspx>

在 **Ubuntu 终端** 中执行：

```bash
nvidia-smi
```

若能看到显卡型号、显存、驱动版本，说明 GPU 已透传给 WSL2。若提示 `command not found` 或报错，请先在 Windows 安装/更新 NVIDIA 驱动并重启。

---

### 五、在 Ubuntu 里安装 Python 环境和 vLLM（详细）

> 下面所有命令都在 **Ubuntu 终端** 内执行（不是在 Windows PowerShell）。

#### 5.1 更新系统 & 安装基础包

```bash
sudo apt update
sudo apt install -y python3.10-venv git
```

#### 5.2 创建并激活 Python 虚拟环境

```bash
python3 -m venv vllm-venv
source vllm-venv/bin/activate
```

终端前面如果多了一个 `(vllm-venv)` 前缀，说明虚拟环境已激活。

#### 5.3 安装 vLLM 和 Hugging Face 相关包

```bash
pip install --upgrade pip
pip install "vllm[all]" huggingface_hub
```

> 如果你有 Hugging Face 的访问 token（有些模型需要授权），可以先导出：
>
> ```bash
> export HF_TOKEN=你的HF_TOKEN
> ```

---

### 六、用 vLLM 跑 DeepSeek‑R1 蒸馏模型（详细）

这里以 **7B 蒸馏版** 为例（相对更容易在单卡上跑起来）。

在 Ubuntu 里，保持虚拟环境已激活 `(vllm-venv)`，执行：

```bash
python -m vllm.entrypoints.openai.api_server \
  --model deepseek-ai/DeepSeek-R1-Distill-Qwen-7B \
  --trust-remote-code \
  --host 0.0.0.0 \
  --port 8000
```

说明：

- `--model`：Hugging Face 上的模型名
- `--trust-remote-code`：DeepSeek 提供的模型代码需要启用此选项
- `--host 0.0.0.0`：对所有网卡开放访问（方便从 Windows 访问）
- `--port 8000`：监听端口为 8000（可根据需要调整）

**第一次运行**会从 Hugging Face 下载模型权重，速度取决于网络，时间可能较长。  

当你看到类似下面的日志：

```text
INFO:     Uvicorn running on http://0.0.0.0:8000 (Press CTRL+C to quit)
```

说明 **vLLM 服务已经成功跑起来了**。

> 注意：此终端需要保持打开，不能关闭。关闭 = 服务停止。

---

### 七、部署 qwen3-embedding（Ollama，详细）

qwen3-embedding 与 DeepSeek-R1-Distill-Qwen-7B 同属 Qwen 生态，中文表现好，适合作为 RAG 向量模型。

#### 7.1 安装 Ollama（Windows）

1. 打开 [https://ollama.com/download](https://ollama.com/download)
2. 下载 Windows 安装包并安装
3. 安装后 Ollama 作为后台服务运行，默认地址 `http://localhost:11434`

#### 7.2 拉取 qwen3-embedding

在 **Windows PowerShell 或 CMD** 中执行：

```powershell
ollama pull qwen3-embedding
```

首次会下载约 400MB 模型文件。

#### 7.3 验证 Embedding 服务

```powershell
curl.exe -X POST http://localhost:11434/api/embed -H "Content-Type: application/json" -d "{\"model\": \"qwen3-embedding\", \"input\": \"你好\"}"
```

返回 JSON 中包含 `embeddings` 数组（1024 维向量）即表示正常。

---

### 八、在 Windows 侧测试大模型接口

现在我们像调用 OpenAI 一样来调用这个本地服务。

1. 打开 **Windows PowerShell 或 CMD**（注意：这一步在 Windows 里，不是在 Ubuntu 里）。

2. 执行（PowerShell 写法，推荐）：

```powershell
$json = @'
{
  "model": "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B",
  "messages": [
    { "role": "user", "content": "用中文简单自我介绍一下" }
  ]
}
'@

curl.exe http://localhost:8000/v1/chat/completions `
  -H "Content-Type: application/json" `
  -d $json
```

> 说明：
> - 使用 `curl.exe`，避免 PowerShell 把 `curl` 当成 `Invoke-WebRequest`。
> - JSON 放在 `$json` 变量里，不需要各种转义。

3. 如果返回类似下面的 JSON（只示意结构）：

```json
{
  "id": "chatcmpl-xxxx",
  "object": "chat.completion",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "你好，我是 ... （省略）"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": { ... }
}
```

其中 `choices[0].message.content` 里有模型回复内容，说明大模型服务正常。

---

### 七、voice-assistant-api 配置与使用示例

将大模型（vLLM）和向量模型（Ollama）接入 voice-assistant-api，实现本地 RAG 语音助手。

#### 9.1 配置 application.yml

```yaml
voice:
  # 大模型：vLLM（WSL2 中运行，Windows 通过 localhost 访问）
  vllm-base-url: http://localhost:8000
  vllm-model: deepseek-ai/DeepSeek-R1-Distill-Qwen-7B

  # 向量 Embedding：Ollama 本地
  embedding-provider: ollama
  ollama-base-url: http://localhost:11434
  ollama-embedding-model: qwen3-embedding

  # RAG：启用向量检索
  rag-enabled: true
  rag-top-k: 5
```

#### 7.2 服务启动顺序

1. **WSL2 Ubuntu**：启动 vLLM（第六节命令），保持终端不关
2. **Windows**：Ollama 安装后自动运行，无需额外操作
3. **Windows**：启动 voice-assistant-api（`mvn spring-boot:run` 等）

#### 9.3 向量库入库

先往向量库添加知识文档（用于 RAG）：

```powershell
# 单条添加
curl.exe -X POST http://localhost:8080/api/vector/documents `
  -H "Content-Type: application/json" `
  -d "{\"text\": \"打开客厅灯：说「打开客厅灯」或「开灯」\"}"

# 批量添加
curl.exe -X POST http://localhost:8080/api/vector/documents/batch `
  -H "Content-Type: application/json" `
  -d "{\"texts\": [\"关闭卧室灯：说「关灯」\", \"调节亮度：说「调亮一点」\"]}"
```

#### 9.4 语义检索测试

```powershell
curl.exe "http://localhost:8080/api/vector/search?query=怎么开灯&topK=3"
```

返回与「开灯」语义相近的知识文档。

#### 9.5 语音对话（带 RAG）

使用本地管道 `POST /api/voice/upload`，上传 WAV 录音：

```powershell
curl.exe -X POST http://localhost:8080/api/voice/upload -F "file=@recording.wav"
```

流程：**ASR** → **向量检索**（根据用户问题查知识库，拼入 prompt）→ **vLLM 生成回复** → **TTS** → 返回 `{ text, reply, audioUrl }`。

---

### 十、在 Spring / Java 项目里直接调用 vLLM

你现在可以把 `http://localhost:8000/v1/chat/completions` 当成一个 **本地 OpenAI 兼容服务**，在 Java 里通过 `RestTemplate` 或 `WebClient` 调用即可。

下面是一个简化的 `RestTemplate` 示例（仅示意）：

```java
RestTemplate restTemplate = new RestTemplate();

String url = "http://localhost:8000/v1/chat/completions";

Map<String, Object> body = new HashMap<>();
body.put("model", "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B");

List<Map<String, String>> messages = new ArrayList<>();
messages.add(Map.of("role", "user", "content", "用中文简单自我介绍一下"));
body.put("messages", messages);

HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);

HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

ResponseEntity<Map> resp = restTemplate.postForEntity(url, entity, Map.class);
Map<String, Object> respBody = resp.getBody();
List<Map<String, Object>> choices = (List<Map<String, Object>>) respBody.get("choices");
Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
String content = (String) msg.get("content");
System.out.println(content);
```

---

### 十一、常见问题

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| `nvidia-smi` 报错或找不到 | 驱动未装/过旧，或 WSL2 未识别 GPU | 在 Windows 安装最新 NVIDIA 驱动并重启 |
| vLLM 报 `CUDA out of memory` | 显存不足 | 换小模型、加 `--gpu-memory-utilization 0.8`，或使用量化版 |
| `localhost:8000` 连不上 | 未加 `--host 0.0.0.0`，或防火墙拦截 | 确认启动参数，检查 Windows 防火墙 |
| 模型下载很慢 | 访问 Hugging Face 受限 | 配置 `HF_ENDPOINT` 镜像或提前离线下载模型 |
| Ollama embedding 调用失败 | Ollama 未启动或模型未拉取 | 确认 Ollama 在运行，执行 `ollama pull qwen3-embedding` |

---

### 十二、显卡 / 显存不够时的建议

- 显存 < 16 GB：  
  - 可以考虑换更小的蒸馏模型（例如 7B 的量化版，或者 Qwen2.5-3B 等）。  
  - 或改用 **Ollama** + 量化模型（部署更轻量）。

- 显存充足（> 24/32/48 GB）：  
  - 可以尝试更大的蒸馏模型，比如 `DeepSeek-R1-Distill-Qwen-14B` / `32B`，命令只要改 `--model` 即可。

---

### 十三、小结

| 组件 | 部署方式 | 端口 | 用途 |
|------|----------|------|------|
| DeepSeek-R1 蒸馏 7B | WSL2 + Ubuntu + vLLM | 8000 | 大模型推理 |
| qwen3-embedding | Windows Ollama | 11434 | 向量 Embedding / RAG |

整体流程：**Windows 装驱动** → **WSL2 部署 vLLM** → **Windows 安装 Ollama + qwen3-embedding** → **配置 voice-assistant-api** → **入库 + 对话**。

若需集成到 Spring Cloud / 微服务体系，可在网关层加代理，将请求转发到 vLLM 服务。

