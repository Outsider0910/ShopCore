package com.example.shopcore.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceCalculatorTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void noGrowth() {
        PriceCalculator.Quote quote = PriceCalculator.quote(
                d("100"), BigDecimal.ZERO, BigDecimal.ZERO, 0, 3, 2);
        assertEquals(new BigDecimal("100.00"), quote.firstUnitPrice());
        assertEquals(new BigDecimal("300.00"), quote.totalPrice());
    }

    @Test
    void growthIsPerUnit() {
        PriceCalculator.Quote quote = PriceCalculator.quote(
                d("100"), d("10"), BigDecimal.ZERO, 0, 3, 2);
        assertEquals(new BigDecimal("100.00"), quote.firstUnitPrice());
        assertEquals(new BigDecimal("330.00"), quote.totalPrice());
    }

    @Test
    void growthUsesPurchasedCount() {
        PriceCalculator.Quote quote = PriceCalculator.quote(
                d("100"), d("10"), BigDecimal.ZERO, 2, 2, 2);
        assertEquals(new BigDecimal("120.00"), quote.firstUnitPrice());
        assertEquals(new BigDecimal("250.00"), quote.totalPrice());
    }

    @Test
    void capAppliesPerUnit() {
        PriceCalculator.Quote quote = PriceCalculator.quote(
                d("100"), d("10"), d("115"), 0, 3, 2);
        assertEquals(new BigDecimal("325.00"), quote.totalPrice());
    }

    @Test
    void roundsAtCurrencyScale() {
        PriceCalculator.Quote quote = PriceCalculator.quote(
                d("3"), d("10"), BigDecimal.ZERO, 0, 2, 0);
        assertEquals(new BigDecimal("3"), quote.firstUnitPrice());
        assertEquals(new BigDecimal("6"), quote.totalPrice());
    }
}
