package com.example.shopcore.exchange;

import com.example.shopcore.economy.CurrencyAsset;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangePairFactoryTest {

    private static final CurrencyAsset POINT =
            new CurrencyAsset("POINT", "积分", "积分", 0, true);
    private static final CurrencyAsset CNY =
            new CurrencyAsset("CNY", "人民币", "￥", 2, true);

    @Test
    void createsExplicitAndAutomaticReversePair() {
        ExchangeSettings settings = new ExchangeSettings(
                true, true, Map.of("POINT", Map.of("CNY", new BigDecimal("0.01"))));
        List<ExchangePair> pairs = ExchangePairFactory.build(settings, List.of(POINT, CNY));

        assertEquals(2, pairs.size());
        ExchangePair forward = find(pairs, "POINT>CNY");
        ExchangePair reverse = find(pairs, "CNY>POINT");
        assertEquals(new BigDecimal("0.01"), forward.rate());
        assertTrue(forward.explicit());
        assertEquals(0, new BigDecimal("100").compareTo(reverse.rate().setScale(0, java.math.RoundingMode.HALF_UP)));
        assertFalse(reverse.explicit());
    }

    @Test
    void explicitReverseWins() {
        ExchangeSettings settings = new ExchangeSettings(true, true, Map.of(
                "POINT", Map.of("CNY", new BigDecimal("0.01")),
                "CNY", Map.of("POINT", new BigDecimal("120"))));
        List<ExchangePair> pairs = ExchangePairFactory.build(settings, List.of(POINT, CNY));
        ExchangePair reverse = find(pairs, "CNY>POINT");
        assertEquals(new BigDecimal("120"), reverse.rate());
        assertTrue(reverse.explicit());
    }

    @Test
    void bidirectionalFalseOnlyExplicit() {
        ExchangeSettings settings = new ExchangeSettings(
                true, false, Map.of("POINT", Map.of("CNY", new BigDecimal("0.01"))));
        List<ExchangePair> pairs = ExchangePairFactory.build(settings, List.of(POINT, CNY));
        assertEquals(1, pairs.size());
        assertEquals("POINT>CNY", pairs.get(0).key());
    }

    @Test
    void skipsDisabledOrMissingAssets() {
        CurrencyAsset disabledCny = new CurrencyAsset("CNY", "人民币", "￥", 2, false);
        ExchangeSettings settings = new ExchangeSettings(
                true, true, Map.of("POINT", Map.of("CNY", new BigDecimal("0.01"))));
        assertTrue(ExchangePairFactory.build(settings, List.of(POINT, disabledCny)).isEmpty());
        assertTrue(ExchangePairFactory.build(settings, List.of(POINT)).isEmpty());
    }

    private ExchangePair find(List<ExchangePair> pairs, String key) {
        return pairs.stream().filter(p -> p.key().equals(key)).findFirst().orElseThrow();
    }
}
