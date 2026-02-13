package com.wshg.voice.repository;

import com.wshg.voice.entity.SmartHomeDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SmartHomeDeviceRepository extends JpaRepository<SmartHomeDevice, Long> {

    List<SmartHomeDevice> findByRoomAndEnabledTrue(String room);

    List<SmartHomeDevice> findByEnabledTrue();

    Optional<SmartHomeDevice> findByDeviceId(String deviceId);
}
