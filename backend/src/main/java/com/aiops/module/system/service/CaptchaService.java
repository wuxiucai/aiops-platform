package com.aiops.module.system.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 验证码服务：4位数字字母，内存 ConcurrentHashMap + 5分钟过期（毕设规模，不引 Redis）。
 * 图片用内嵌 SVG 文本直接渲染，无需额外图形库。
 */
@Slf4j
@Service
public class CaptchaService {

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long EXPIRE_MS = 5 * 60_000L;

    /** captchaKey -> [code, createTime] */
    private final Map<String, Object[]> store = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** 生成验证码，返回 {key, svg} */
    public Map<String, String> generate() {
        StringBuilder code = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            code.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        String key = UUID.randomUUID().toString().replace("-", "");
        store.put(key, new Object[]{code.toString(), System.currentTimeMillis()});
        // 顺带清理过期项
        store.entrySet().removeIf(e -> System.currentTimeMillis() - (long) e.getValue()[1] > EXPIRE_MS);
        Map<String, String> r = new java.util.HashMap<>();
        r.put("key", key);
        r.put("svg", renderSvg(code.toString()));
        return r;
    }

    /** 校验并消费验证码（一次性） */
    public boolean verify(String key, String code) {
        if (key == null || code == null) {
            return false;
        }
        Object[] v = store.remove(key);
        if (v == null) {
            return false;
        }
        return ((String) v[0]).equalsIgnoreCase(code.trim());
    }

    /** 简易 SVG 文本验证码（浅色干扰线 + 旋转字符） */
    private String renderSvg(String code) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns='http://www.w3.org/2000/svg' width='120' height='40'>");
        sb.append("<rect width='120' height='40' fill='#f0f2f5'/>");
        for (int i = 0; i < 3; i++) {
            int x1 = random.nextInt(100), y1 = random.nextInt(35);
            int x2 = x1 + 20 + random.nextInt(30), y2 = random.nextInt(35);
            sb.append("<line x1='").append(x1).append("' y1='").append(y1)
                    .append("' x2='").append(x2).append("' y2='").append(y2)
                    .append("' stroke='#bfbfbf' stroke-width='1'/>");
        }
        for (int i = 0; i < code.length(); i++) {
            int x = 15 + i * 25 + random.nextInt(6);
            int y = 26 + random.nextInt(6);
            int rot = random.nextInt(30) - 15;
            sb.append("<text x='").append(x).append("' y='").append(y)
                    .append("' font-size='22' font-family='monospace' font-weight='bold' fill='#333' ")
                    .append("transform='rotate(").append(rot).append(' ').append(x).append(' ').append(y)
                    .append(")'>").append(code.charAt(i)).append("</text>");
        }
        sb.append("</svg>");
        return Base64.getEncoder().encodeToString(sb.toString().getBytes());
    }
}
