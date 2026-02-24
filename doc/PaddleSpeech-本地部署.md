# PaddleSpeech 本地部署指南

> 百度开源语音工具：语音识别（ASR）+ 语音合成（TTS），可完全离线运行

---

## 一、环境要求

| 项目 | 要求 |
|------|------|
| 系统 | Linux（推荐）、Windows、Mac |
| Python | >= 3.8（推荐 3.8 或 3.9） |
| 内存 | 建议 8GB 以上，跑大模型建议 16GB |
| 磁盘 | 模型会下载数 GB，预留 5GB+ |
| 可选 | 有 GPU 可加速，CPU 也能跑 |

---

## 二、安装步骤

### 2.1 创建虚拟环境（推荐）

```bash
# 使用 conda（若已安装）
conda create -n paddlespeech python=3.9
conda activate paddlespeech

# 或使用 venv
python -m venv paddlespeech_env
# Windows: paddlespeech_env\Scripts\activate
# Linux/Mac: source paddlespeech_env/bin/activate
```

### 2.2 安装 PaddlePaddle（底层框架）

**CPU 版本：**
```bash
pip install paddlepaddle -i https://mirror.baidu.com/pypi/simple
```

**指定版本（示例）：**
```bash
pip install paddlepaddle==2.5.2 -i https://mirror.baidu.com/pypi/simple
```

**GPU 版本（有 NVIDIA 显卡时）：**  
到 [PaddlePaddle 官网](https://www.paddlepaddle.org.cn/install/quick) 按 CUDA 版本选择安装命令。

### 2.3 安装 PaddleSpeech

```bash
pip install pytest-runner
pip install paddlespeech -i https://pypi.tuna.tsinghua.edu.cn/simple
```

若安装慢或报错，可先试官方源：
```bash
pip install paddlespeech
```

---

## 三、首次使用会下载模型

第一次运行语音识别或语音合成时，会自动从网络下载预训练模型（几百 MB 到数 GB），只需等待即可。之后可离线使用。

---

## 四、语音合成（TTS）— 文字转语音

### 4.1 命令行

```bash
paddlespeech tts --input "你好，欢迎使用百度飞桨语音合成。" --output output.wav
```

生成的文件默认在当前目录：`output.wav`（约 24kHz 采样率）。

### 4.2 Python 调用

```python
from paddlespeech.cli.tts.infer import TTSExecutor

tts = TTSExecutor()
tts(text="今天天气十分不错。", output="output.wav")
```

### 4.3 指定音色（可选）

部分模型支持不同发音人，具体见官方文档 [TTS 模型列表](https://github.com/PaddlePaddle/PaddleSpeech#text-to-speech)。

---

## 五、语音识别（ASR）— 语音转文字

### 5.1 准备音频

支持 16kHz、16bit、单声道 WAV。若格式不对，可用 ffmpeg 转换：
```bash
ffmpeg -i input.mp3 -ar 16000 -ac 1 output.wav
```

### 5.2 命令行

```bash
# 下载示例音频（可选）
wget -c https://paddlespeech.cdn.bcebos.com/PaddleAudio/zh.wav

# 中文识别
paddlespeech asr --lang zh --input zh.wav
```

### 5.3 Python 调用

```python
from paddlespeech.cli.asr.infer import ASRExecutor

asr = ASRExecutor()
result = asr(audio_file="zh.wav")
print(result)  # 输出识别文字
```

---

## 六、一键语音对话（ASR → 大模型 → TTS）

本地用 PaddleSpeech 做 ASR + TTS，中间接大模型 API 或本地 LLM，即可实现：

1. 麦克风录音 → **PaddleSpeech ASR** → 文字  
2. 文字 → **大模型**（如 HTTP 调 API）→ 回复文字  
3. 回复文字 → **PaddleSpeech TTS** → 生成 WAV → 播放  

示例流程（需自行接麦克风、大模型 API 和播放器）：

```python
from paddlespeech.cli.asr.infer import ASRExecutor
from paddlespeech.cli.tts.infer import TTSExecutor
# import requests  # 调大模型 API
# import sounddevice as sd  # 播放音频

asr = ASRExecutor()
tts = TTSExecutor()

# 1. 录音保存为 rec.wav 后
# text = asr(audio_file="rec.wav")

# 2. 调用大模型得到 reply_text
# reply_text = requests.post(...).json()["content"]

# 3. TTS 并播放
# tts(text=reply_text, output="reply.wav")
# sd.play(...)
```

---

## 七、启动本地语音服务（供 ESP32 等调用）

PaddleSpeech 支持以**服务**方式启动，ESP32 通过 HTTP 请求本机 TTS 得到 MP3/WAV。

### 7.1 启动服务

```bash
# 进入 PaddleSpeech 源码目录（若从 git 克隆）
cd PaddleSpeech
paddlespeech_server start --config_file ./demos/speech_server/conf/application.yaml
```

或使用流式 TTS 服务：
```bash
paddlespeech_server start --config_file ./demos/streaming_tts_server/conf/tts_online_application.yaml
```

### 7.2 客户端请求 TTS

```bash
paddlespeech_client tts --server_ip 127.0.0.1 --port 8090 --input "您好，欢迎使用百度飞桨语音合成服务。" --output output.wav
```

服务端跑在你电脑上时，ESP32 或手机可在同一局域网用 `电脑IP:8090` 访问（需确认配置文件中的 `host` 为 `0.0.0.0`）。

---

## 七（续）、不在同一局域网时调用

客户端（如手机、ESP32 在别处 WiFi）和运行 PaddleSpeech 的电脑不在同一局域网时，需要让「外网」能访问你本机的服务端口，常用做法如下。

### 方案一：内网穿透（推荐，无需公网 IP）

在运行 PaddleSpeech 的电脑上装一个**内网穿透**工具，把本机 `127.0.0.1:8090` 映射到一个**公网域名或 URL**，外网通过该 URL 访问即可。

| 工具 | 说明 | 使用方式 |
|------|------|----------|
| **cpolar**（国内） | 国产，有免费版 | 安装客户端，创建 HTTP 隧道映射 8090 端口，得到公网 URL |
| **花生壳** | 国内 | 类似，内网映射 + 免费/付费域名 |
| **ngrok** | 国外 | `ngrok http 8090` 得到临时公网 URL |
| **frp** | 自建 | 需有一台有公网 IP 的服务器，自己配置 frp 服务端与客户端 |

**示例（cpolar）：**  
安装 cpolar → 登录 → 添加隧道，本地端口填 `8090`，协议选 HTTP → 会得到一个类似 `https://xxxx.cpolar.cn` 的地址，外网用该地址 + 接口路径调用 TTS 即可。

调用时：把原来的 `http://电脑IP:8090` 换成内网穿透给的 **公网 URL**（如 `https://xxxx.cpolar.cn`），其它请求方式不变。

### 方案二：路由器端口映射（有公网 IP 时）

若家里宽带是**公网 IP**（可向运营商申请）：

1. 在路由器里做**端口映射**：外网访问「路由器公网IP:8090」时转发到「运行 PaddleSpeech 的电脑内网IP:8090」。
2. PaddleSpeech 配置里 `host` 设为 `0.0.0.0`，监听所有网卡。
3. 外网客户端用 `http://你的公网IP:8090` 调用。

注意：很多家庭宽带没有公网 IP 或 80/443 被封，此时用方案一更稳妥。

### 方案三：部署到云服务器

把 PaddleSpeech 直接装在**云服务器**（阿里云/腾讯云/AWS 等）上，服务器本身有公网 IP：

- 在服务器上按本文档安装并启动 PaddleSpeech 服务，`host` 设为 `0.0.0.0`。
- 安全组/防火墙放行 8090（或你用的端口）。
- 外网用 `http://服务器公网IP:8090` 调用。

适合长期、多人使用，需承担云主机费用。

### 方案四：VPN 组网

让手机/ESP32 和家里的电脑接入**同一个 VPN**（如 WireGuard、ZeroTier、Tailscale）：

- 电脑和客户端都装同一 VPN，获得虚拟局域网 IP。
- PaddleSpeech 仍监听 `0.0.0.0:8090`，客户端用「电脑在 VPN 里的 IP:8090」访问。

这样在逻辑上仍是「同一局域网」访问，无需改 PaddleSpeech 配置，只是多一步 VPN 连接。

---

### 小结

| 场景 | 建议 |
|------|------|
| 不想买服务器、没有公网 IP | **内网穿透**（cpolar / 花生壳 / ngrok） |
| 有公网 IP、会设路由器 | **端口映射** |
| 长期、稳定、多人用 | **云服务器**部署 PaddleSpeech |
| 希望像局域网一样访问 | **VPN**（ZeroTier / Tailscale 等） |

无论用哪种方式，**调用方式不变**：只是把「服务器地址」从 `http://局域网IP:8090` 换成对应的**公网 URL 或 公网IP:端口**即可。

---

## 八、常见问题

| 现象 | 处理 |
|------|------|
| 安装报错 gcc / 编译错误 | Linux 安装 `build-essential`；Windows 可试 `pip install paddlespeech` 预编译包 |
| 首次运行很慢 | 正在下载模型，等完成后再用 |
| 内存不足 | 换用更小的 ASR/TTS 模型，或关闭其它程序 |
| 中文识别不准 | 确认音频为 16kHz、16bit、单声道 WAV |
| Windows 下报错 | 参考 [官方安装文档](https://github.com/PaddlePaddle/PaddleSpeech/blob/develop/docs/source/install.md) 或 Issue |

---

## 九、voice-assistant-api 中的 paddlespeech-cmd 配置与使用

在 **voice-assistant-api** 项目中，本地模式通过配置项 `voice.paddlespeech-cmd` 指定 PaddleSpeech 命令行，用于 ASR 和 TTS。

### 9.1 含义

`paddlespeech-cmd: paddlespeech` 表示：**本地模式**下，ASR 和 TTS 都会在系统里执行该命令（默认是 `paddlespeech`）。

代码中实际执行的命令为：

- **语音转文本（ASR）**：`paddlespeech asr --lang zh --input <你的 wav 文件>`
- **文本转语音（TTS）**：`paddlespeech tts --input "文本" --output <输出 wav>`

因此需要保证系统里能执行到 PaddleSpeech 对应的可执行文件。

### 9.2 使用步骤

**① 安装 PaddleSpeech（需 Python 3.8+）**

```bash
# 先装 PaddlePaddle（按官方文档选 CPU/GPU 版本）
pip install paddlepaddle

# 再装 PaddleSpeech
pip install paddlespeech
```

或用 conda 按官方文档安装（参见本文档第二节）。

**② 确认命令可用**

在终端执行：

```bash
paddlespeech asr --help
```

能出现帮助信息说明命令在 PATH 里，此时配置 `paddlespeech-cmd: paddlespeech` 即可直接使用。

**③ 命令不在 PATH 时**

将 `paddlespeech-cmd` 改为可执行文件的**绝对路径**，例如在 `application.yml` 的 local 配置段：

```yaml
# Windows 示例（按本机路径修改）
paddlespeech-cmd: "C:/Python311/Scripts/paddlespeech"

# Linux / macOS 示例
paddlespeech-cmd: /usr/local/bin/paddlespeech
```

**④ 启动应用并走本地流程**

- 保持 `spring.profiles.active: local`（项目默认已是 local）。
- 启动 voice-assistant-api 后，通过 **POST /api/voice/upload** 上传音频，本地会自动用 PaddleSpeech 做 ASR 和 TTS。

### 9.3 小结

| 配置项 | 作用 | 你需要做的 |
|--------|------|------------|
| `paddlespeech-cmd: paddlespeech` | 指定 PaddleSpeech 命令行 | 安装 PaddleSpeech，并保证终端能执行 `paddlespeech` |
| 命令不在 PATH | 改用绝对路径 | 在配置中写成 `paddlespeech-cmd: "你的全路径"` |

---

## 十、参考链接

- [PaddleSpeech GitHub](https://github.com/PaddlePaddle/PaddleSpeech)
- [安装说明](https://github.com/PaddlePaddle/PaddleSpeech/blob/develop/docs/source/install.md)
- [TTS 文档](https://github.com/PaddlePaddle/PaddleSpeech/tree/develop/docs/source/tts)
- [语音服务 Demo](https://github.com/PaddlePaddle/PaddleSpeech/tree/develop/demos/speech_server)
