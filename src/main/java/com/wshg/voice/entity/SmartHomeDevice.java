package com.wshg.voice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 智能家居设备表：存储连接信息、控制信息与基本信息。
 * 用户说「开灯/关灯」时，大模型解析命令后查此表，向设备发送控制指令。
 */
@Entity
@Table(name = "smart_home_device")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmartHomeDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 设备唯一标识，用于下发指令 */
    @Column(nullable = false, unique = true, length = 64)
    private String deviceId;

    /** 设备显示名称，如「客厅主灯」 */
    @Column(length = 128)
    private String deviceName;

    /** 房间名，如「客厅」「卧室」，用于语音解析匹配 */
    @Column(nullable = false, length = 64)
    private String room;

    /** 房间标识，如 living_room */
    @Column(length = 64)
    private String roomId;

    /** 设备类型：light / socket / ... */
    @Column(length = 32)
    private String deviceType;

    /** 连接信息：控制接口 base URL，如 http://192.168.1.100:8080 */
    @Column(name = "connection_url", length = 256)
    private String connectionUrl;

    /** 控制方式：GET / POST */
    @Column(length = 16)
    private String controlMethod;

    /** 开指令：GET 时为路径，POST 时为 body 片段，如 {"action":"on"} */
    @Column(name = "control_on", length = 512)
    private String controlOn;

    /** 关指令 */
    @Column(name = "control_off", length = 512)
    private String controlOff;

    /** 当前状态：on / off / unknown */
    @Column(length = 32)
    private String status;

    /** 是否启用 */
    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
