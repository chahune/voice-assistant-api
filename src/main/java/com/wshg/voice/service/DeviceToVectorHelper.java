package com.wshg.voice.service;

import com.wshg.voice.entity.SmartHomeDevice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将智能家居设备表信息转为知识库文档（text + metadata），用于 RAG 检索与语音控制说明。
 */
public final class DeviceToVectorHelper {

    private static final String CONTROL_API = "POST /api/device/control";

    /**
     * 根据设备生成知识库文本（关灯/开灯指令、房间、操作步骤、调用方法）。
     */
    public static String buildText(SmartHomeDevice d) {
        String room = d.getRoom() != null ? d.getRoom() : "未知";
        String deviceId = d.getDeviceId() != null ? d.getDeviceId() : "";
        String name = d.getDeviceName() != null ? d.getDeviceName() : deviceId;
        return String.format(
                "关灯指令：说「关灯」或「关闭灯光」或「关%s灯」。开灯指令：说「开灯」或「打开灯光」或「开%s灯」。房间：%s。操作步骤：1) 在 App 或语音助手中说出「开灯/关灯」或带房间名「开%s灯」；2) 系统根据房间名「%s」解析到设备 %s；3) 调用设备控制接口 %s 向 %s 发送 { \"action\": \"on\" } 或 { \"action\": \"off\" }。",
                room, room, room, room, room, deviceId, CONTROL_API, deviceId
        );
    }

    /**
     * 根据设备生成知识库 metadata（与表信息、RAG 检索一致）。
     */
    public static Map<String, Object> buildMetadata(SmartHomeDevice d) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("category", "智能家居");
        meta.put("scene", d.getRoom());
        meta.put("room", d.getRoom());
        meta.put("roomId", d.getRoomId() != null ? d.getRoomId() : "");
        meta.put("deviceId", d.getDeviceId());
        meta.put("deviceName", d.getDeviceName() != null ? d.getDeviceName() : d.getDeviceId());
        meta.put("source", "device");
        meta.put("description", "如何找到设备：确保手机与设备在同一 WiFi，在「设备列表」或「添加设备」里会自动发现局域网内的灯光、插座等，按提示绑定即可。如何控制灯光：绑定后在 App 里可开关、调亮度；语音控制时说「开灯」「关灯」「打开" + d.getRoom() + "灯」「调亮一点」等，语音助手会识别并控制对应灯光。");
        meta.put("操作步骤", List.of(
                "用户说「开灯」或「关灯」（可带房间名，如「关" + d.getRoom() + "灯」）",
                "NLU/大模型解析出：意图=控制灯光，房间=" + d.getRoom() + "，动作=开/关",
                "根据房间名或 roomId 查表得到设备 " + d.getDeviceId(),
                "调用控制接口向 " + d.getDeviceId() + " 发送指令，" + CONTROL_API + " Body: {\"deviceId\":\"" + d.getDeviceId() + "\",\"action\":\"on\" 或 \"off\"}"
        ));
        meta.put("调用方法", CONTROL_API + "，Body 示例：{\"deviceId\":\"" + d.getDeviceId() + "\",\"action\":\"on\"|\"off\"}，deviceId 由房间名映射得到（如 " + d.getRoom() + " -> " + d.getDeviceId() + "）。");
        return meta;
    }
}
