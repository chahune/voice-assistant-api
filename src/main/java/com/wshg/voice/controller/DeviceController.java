package com.wshg.voice.controller;

import com.wshg.voice.entity.SmartHomeDevice;
import com.wshg.voice.repository.SmartHomeDeviceRepository;
import com.wshg.voice.service.DeviceControlService;
import com.wshg.voice.service.VectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 智能家居设备：CRUD + 手动触发控制（语音流程中由 DeviceControlService 自动查库并下发）。
 */
@Slf4j
@RestController
@RequestMapping("/api/device")
@RequiredArgsConstructor
public class DeviceController {

    private final SmartHomeDeviceRepository deviceRepository;
    private final DeviceControlService deviceControlService;
    private final VectorStoreService vectorStoreService;

    @GetMapping("/list")
    public List<SmartHomeDevice> list(@RequestParam(required = false) String room) {
        if (room != null && !room.isBlank()) {
            return deviceRepository.findByRoomAndEnabledTrue(room);
        }
        return deviceRepository.findByEnabledTrue();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SmartHomeDevice> get(@PathVariable Long id) {
        return deviceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public SmartHomeDevice create(@RequestBody SmartHomeDevice device) {
        log.info("[API] POST /api/device deviceId={}, room={}", device != null ? device.getDeviceId() : null, device != null ? device.getRoom() : null);
        SmartHomeDevice saved = deviceRepository.save(device);
        int synced = vectorStoreService.syncFromDevices();
        log.info("[API] /device 新增完成 id={}, 知识库同步文档数={}", saved.getId(), synced);
        return saved;
    }

    @PutMapping("/{id}")
    public ResponseEntity<SmartHomeDevice> update(@PathVariable Long id, @RequestBody SmartHomeDevice device) {
        if (!deviceRepository.existsById(id)) return ResponseEntity.notFound().build();
        device.setId(id);
        log.info("[API] PUT /api/device/{} deviceId={}", id, device.getDeviceId());
        SmartHomeDevice saved = deviceRepository.save(device);
        int synced = vectorStoreService.syncFromDevices();
        log.info("[API] /device 更新完成, 知识库同步文档数={}", synced);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!deviceRepository.existsById(id)) return ResponseEntity.notFound().build();
        log.info("[API] DELETE /api/device/{}", id);
        deviceRepository.deleteById(id);
        int synced = vectorStoreService.syncFromDevices();
        log.info("[API] /device 删除完成, 知识库同步文档数={}", synced);
        return ResponseEntity.noContent().build();
    }

    /** 手动控制：POST /api/device/control Body: {"room":"客厅","action":"on"} 或 {"deviceId":"xxx","action":"off"} */
    @PostMapping("/control")
    public ResponseEntity<Map<String, Object>> control(@RequestBody Map<String, String> body) {
        String room = body.get("room");
        String deviceId = body.get("deviceId");
        String action = body.get("action");
        log.info("[API] POST /api/device/control room={}, deviceId={}, action={}", room, deviceId, action);
        if (action == null || !action.equalsIgnoreCase("on") && !action.equalsIgnoreCase("off")) {
            return ResponseEntity.badRequest().body(Map.of("error", "action 必须为 on 或 off"));
        }
        boolean turnOn = "on".equalsIgnoreCase(action);
        int count = 0;
        if (deviceId != null && !deviceId.isBlank()) {
            var dev = deviceRepository.findByDeviceId(deviceId);
            if (dev.isPresent() && deviceControlService.sendControl(dev.get(), turnOn)) count = 1;
        } else {
            count = deviceControlService.executeByRoom(room != null ? room : "all", turnOn);
        }
        log.info("[API] /device/control 完成 success={}, count={}", count > 0, count);
        return ResponseEntity.ok(Map.of("success", count > 0, "count", count));
    }
}
