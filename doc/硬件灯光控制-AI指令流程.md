# 简单硬件灯光控制设备制作指南

> 从 AI 对话到硬件执行关灯的完整流程

---

## 一、整体架构概述

```
用户输入 "关灯"
    ↓
AI 解析意图 → 生成控制指令（如 JSON: {"action":"light_off"}）
    ↓
指令通过串口/WiFi 发送到硬件
    ↓
单片机解析指令 → 控制 GPIO 输出
    ↓
继电器/LED 执行 → 灯关闭
```

---

## 二、方案选型

| 方案 | 通信方式 | 难度 | 适用场景 |
|------|----------|------|----------|
| A. Arduino + USB 串口 | 电脑通过 USB 连接 | ★★☆☆☆ | 学习、桌面场景 |
| B. ESP8266/ESP32 + WiFi | 局域网 TCP/UDP/HTTP | ★★★☆☆ | 远程控制、智能家居 |
| C. ESP32 + 蓝牙 | 蓝牙 BLE | ★★★☆☆ | 手机近场控制 |

**推荐入门方案**：Arduino + USB 串口（成本低、易调试）

---

## 三、材料清单（Arduino USB 方案）

### 必需材料

| 序号 | 名称 | 规格/型号 | 数量 | 参考价格 | 用途 |
|------|------|-----------|------|----------|------|
| 1 | Arduino 开发板 | Arduino Uno / Nano / Mega | 1 | 20–50 元 | 主控芯片 |
| 2 | USB 数据线 | Type-A 转 Micro-B / Type-C | 1 | 5–15 元 | 与电脑通信 |
| 3 | 继电器模块 | 5V 单路继电器（带光耦） | 1 | 5–10 元 | 安全控制 220V 灯 |
| 4 | 杜邦线 | 公对母 | 若干 | 5 元 | 接线 |
| 5 | 灯 / LED | 可选：小灯带或 220V 灯泡 | 1 | 按需 | 被控负载 |

### 若仅做低压演示（不碰 220V）

| 序号 | 名称 | 用途 |
|------|------|------|
| 1 | LED 灯 | 5mm 红/绿/黄 |
| 2 | 限流电阻 | 220Ω–330Ω |
| 3 | 杜邦线 | 连接 Arduino 与 LED |

### 工具

- 螺丝刀、剥线钳（若接 220V 需格外注意安全）
- 电脑（安装 Arduino IDE 或 PlatformIO）

---

## 四、硬件连接

### 4.1 低压 LED 演示接线

```
Arduino Pin 13 ──[220Ω]── LED 正极
Arduino GND   ─────────── LED 负极
```

### 4.2 继电器控制 220V 灯（务必断电操作）

```
Arduino 5V    ─── 继电器 VCC
Arduino GND   ─── 继电器 GND
Arduino Pin 7 ─── 继电器 IN（信号）

220V 电源 ──[继电器常开]── 灯泡 ── 零线
```

> ⚠️ **安全提示**：220V 接线必须由具备电工资质的人员完成，或在专业指导下进行。

---

## 五、Arduino 程序（固件）

### 5.1 协议约定

采用**文本行协议**，每条指令一行，便于串口收发与 AI 生成：

| 指令 | 含义 |
|------|------|
| `LIGHT_ON` | 开灯 |
| `LIGHT_OFF` | 关灯 |
| `LIGHT_TOGGLE` | 切换状态 |
| `STATUS` | 查询当前灯状态 |

### 5.2 完整代码

```cpp
/*
 * 串口灯光控制器
 * 协议：每行一个命令，LIGHT_ON / LIGHT_OFF / LIGHT_TOGGLE / STATUS
 */

#define LIGHT_PIN 7   // 控制继电器的引脚（或 LED 接 13）
#define SERIAL_BAUD 9600

bool lightState = false;
String inputBuffer = "";

void setup() {
  pinMode(LIGHT_PIN, OUTPUT);
  Serial.begin(SERIAL_BAUD);
  digitalWrite(LIGHT_PIN, LOW);  // 初始关灯
}

void loop() {
  // 读取串口数据
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (inputBuffer.length() > 0) {
        processCommand(inputBuffer.trim());
        inputBuffer = "";
      }
    } else {
      inputBuffer += c;
      if (inputBuffer.length() > 32) inputBuffer = "";  // 防溢出
    }
  }
}

void processCommand(String cmd) {
  cmd.toUpperCase();
  if (cmd == "LIGHT_ON") {
    lightState = true;
    digitalWrite(LIGHT_PIN, HIGH);
    Serial.println("OK:light_on");
  } else if (cmd == "LIGHT_OFF") {
    lightState = false;
    digitalWrite(LIGHT_PIN, LOW);
    Serial.println("OK:light_off");
  } else if (cmd == "LIGHT_TOGGLE") {
    lightState = !lightState;
    digitalWrite(LIGHT_PIN, lightState ? HIGH : LOW);
    Serial.println(lightState ? "OK:light_on" : "OK:light_off");
  } else if (cmd == "STATUS") {
    Serial.println(lightState ? "STATUS:on" : "STATUS:off");
  } else {
    Serial.println("ERR:unknown_command");
  }
}
```

### 5.3 烧录步骤

1. 安装 [Arduino IDE](https://www.arduino.cc/en/software)
2. 选择板型：`工具` → `开发板` → `Arduino Uno`（或对应型号）
3. 选择串口：`工具` → `端口` → 选择对应 COM 口
4. 点击 `上传` 完成烧录

---

## 六、电脑端桥接程序（Python）

用于接收 AI 或脚本生成的指令，通过串口发给 Arduino。

### 6.1 依赖

```bash
pip install pyserial
```

### 6.2 Python 桥接脚本

```python
# serial_light_bridge.py
import serial
import sys
import time

# 根据实际 COM 口修改，Windows 如 COM3，Linux 如 /dev/ttyUSB0
PORT = "COM3"
BAUD = 9600

def send_command(cmd: str) -> str:
    """发送命令到 Arduino，返回响应"""
    try:
        ser = serial.Serial(PORT, BAUD, timeout=1)
        time.sleep(0.1)  # 等待串口稳定
        ser.write((cmd.strip() + "\n").encode())
        time.sleep(0.1)
        response = ser.readline().decode().strip()
        ser.close()
        return response
    except Exception as e:
        return f"ERROR:{e}"

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python serial_light_bridge.py LIGHT_ON|LIGHT_OFF|LIGHT_TOGGLE|STATUS")
        sys.exit(1)
    cmd = sys.argv[1]
    result = send_command(cmd)
    print(result)
```

### 6.3 使用示例

```bash
python serial_light_bridge.py LIGHT_OFF
# 输出: OK:light_off
```

---

## 七、AI 生成指令的完整流程

### 7.1 流程说明

1. 用户在对话中输入：「关灯」「把灯关上」「turn off the light」等
2. AI（或本地 LLM）解析意图，输出标准化指令：`LIGHT_OFF`
3. 调用桥接程序，将 `LIGHT_OFF` 通过串口发送给 Arduino
4. Arduino 解析并执行，灯关闭

### 7.2 AI 端提示词设计（适用于 GPT/Claude/本地模型）

```
你是一个家居控制助手。用户可能用自然语言表达灯光控制意图。

规则：
- 当用户表达「开灯」「打开灯」「开一下灯」等 → 输出唯一一行：LIGHT_ON
- 当用户表达「关灯」「关闭灯」「关一下灯」「把灯关掉」等 → 输出唯一一行：LIGHT_OFF
- 当用户表达「切换」「 toggle」等 → 输出唯一一行：LIGHT_TOGGLE
- 当用户表达「灯的状态」「灯开了吗」等 → 输出唯一一行：STATUS
- 其他与灯无关的对话，正常回答，不要输出上述指令。

只输出指令或正常对话内容，不要额外解释。
```

### 7.3 集成示例（伪代码）

```python
# ai_light_controller.py
import subprocess

def user_says(text: str):
    """用户输入 -> AI 解析 -> 执行硬件"""
    # 1. 调用 AI/LLM 接口，传入 text 和上述 prompt
    ai_response = call_llm(prompt=PROMPT, user_input=text)
    
    # 2. 检查是否包含控制指令
    cmd = None
    for c in ["LIGHT_ON", "LIGHT_OFF", "LIGHT_TOGGLE", "STATUS"]:
        if c in ai_response:
            cmd = c
            break
    
    # 3. 若有指令，调用串口桥接
    if cmd:
        result = subprocess.run(
            ["python", "serial_light_bridge.py", cmd],
            capture_output=True, text=True
        )
        return result.stdout.strip()
    return ai_response  # 非控制对话，直接返回 AI 回复
```

### 7.4 指令映射表（供 AI 生成参考）

| 用户表述示例 | 生成指令 |
|--------------|----------|
| 关灯、把灯关掉、关一下灯 | LIGHT_OFF |
| 开灯、打开灯、亮灯 | LIGHT_ON |
| 切换、翻转、toggle | LIGHT_TOGGLE |
| 灯的状态、灯开了吗 | STATUS |

---

## 八、原理简述

### 8.1 串口通信

- Arduino 通过 USB 虚拟出一个串口（如 COM3）
- 电脑发送字节流，Arduino 在 `loop()` 中调用 `Serial.available()` 和 `Serial.read()` 接收
- 约定以换行符 `\n` 作为一条指令的结束

### 8.2 GPIO 控制

- `digitalWrite(pin, HIGH)`：输出 5V，继电器吸合或 LED 亮
- `digitalWrite(pin, LOW)`：输出 0V，继电器断开或 LED 灭

### 8.3 继电器原理

- 小电流控制大电流：Arduino 输出 5V 驱动继电器线圈
- 继电器内部机械开关切换 220V 回路，实现安全隔离

---

## 九、故障排查

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 串口打不开 | 端口被占用或选错 | 关闭其它占用串口的程序，重新选 COM 口 |
| 发送无反应 | 波特率不一致 | 确保 Arduino 与 Python 均为 9600 |
| 灯不亮 | 接线错误、继电器反接 | 检查 VCC/GND/IN 接线，确认继电器规格 |
| AI 不输出指令 | Prompt 或解析逻辑有误 | 检查 prompt 与 response 解析代码 |

---

## 十、扩展方向

1. **WiFi 控制**：换用 ESP8266/ESP32，通过 HTTP 或 MQTT 接收指令
2. **语音控制**：接入语音识别，将「关灯」转为文本再交给 AI
3. **多路灯光**：增加 `LIGHT1_OFF`、`LIGHT2_ON` 等指令，扩展继电器数量
4. **定时/场景**：在 Python 端实现定时任务或场景组合

---

## 十一、ESP8266/ESP32 WiFi 控制详解

### 11.1 开发框架对比（Arduino / ESP-IDF / MicroPython）

| 对比维度 | Arduino 框架 | ESP-IDF（方案 B） | MicroPython（方案 C） |
|----------|--------------|-------------------|------------------------|
| 编程语言 | C++ | C / C++ | Python |
| 运行方式 | 编译执行 | 编译执行（直接机器码） | 解释执行（Python 虚拟机） |
| 开发门槛 | 低 | 较高（需懂嵌入式、指针、内存） | 极低（语法简单，无需深究硬件） |
| 运行效率 | 高 | ⚡ 极高（直接操作硬件） | 🐢 较低（有解释器开销） |
| 实时性 | 较强 | 强（微秒级，适合控制） | 弱（响应有延迟，不适合精确定时） |
| 开发工具 | Arduino IDE | VS Code + 命令行（工程化） | Thonny / WebREPL（交互式） |
| 适用场景 | 原型、教学、简单 IoT | 商业产品、复杂协议、AI 推理 | 教学、快速验证、简单 IoT 小玩具 |

> **说明**：ESP-IDF 官方主要支持 **ESP32**（含 S2/S3/C3 等系列），ESP8266 可用但生态较弱；Arduino / MicroPython 同时支持 ESP8266 与 ESP32。

### 11.2 通信协议对比

| 方式 | 优点 | 缺点 |
|------|------|------|
| HTTP | 实现简单，用浏览器/curl 即可测试 | 需设备与电脑在同一局域网 |
| MQTT | 支持订阅/发布，适合多设备、远程 | 需部署 MQTT Broker（如 Mosquitto） |

### 11.3 材料差异

在 Arduino 方案基础上替换主控：

| 原 Arduino Uno | 替换为 |
|----------------|--------|
| Arduino Uno + USB | **ESP8266 NodeMCU** 或 **ESP32 DevKit** |

- ESP8266：约 15 元，单核，WiFi 2.4G
- ESP32：约 25 元，双核，WiFi+蓝牙，GPIO 更多

接线不变：继电器 VCC/GND 接板子 3.3V/GND，IN 接任意 GPIO（如 D1/GPIO5）。

### 11.4 HTTP 方式

设备连接 WiFi 后启动 Web 服务器，收到 `GET /light?action=off` 即关灯。

#### 11.4.1 Arduino 框架（ESP8266 示例）

```cpp
#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>

#define LIGHT_PIN 5   // D1
const char* ssid = "你的WiFi名";
const char* pass = "你的WiFi密码";

ESP8266WebServer server(80);
bool lightOn = false;

void handleLight() {
  String action = server.arg("action");
  if (action == "on") {
    lightOn = true;
    digitalWrite(LIGHT_PIN, HIGH);
    server.send(200, "text/plain", "OK:light_on");
  } else if (action == "off") {
    lightOn = false;
    digitalWrite(LIGHT_PIN, LOW);
    server.send(200, "text/plain", "OK:light_off");
  } else {
    server.send(200, "text/plain", lightOn ? "STATUS:on" : "STATUS:off");
  }
}

void setup() {
  pinMode(LIGHT_PIN, OUTPUT);
  WiFi.begin(ssid, pass);
  while (WiFi.status() != WL_CONNECTED) delay(500);
  server.on("/light", handleLight);
  server.begin();
}

void loop() {
  server.handleClient();
}
```

#### 11.4.2 ESP-IDF 框架（ESP32 示例）

使用 ESP-IDF 原生 `esp_http_server` 组件，适合商业项目、高性能、微秒级控制。

**依赖**：ESP-IDF v5.x，`esp_wifi` + `esp_http_server`（已内置）

**主要逻辑**：在 `main.c` 中注册 URI `/light`，从查询字符串解析 `action`，控制 GPIO。

```c
/* light_http_handler.c 片段 - ESP-IDF 风格 */
#include "esp_http_server.h"
#include "driver/gpio.h"

#define LIGHT_GPIO    GPIO_NUM_5
#define ACTION_MAX    16

static bool s_light_on = false;

static esp_err_t light_get_handler(httpd_req_t *req) {
    char buf[128];
    size_t buf_len = httpd_req_get_url_query_len(req) + 1;
    if (buf_len > sizeof(buf)) buf_len = sizeof(buf);

    if (httpd_req_get_url_query_str(req, buf, buf_len) == ESP_OK) {
        char action[ACTION_MAX] = {0};
        if (httpd_query_key_value(buf, "action", action, sizeof(action)) == ESP_OK) {
            if (strcmp(action, "on") == 0) {
                s_light_on = true;
                gpio_set_level(LIGHT_GPIO, 1);
                httpd_resp_send(req, "OK:light_on", HTTPD_RESP_USE_STRLEN);
                return ESP_OK;
            }
            if (strcmp(action, "off") == 0) {
                s_light_on = false;
                gpio_set_level(LIGHT_GPIO, 0);
                httpd_resp_send(req, "OK:light_off", HTTPD_RESP_USE_STRLEN);
                return ESP_OK;
            }
        }
    }
    httpd_resp_send(req, s_light_on ? "STATUS:on" : "STATUS:off", HTTPD_RESP_USE_STRLEN);
    return ESP_OK;
}

static const httpd_uri_t light = {
    .uri       = "/light",
    .method    = HTTP_GET,
    .handler   = light_get_handler,
};

// 在 start_webserver() 中注册: httpd_register_uri_handler(server, &light);
```

**工程结构**：需配置 WiFi（`menuconfig` 或 `idf.py menuconfig`）、NVS、`app_main` 中启动 WiFi + HTTP 服务器（可参考 `examples/protocols/http_server/simple`）。

**AI 端调用示例：** 用户说「关灯」→ AI 输出 `LIGHT_OFF` → 程序请求 `http://192.168.1.100/light?action=off`（IP 为 ESP 分配的地址）。

### 11.5 MQTT 方式

设备订阅主题 `home/light/cmd`，收到 `off` 即关灯；执行后向 `home/light/status` 发布状态。

#### 11.5.1 Arduino 框架

**依赖库**：`PubSubClient`（Arduino 库管理器搜索安装）

**ESP32 示例：**

```cpp
#include <WiFi.h>
#include <PubSubClient.h>

#define LIGHT_PIN 5
#define MQTT_TOPIC_CMD   "home/light/cmd"
#define MQTT_TOPIC_STAT  "home/light/status"

const char* ssid = "你的WiFi名";
const char* pass = "你的WiFi密码";
const char* mqttBroker = "192.168.1.10";  // MQTT 服务器 IP
const int   mqttPort = 1883;

WiFiClient espClient;
PubSubClient client(espClient);
bool lightOn = false;

void mqttCallback(char* topic, byte* payload, unsigned int len) {
  payload[len] = '\0';
  String msg = (char*)payload;
  if (msg == "on") {
    lightOn = true;
    digitalWrite(LIGHT_PIN, HIGH);
    client.publish(MQTT_TOPIC_STAT, "on");
  } else if (msg == "off") {
    lightOn = false;
    digitalWrite(LIGHT_PIN, LOW);
    client.publish(MQTT_TOPIC_STAT, "off");
  }
}

void setup() {
  pinMode(LIGHT_PIN, OUTPUT);
  WiFi.begin(ssid, pass);
  while (WiFi.status() != WL_CONNECTED) delay(500);
  client.setServer(mqttBroker, mqttPort);
  client.setCallback(mqttCallback);
}

void loop() {
  if (!client.connected()) {
    if (client.connect("ESP32_LIGHT")) {
      client.subscribe(MQTT_TOPIC_CMD);
    }
  }
  client.loop();
}
```

#### 11.5.2 ESP-IDF 框架

使用 ESP-IDF 内置 `mqtt` 组件，性能高、适合多设备、商业产品。

**依赖**：ESP-IDF v5.x，`esp_mqtt` 组件（已内置）

```c
/* mqtt_light_handler.c 片段 - ESP-IDF 风格 */
#include "mqtt_client.h"
#include "driver/gpio.h"

#define LIGHT_GPIO       GPIO_NUM_5
#define MQTT_TOPIC_CMD   "home/light/cmd"
#define MQTT_TOPIC_STAT  "home/light/status"

static esp_mqtt_client_handle_t client;
static bool s_light_on = false;

static void mqtt_event_handler(void *handler_args, esp_event_base_t base, int32_t id, void *event_data) {
    esp_mqtt_event_handle_t event = event_data;
    if (event->event_id == MQTT_EVENT_DATA) {
        if (strncmp(event->topic, MQTT_TOPIC_CMD, event->topic_len) == 0) {
            char action[16] = {0};
            int len = event->data_len < 15 ? event->data_len : 15;
            memcpy(action, event->data, len);
            if (strcmp(action, "on") == 0) {
                s_light_on = true;
                gpio_set_level(LIGHT_GPIO, 1);
                esp_mqtt_client_publish(client, MQTT_TOPIC_STAT, "on", 0, 0, 0);
            } else if (strcmp(action, "off") == 0) {
                s_light_on = false;
                gpio_set_level(LIGHT_GPIO, 0);
                esp_mqtt_client_publish(client, MQTT_TOPIC_STAT, "off", 0, 0, 0);
            }
        }
    }
}

void mqtt_light_init(void) {
    gpio_config_t io = { .pin_bit_mask = (1ULL << LIGHT_GPIO), .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE, .pull_down_en = GPIO_PULLDOWN_DISABLE, .intr_type = GPIO_INTR_DISABLE };
    gpio_config(&io);

    esp_mqtt_client_config_t mqtt_cfg = {
        .broker.address.uri = "mqtt://192.168.1.10:1883",
    };
    client = esp_mqtt_client_init(&mqtt_cfg);
    esp_mqtt_client_register_event(client, MQTT_EVENT_ANY, mqtt_event_handler, NULL);
    esp_mqtt_client_start(client);
    esp_mqtt_client_subscribe(client, MQTT_TOPIC_CMD, 0);
}
```

**工程配置**：WiFi + MQTT 初始化顺序、Broker 地址通过 `menuconfig` 或 Kconfig 配置。

**MQTT 测试：** 在电脑安装 MQTT 客户端（如 MQTTX），向 `home/light/cmd` 发布 `off`，即可远程关灯。

**AI 集成流程：** 用户说「关灯」→ AI 生成 `off` → 调用 MQTT 客户端向 `home/light/cmd` 发布 `off` → ESP 收到并执行。

### 11.6 与 AI 的桥接

| 方案 | AI 输出 | 桥接动作 |
|------|---------|----------|
| HTTP | `LIGHT_OFF` | `requests.get("http://ESP_IP/light?action=off")` |
| MQTT | `off` | `paho-mqtt` 向 `home/light/cmd` 发布 `off` |

### 11.7 框架与协议速查

| 协议 | Arduino 框架 | ESP-IDF 框架 |
|------|--------------|--------------|
| HTTP | ✅ `ESP8266WebServer` / `WebServer` | ✅ `esp_http_server` |
| MQTT | ✅ `PubSubClient` | ✅ `esp_mqtt` |
| 支持芯片 | ESP8266 / ESP32 | 以 ESP32 为主 |

---

## 附录：文件清单

| 文件 | 用途 |
|------|------|
| `arduino_light_controller.ino` | Arduino 固件（串口） |
| `serial_light_bridge.py` | 串口桥接脚本 |
| `ai_light_controller.py` | AI 集成示例（需根据实际 LLM 接口调整） |
| `esp32_light_http/` | ESP-IDF HTTP 灯光控制工程 |
| `esp32_light_mqtt/` | ESP-IDF MQTT 灯光控制工程 |

---

*文档版本：1.1 | Arduino / ESP-IDF / MicroPython 三框架可选，支持串口、HTTP、MQTT*
