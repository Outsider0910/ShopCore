package com.example.shopcore.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResetPeriodTest {

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final long NOON = Instant.parse("2026-10-20T12:00:00Z").toEpochMilli();

    @Test
    void neverDoesNotReset() {
        assertEquals(0L, ResetPeriod.NEVER.windowStart(NOON, UTC));
        assertFalse(ResetPeriod.NEVER.shouldReset(0L, NOON, UTC));
    }

    @Test
    void dailyStartsAtMidnight() {
        long start = ResetPeriod.DAILY.windowStart(NOON, UTC);
        assertEquals(Instant.parse("2026-10-20T00:00:00Z").toEpochMilli(), start);
        assertTrue(ResetPeriod.DAILY.shouldReset(start - 1, NOON, UTC));
        assertFalse(ResetPeriod.DAILY.shouldReset(start, NOON, UTC));
    }

    @Test
    void weeklyStartsOnMonday() {
        long start = ResetPeriod.WEEKLY.windowStart(NOON, UTC);
        ZonedDateTime date = Instant.ofEpochMilli(start).atZone(UTC);
        assertEquals(java.time.DayOfWeek.MONDAY, date.getDayOfWeek());
        assertEquals(0, date.getHour());
        assertEquals(0, date.getMinute());
    }

    @Test
    void monthlyStartsOnFirstDay() {
        long start = ResetPeriod.MONTHLY.windowStart(NOON, UTC);
        ZonedDateTime date = Instant.ofEpochMilli(start).atZone(UTC);
        assertEquals(1, date.getDayOfMonth());
        assertEquals(0, date.getHour());
    }

    @Test
    void cycles() {
        assertEquals(ResetPeriod.DAILY, ResetPeriod.NEVER.next());
        assertEquals(ResetPeriod.WEEKLY, ResetPeriod.DAILY.next());
        assertEquals(ResetPeriod.MONTHLY, ResetPeriod.WEEKLY.next());
        assertEquals(ResetPeriod.NEVER, ResetPeriod.MONTHLY.next());
    }
}
