package com.example.shopcore.exchange;

import com.example.shopcore.economy.CurrencyAsset;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExchangeRateCalculatorTest {

    private static final CurrencyAsset POINT =
            new CurrencyAsset("POINT", "积分", "积分", 0, true);
    private static final CurrencyAsset CNY =
            new CurrencyAsset("CNY", "人民币", "￥", 2, true);

    @Test
    void pointToCny() {
        ExchangePair pair = new ExchangePair(POINT, CNY, new BigDecimal("0.01"), true);
        assertEquals(new BigDecimal("1.00"),
                ExchangeRateCalculator.targetAmount(pair, new BigDecimal("100")));
        assertEquals(new BigDecimal("0.01"),
                ExchangeRateCalculator.targetAmount(pair, new BigDecimal("1")));
    }

    @Test
    void roundsDownAtTargetScale() {
        ExchangePair pair = new ExchangePair(CNY, POINT, new BigDecimal("100"), true);
        assertEquals(new BigDecimal("199"),
                ExchangeRateCalculator.targetAmount(pair, new BigDecimal("1.99")));

        CurrencyAsset cny2 = new CurrencyAsset("CNY", "人民币", "￥", 2, true);
        CurrencyAsset cny3 = new CurrencyAsset("TWO", "两位", "T", 2, true);
        ExchangePair tiny = new ExchangePair(cny2, cny3, new BigDecimal("0.01"), true);
        assertEquals(new BigDecimal("0.01"),
                ExchangeRateCalculator.targetAmount(tiny, new BigDecimal("1.99")));
    }

    @Test
    void rejectsNonPositiveSource() {
        ExchangePair pair = new ExchangePair(POINT, CNY, new BigDecimal("0.01"), true);
        assertThrows(IllegalArgumentException.class,
                () -> ExchangeRateCalculator.targetAmount(pair, BigDecimal.ZERO));
    }
}
