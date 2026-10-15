package com.aiops.common.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** TimeZones：平台默认时区 Asia/Shanghai（UTC+8，无夏令时） */
class TimeZonesTest {

    @Test
    void 本地时间转UTC_用于ES范围查询() {
        assertEquals(LocalDateTime.of(2026, 10, 15, 2, 0),
                TimeZones.toUtc(LocalDateTime.of(2026, 10, 15, 10, 0)));
    }

    @Test
    void 跨日换算() {
        assertEquals(LocalDateTime.of(2026, 10, 14, 23, 30),
                TimeZones.toUtc(LocalDateTime.of(2026, 10, 15, 7, 30)));
    }

    @Test
    void ES时间_Z结尾() {
        assertEquals("2026-09-30 11:01:46", TimeZones.esTimeToLocalString("2026-09-30T03:01:46.996Z"));
    }

    @Test
    void ES时间_带偏移() {
        assertEquals("2026-09-30 11:01:46", TimeZones.esTimeToLocalString("2026-09-30T11:01:46+08:00"));
    }

    @Test
    void ES时间_无时区标记按UTC() {
        assertEquals("2026-09-30 11:01:46", TimeZones.esTimeToLocalString("2026-09-30T03:01:46"));
    }

    @Test
    void 无法解析_原样返回或null() {
        assertEquals("not-a-time", TimeZones.esTimeToLocalString("not-a-time"));
        assertNull(TimeZones.parseEsTime(null));
        assertNull(TimeZones.parseEsTime(""));
    }
}
