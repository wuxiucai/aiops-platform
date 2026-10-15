package com.aiops.common.util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 平台本地时间 ↔ ES 存储时间（UTC）的统一换算。
 * <p>
 * 平台内部（MySQL、接口入参出参）一律用 {@link #ZONE} 下的 LocalDateTime；
 * ES 的 @timestamp 按 UTC 存储。此前三个文件里各自写死 ±8 小时，换部署时区即整体偏移。
 * 时区来自 JVM 系统属性 {@code aiops.zone}（未设置时 Asia/Shanghai），在 application.yml 中由
 * {@code aiops.zone} 配置并在启动时写入。
 */
public final class TimeZones {

    public static final ZoneId ZONE = ZoneId.of(System.getProperty("aiops.zone", "Asia/Shanghai"));

    private static final DateTimeFormatter LOCAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private TimeZones() {
    }

    /** 平台本地时间 → UTC（用于拼 ES range 查询） */
    public static LocalDateTime toUtc(LocalDateTime local) {
        return local.atZone(ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    /** UTC → 平台本地时间 */
    public static LocalDateTime fromUtc(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZONE).toLocalDateTime();
    }

    /**
     * 解析 ES 返回的时间串为平台本地时间。
     * 兼容 2026-09-30T03:01:46.996Z、带偏移的 2026-09-30T11:01:46+08:00、无时区标记（按 UTC）三种形式。
     *
     * @return 解析失败返回 null
     */
    public static LocalDateTime parseEsTime(String esTime) {
        if (esTime == null || esTime.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(esTime).atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (DateTimeParseException ignore) {
            // 无偏移标记：按 UTC 解释
        }
        try {
            return fromUtc(LocalDateTime.parse(esTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** ES 时间串 → 本地时间字符串；解析失败原样返回 */
    public static String esTimeToLocalString(String esTime) {
        LocalDateTime t = parseEsTime(esTime);
        return t == null ? esTime : t.format(LOCAL_FMT);
    }
}
