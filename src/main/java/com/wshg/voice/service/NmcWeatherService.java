package com.wshg.voice.service;

import com.wshg.voice.config.VoiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中央气象台（nmc.cn）天气服务。
 * 使用官方免费 JSON 接口：http://www.nmc.cn/f/rest/real/{城市code}
 */
@Slf4j
@Service
public class NmcWeatherService {

    private static final String NMC_BASE = "http://www.nmc.cn/f/rest";
    private static final String PROVINCE_LIST = NMC_BASE + "/province";

    /** 常用城市 code（中央气象台 nmc.cn 接口格式） */
    private static final Map<String, String> COMMON_CITY_CODES = Map.ofEntries(
            Map.entry("成都", "yGYHR"),
            Map.entry("北京", "Wqsps"),
            Map.entry("绵阳", "GcFwG"),
            Map.entry("乐山", "YuAXe"),
            Map.entry("南充", "vihpz"),
            Map.entry("宜宾", "wfNOD"),
            Map.entry("泸州", "nLWnl"),
            Map.entry("德阳", "QFQLG"),
            Map.entry("内江", "wNtyv"),
            Map.entry("达州", "uEFLb"),
            Map.entry("广元", "OwVIj"),
            Map.entry("遂宁", "pcHfo"),
            Map.entry("眉山", "zkdYW"),
            Map.entry("资阳", "AVVPj"),
            Map.entry("自贡", "MIIAp"),
            Map.entry("攀枝花", "jrDgO"),
            Map.entry("广安", "zJhEo"),
            Map.entry("巴中", "UxUlc"),
            Map.entry("雅安", "MfnxC"),
            Map.entry("西昌", "Whzpi"),
            Map.entry("都江堰", "GrSbA"),
            Map.entry("崇州", "VPihc"),
            Map.entry("彭州", "KKktC"),
            Map.entry("邛崃", "EVoup"),
            Map.entry("简阳", "IhZxf"),
            Map.entry("金堂", "udKik"),
            Map.entry("双流", "grFhZ"),
            Map.entry("温江", "xrRlZ"),
            Map.entry("新都", "XxQIS"),
            Map.entry("郫都", "NUxad"),
            Map.entry("龙泉驿", "mqsMW")
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final VoiceProperties props;
    private final Map<String, String> cityCodeCache = new ConcurrentHashMap<>();

    public NmcWeatherService(RestTemplate restTemplate, ObjectMapper objectMapper, VoiceProperties props) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.props = props;
        cityCodeCache.putAll(COMMON_CITY_CODES);
    }

    public String fetchWeatherForQuery(String userText) {
        if (userText == null || userText.isBlank()) return null;
        String city = resolveCityFromQuery(userText);
        if (city == null) {
            city = props.getWeatherDefaultCity();
            if (city == null || city.isBlank()) city = "成都";
        }
        return fetchWeatherByCityName(city);
    }

    public boolean isWeatherQuery(String userText) {
        if (userText == null || userText.isBlank()) return false;
        String t = userText.trim();
        return t.contains("天气") || t.contains("气温") || t.contains("温度")
                || t.contains("下雨") || t.contains("晴") || t.contains("阴")
                || t.contains("冷") || t.contains("热") || t.contains("暖和")
                || t.contains("多少度") || t.contains("几度");
    }

    public String resolveCityFromQuery(String userText) {
        if (userText == null || userText.isBlank()) return null;
        String t = userText.trim();
        List<String> sorted = new ArrayList<>(cityCodeCache.keySet());
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String city : sorted) {
            if (t.contains(city)) return city;
        }
        return null;
    }

    public String fetchWeatherByCityName(String cityName) {
        if (cityName == null || cityName.isBlank()) return null;
        String code = cityCodeCache.get(cityName);
        if (code == null) {
            code = lookupCityCodeFromApi(cityName);
            if (code != null) cityCodeCache.put(cityName, code);
        }
        if (code == null) {
            log.warn("[天气] 未找到城市 code: {}", cityName);
            return null;
        }
        return fetchWeatherByCode(cityName, code);
    }

    /**
     * 按城市名获取天气并返回结构化数据，供 API 返回 JSON 使用。
     */
    public Map<String, Object> fetchWeatherAsMap(String cityName) {
        if (cityName == null || cityName.isBlank()) return null;
        String code = cityCodeCache.get(cityName);
        if (code == null) {
            code = lookupCityCodeFromApi(cityName);
            if (code != null) cityCodeCache.put(cityName, code);
        }
        if (code == null) {
            log.warn("[天气] 未找到城市 code: {}", cityName);
            return null;
        }
        return fetchWeatherMapByCode(cityName, code);
    }

    private String fetchWeatherByCode(String cityName, String cityCode) {
        Map<String, Object> m = fetchWeatherMapByCode(cityName, cityCode);
        if (m == null) return null;
        String province = (String) m.get("province");
        String city = (String) m.get("city");
        double temp = (Double) m.get("temperature");
        double feelst = (Double) m.get("feelst");
        double humidity = (Double) m.get("humidity");
        String windDirect = (String) m.get("windDirect");
        String windPower = (String) m.get("windPower");
        double windSpeed = (Double) m.get("windSpeed");
        String publishTime = (String) m.get("publishTime");
        return String.format("%s%s：实时气温 %.1f℃，体感 %.1f℃，湿度 %.0f%%，%s%s %.1fm/s。发布时间：%s",
                province, city, temp, feelst, humidity, windDirect, windPower, windSpeed, publishTime);
    }

    private Map<String, Object> fetchWeatherMapByCode(String cityName, String cityCode) {
        String url = NMC_BASE + "/real/" + cityCode;
        try {
            String json = restTemplate.getForObject(url, String.class);
            if (json == null || json.isBlank()) return null;
            JsonNode root = objectMapper.readTree(json);
            JsonNode weather = root.path("weather");
            JsonNode wind = root.path("wind");
            JsonNode station = root.path("station");
            String publishTime = root.path("publish_time").asText("");
            if (weather.isMissingNode()) return null;

            double temp = weather.path("temperature").asDouble(0);
            double feelst = weather.path("feelst").asDouble(temp);
            double humidity = weather.path("humidity").asDouble(0);
            String windDirect = wind.path("direct").asText("");
            String windPower = wind.path("power").asText("");
            double windSpeed = wind.path("speed").asDouble(0);
            String province = station.path("province").asText("");
            String city = station.path("city").asText(cityName);
            String windStr = windDirect + windPower + " " + windSpeed + "m/s";

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("city", city);
            map.put("province", province);
            map.put("temperature", temp);
            map.put("feelst", feelst);
            map.put("humidity", humidity);
            map.put("wind", windStr);
            map.put("windDirect", windDirect);
            map.put("windPower", windPower);
            map.put("windSpeed", windSpeed);
            map.put("publishTime", publishTime);
            return map;
        } catch (Exception e) {
            log.warn("[天气] 请求失败 cityCode={} url={}", cityCode, url, e);
            return null;
        }
    }

    private String lookupCityCodeFromApi(String cityName) {
        try {
            String provincesJson = restTemplate.getForObject(PROVINCE_LIST, String.class);
            if (provincesJson == null) return null;
            JsonNode provinces = objectMapper.readTree(provincesJson);
            for (JsonNode p : provinces) {
                String provCode = p.path("code").asText();
                String citiesJson = restTemplate.getForObject(NMC_BASE + "/province/" + provCode, String.class);
                if (citiesJson == null) continue;
                JsonNode cities = objectMapper.readTree(citiesJson);
                for (JsonNode c : cities) {
                    String name = c.path("city").asText("");
                    if (cityName.equals(name)) {
                        return c.path("code").asText();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[天气] 从 API 查找城市失败: {}", cityName, e);
        }
        return null;
    }
}
