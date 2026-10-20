package com.example.shopcore.util;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/** 官方商品“每位玩家购买次数/价格成长”的重置周期。 */
public enum ResetPeriod {
    NEVER,
    DAILY,
    WEEKLY,
    MONTHLY;

    public static ResetPeriod parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NEVER;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "NEVER", "NONE", "OFF" -> NEVER;
            case "DAILY", "DAY" -> DAILY;
            case "WEEKLY", "WEEK" -> WEEKLY;
            case "MONTHLY", "MONTH" -> MONTHLY;
            default -> throw new IllegalArgumentException("未知的重置周期：" + raw);
        };
    }

    public ResetPeriod next() {
        return switch (this) {
            case NEVER -> DAILY;
            case DAILY -> WEEKLY;
            case WEEKLY -> MONTHLY;
            case MONTHLY -> NEVER;
        };
    }

    /** 返回当前周期起点的毫秒时间戳；NEVER 返回 0。 */
    public long windowStart(long nowMillis, ZoneId zone) {
        if (this == NEVER) {
            return 0L;
        }
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
        ZonedDateTime start = switch (this) {
            case DAILY -> now.toLocalDate().atStartOfDay(zone);
            case WEEKLY -> now.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(zone);
            case MONTHLY -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone);
            case NEVER -> now;
        };
        return start.toInstant().toEpochMilli();
    }

    public boolean shouldReset(long storedWindowStart, long nowMillis, ZoneId zone) {
        if (this == NEVER) {
            return false;
        }
        return windowStart(nowMillis, zone) > storedWindowStart;
    }
}
