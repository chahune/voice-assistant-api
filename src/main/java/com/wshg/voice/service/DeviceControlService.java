package com.wshg.voice.service;

import com.wshg.voice.entity.SmartHomeDevice;
import com.wshg.voice.repository.SmartHomeDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设备控制服务：根据房间/动作查库，向设备发送开关指令。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceControlService {

    private final SmartHomeDeviceRepository deviceRepository;
    private final RestTemplate restTemplate;

    /** 大模型回复中设备控制标记，如 [DEVICE_CTL] room=客厅 action=on */
    public static final Pattern DEVICE_CTL_PATTERN = Pattern.compile(
            "\\[DEVICE_CTL\\]\\s*room=([^\\s]+)\\s+action=(on|off)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 从 LLM 回复中解析设备控制意图。若匹配到 [DEVICE_CTL] room=xxx action=on|off，返回 room 和 action，否则返回 null。
     */
    public DeviceControlIntent parseIntent(String llmReply) {
        if (llmReply == null || llmReply.isBlank()) return null;
        Matcher m = DEVICE_CTL_PATTERN.matcher(llmReply);
        if (!m.find()) return null;
        return new DeviceControlIntent(m.group(1).trim(), "on".equalsIgnoreCase(m.group(2)));
    }

    /**
     * 从回复文本中移除 [DEVICE_CTL] 行，用于 TTS 只读自然语言部分。
     */
    public String stripDeviceControlLine(String reply) {
        if (reply == null) return "";
        return DEVICE_CTL_PATTERN.matcher(reply).replaceAll("").replaceAll("\\n\\s*\\n", "\n").trim();
    }

    /**
     * 异步根据房间与动作查询设备并下发指令，不阻塞调用方（如语音管道）。适用于设备可能超时或离线的场景。
     */
    @Async
    public void executeByRoomAsync(String room, boolean turnOn) {
        int count = executeByRoom(room, turnOn);
        log.info("[设备控制] 异步执行完成: room={}, turnOn={}, 成功设备数={}", room, turnOn ? "on" : "off", count);
    }

    /**
     * 根据房间与动作查询设备并下发指令。返回执行成功的设备数量。
     */
    public int executeByRoom(String room, boolean turnOn) {
        List<SmartHomeDevice> devices = "all".equalsIgnoreCase(room)
                ? deviceRepository.findByEnabledTrue()
                : deviceRepository.findByRoomAndEnabledTrue(room);
        log.info("[设备控制] 查库: room={}, 匹配设备数={}", room, devices != null ? devices.size() : 0);
        if (devices == null || devices.isEmpty()) {
            return 0;
        }
        int ok = 0;
        for (SmartHomeDevice d : devices) {
            if (sendControl(d, turnOn)) ok++;
        }
        log.info("[设备控制] 下发完成: 成功={}/{}", ok, devices.size());
        return ok;
    }

    /**
     * 向单个设备发送开关指令。
     * connectionUrl：设备控制接口 base URL。controlOn/controlOff：GET 时为路径，POST 时为 JSON body 或路径。
     */
    public boolean sendControl(SmartHomeDevice device, boolean turnOn) {
        if (device == null || device.getConnectionUrl() == null || device.getConnectionUrl().isBlank()) {
            log.warn("设备无连接地址: {}", device != null ? device.getDeviceId() : null);
            return false;
        }
        String raw = turnOn ? device.getControlOn() : device.getControlOff();
        if (raw == null || raw.isBlank()) {
            log.warn("设备未配置 {} 指令: {}", turnOn ? "开" : "关", device.getDeviceId());
            return false;
        }
        String baseUrl = device.getConnectionUrl().replaceAll("/$", "");
        boolean isGet = "GET".equalsIgnoreCase(device.getControlMethod());
        try {
            if (isGet) {
                String fullUrl = raw.startsWith("http") ? raw : baseUrl + (raw.startsWith("/") ? raw : "/" + raw);
                ResponseEntity<String> res = restTemplate.getForEntity(fullUrl, String.class);
                if (res.getStatusCode().is2xxSuccessful()) {
                    log.info("[设备控制] 成功 GET deviceId={}, action={}", device.getDeviceId(), turnOn ? "on" : "off");
                    return true;
                }
            } else {
                if (raw.trim().startsWith("{") || raw.trim().startsWith("[")) {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    HttpEntity<String> entity = new HttpEntity<>(raw, headers);
                    ResponseEntity<String> res = restTemplate.postForEntity(baseUrl, entity, String.class);
                    if (res.getStatusCode().is2xxSuccessful()) {
                        log.info("[设备控制] 成功 POST deviceId={}, action={}, url={}", device.getDeviceId(), turnOn ? "on" : "off", baseUrl);
                        return true;
                    }
                } else {
                    String path = raw.startsWith("/") ? raw : "/" + raw;
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    HttpEntity<Void> entity = new HttpEntity<>(headers);
                    ResponseEntity<String> res = restTemplate.exchange(baseUrl + path, HttpMethod.POST, entity, String.class);
                    if (res.getStatusCode().is2xxSuccessful()) {
                        log.info("[设备控制] 成功 POST deviceId={}, action={}, path={}", device.getDeviceId(), turnOn ? "on" : "off", path);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[设备控制] 失败 deviceId={}, action={}, error={}", device.getDeviceId(), turnOn ? "on" : "off", e.getMessage());
        }
        return false;
    }

    public record DeviceControlIntent(String room, boolean turnOn) {}
}
