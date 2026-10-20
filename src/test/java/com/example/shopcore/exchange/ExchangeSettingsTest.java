package com.example.shopcore.exchange;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeSettingsTest {

    @Test
    void loadsRatesAndFlags() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("exchange.enabled", true);
        config.set("exchange.bidirectional", false);
        config.set("exchange.rates.POINT.CNY", "0.01");
        config.set("exchange.rates.CNY.POINT", "100");

        ExchangeSettings settings = ExchangeSettings.load(config);
        assertTrue(settings.enabled());
        assertFalse(settings.bidirectional());
        assertEquals(new BigDecimal("0.01"),
                settings.rates().get("POINT").get("CNY"));
        assertEquals(new BigDecimal("100"),
                settings.rates().get("CNY").get("POINT"));
    }

    @Test
    void rejectsNonPositiveRate() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("exchange.rates.POINT.CNY", "0");
        assertThrows(IllegalArgumentException.class, () -> ExchangeSettings.load(config));
    }

    @Test
    void disabledHasNoRates() {
        ExchangeSettings settings = ExchangeSettings.disabled();
        assertFalse(settings.enabled());
        assertTrue(settings.rates().isEmpty());
    }
}
