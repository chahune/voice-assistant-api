package com.wshg.voice.controller;

import com.wshg.voice.service.NmcWeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 天气查询 API：供前端页面调用，返回 JSON。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WeatherController {

    private final NmcWeatherService nmcWeatherService;

    /**
     * GET /api/weather?city=成都
     * 调用 NmcWeatherService 获取天气，返回 city、temperature、humidity、wind、publishTime 等。
     */
    @GetMapping("/weather")
    public ResponseEntity<?> weather(@RequestParam(value = "city", defaultValue = "成都") String city) {
        log.info("[API] GET /api/weather city={}", city);
        if (city == null || city.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "city 不能为空"));
        }
        Map<String, Object> data = nmcWeatherService.fetchWeatherAsMap(city.trim());
        if (data == null) {
            return ResponseEntity.status(502).body(Map.of("error", "获取天气失败，请检查城市名或稍后重试"));
        }
        return ResponseEntity.ok(data);
    }
}
