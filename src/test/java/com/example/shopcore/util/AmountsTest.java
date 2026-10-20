package com.example.shopcore.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AmountsTest {

    @Test
    void parsesAndScales() {
        assertEquals(new BigDecimal("100.00"), Amounts.parse("100", 2));
        assertEquals(new BigDecimal("1.50"), Amounts.parse("1.5", 2));
        assertEquals(new BigDecimal("0.01"), Amounts.parse("0.01", 2));
        assertEquals("100.00", Amounts.format(new BigDecimal("100"), 2));
    }

    @Test
    void rejectsTooManyDecimals() {
        assertThrows(IllegalArgumentException.class, () -> Amounts.parse("1.234", 2));
    }

    @Test
    void rejectsNegativeAndScientific() {
        assertThrows(IllegalArgumentException.class, () -> Amounts.parse("-1", 2));
        assertThrows(IllegalArgumentException.class, () -> Amounts.parse("1e3", 2));
    }

    @Test
    void positiveCheck() {
        assertThrows(IllegalArgumentException.class, () -> Amounts.parsePositive("0", 2));
        assertEquals(new BigDecimal("5.00"), Amounts.parsePositive("5", 2));
    }
}
