package com.wshg.voice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新浪财经股票实时行情服务。
 * 接口：https://hq.sinajs.cn/list={代码}，需 Referer: https://finance.sina.com.cn
 * 沪深：sh60xxxx/sz00xxxx/sz30xxxx
 */
@Slf4j
@Service
public class SinaStockService {

    private static final String SINA_URL = "https://hq.sinajs.cn/list=";
    private static final Pattern CODE_6 = Pattern.compile("(60\\d{4}|00\\d{4}|30\\d{4})");
    private static final Pattern CODE_FULL = Pattern.compile("(sh|sz)(\\d{6})", Pattern.CASE_INSENSITIVE);

    /** 常用股票/指数 名称 -> 代码 */
    private static final Map<String, String> STOCK_MAP = Map.ofEntries(
            Map.entry("茅台", "sh600519"),
            Map.entry("贵州茅台", "sh600519"),
            Map.entry("中国平安", "sh601318"),
            Map.entry("平安", "sh601318"),
            Map.entry("招商银行", "sh600036"),
            Map.entry("比亚迪", "sz002594"),
            Map.entry("宁德时代", "sz300750"),
            Map.entry("上证指数", "sh000001"),
            Map.entry("上证", "sh000001"),
            Map.entry("深证成指", "sz399001"),
            Map.entry("深证", "sz399001"),
            Map.entry("创业板指", "sz399006"),
            Map.entry("创业板", "sz399006"),
            Map.entry("沪深300", "sh000300"),
            Map.entry("中证500", "sh000905"),
            Map.entry("工商银行", "sh601398"),
            Map.entry("建设银行", "sh601939"),
            Map.entry("农业银行", "sh601288"),
            Map.entry("中国银行", "sh601988"),
            Map.entry("腾讯", "hk00700"),
            Map.entry("阿里巴巴", "gb_baba"),
            Map.entry("阿里", "gb_baba"),
            Map.entry("百度", "gb_bidu"),
            Map.entry("京东", "gb_jd"),
            Map.entry("美团", "hk03690"),
            Map.entry("小米", "hk01810")
    );

    private final RestTemplate restTemplate;

    public SinaStockService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isStockQuery(String userText) {
        if (userText == null || userText.isBlank()) return false;
        String t = userText.trim();
        return t.contains("股票") || t.contains("股价") || t.contains("涨跌") || t.contains("行情")
                || t.contains("多少钱") || t.contains("多少钱") || t.contains("开盘")
                || t.contains("收盘") || t.contains("指数") || CODE_6.matcher(t).find()
                || STOCK_MAP.keySet().stream().anyMatch(t::contains);
    }

    public String fetchStockForQuery(String userText) {
        if (userText == null || userText.isBlank()) return null;
        String code = resolveStockCode(userText);
        if (code == null) return null;
        return fetchByCode(code);
    }

    public String resolveStockCode(String userText) {
        if (userText == null || userText.isBlank()) return null;
        String t = userText.trim();
        Matcher full = CODE_FULL.matcher(t);
        if (full.find()) return full.group(1).toLowerCase() + full.group(2);
        Matcher m = CODE_6.matcher(t);
        if (m.find()) {
            String c = m.group(1);
            if (c.startsWith("60")) return "sh" + c;
            if (c.startsWith("00") || c.startsWith("30")) return "sz" + c;
        }
        for (Map.Entry<String, String> e : STOCK_MAP.entrySet()) {
            if (t.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    public String fetchByCode(String code) {
        if (code == null || code.isBlank()) return null;
        String url = SINA_URL + code;
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", "https://finance.sina.com.cn");
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<String> res = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String body = res.getBody();
            if (body == null || body.isBlank()) return null;
            return parseSinaResponse(code, body);
        } catch (Exception e) {
            log.warn("[股票] 请求失败 code={} url={}", code, url, e);
            return null;
        }
    }

    private String parseSinaResponse(String code, String body) {
        int eq = body.indexOf('=');
        if (eq < 0) return null;
        String content = body.substring(eq + 1).trim();
        if (content.startsWith("\"") && content.endsWith("\"")) {
            content = content.substring(1, content.length() - 1);
        }
        content = content.replace(";", "").trim();
        if (content.isEmpty()) return null;
        String[] parts = content.split(",");
        if (parts.length < 4) return null;
        String name = parts[0];
        String open = parts[1];
        String prevClose = parts[2];
        String current = parts[3];
        String high = parts.length > 4 ? parts[4] : "-";
        String low = parts.length > 5 ? parts[5] : "-";
        String date = parts.length > 30 ? parts[30] : "";
        String time = parts.length > 31 ? parts[31] : "";
        double curr = parseDouble(current);
        double prev = parseDouble(prevClose);
        double change = curr - prev;
        double changePct = prev != 0 ? (change / prev) * 100 : 0;
        String changeStr = change >= 0 ? "+" + String.format("%.2f", change) : String.format("%.2f", change);
        String pctStr = change >= 0 ? "+" + String.format("%.2f", changePct) + "%" : String.format("%.2f", changePct) + "%";
        return String.format("%s(%s)：当前 %.2f，涨跌 %s (%s)，今开 %s，昨收 %s，最高 %s，最低 %s。%s %s",
                name, code, curr, changeStr, pctStr, open, prevClose, high, low, date, time);
    }

    private double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
