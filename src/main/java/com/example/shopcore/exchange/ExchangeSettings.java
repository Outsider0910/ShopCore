package com.example.shopcore.exchange;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 兑换配置。
 *
 * <pre>
 * exchange:
 *   enabled: true
 *   bidirectional: true
 *   rates:
 *     POINT:
 *       CNY: "0.01"     # 1 POINT = 0.01 CNY
 * </pre>
 *
 * <p>bidirectional=true 时，未显式配置的反向方向按 1/rate 自动生成；显式反向费率优先。
 * bidirectional=false 时只允许 rates 里写出来的方向。</p>
 */
public record ExchangeSettings(
        boolean enabled,
        boolean bidirectional,
        Map<String, Map<String, BigDecimal>> rates
) {

    public ExchangeSettings {
        Map<String, Map<String, BigDecimal>> copy = new LinkedHashMap<>();
        if (rates != null) {
            for (Map.Entry<String, Map<String, BigDecimal>> entry : rates.entrySet()) {
                Map<String, BigDecimal> targets = new LinkedHashMap<>();
                for (Map.Entry<String, BigDecimal> target : entry.getValue().entrySet()) {
                    targets.put(target.getKey(), target.getValue());
                }
                copy.put(entry.getKey(), Collections.unmodifiableMap(targets));
            }
        }
        rates = Collections.unmodifiableMap(copy);
    }

    public static ExchangeSettings load(FileConfiguration config) {
        boolean enabled = config.getBoolean("exchange.enabled", true);
        boolean bidirectional = config.getBoolean("exchange.bidirectional", true);
        Map<String, Map<String, BigDecimal>> rates = new LinkedHashMap<>();
        ConfigurationSection root = config.getConfigurationSection("exchange.rates");
        if (root != null) {
            for (String sourceRaw : root.getKeys(false)) {
                ConfigurationSection targets = root.getConfigurationSection(sourceRaw);
                if (targets == null) {
                    continue;
                }
                String source = sourceRaw.trim().toUpperCase(Locale.ROOT);
                if (source.isEmpty()) {
                    continue;
                }
                Map<String, BigDecimal> targetRates = new LinkedHashMap<>();
                for (String targetRaw : targets.getKeys(false)) {
                    String target = targetRaw.trim().toUpperCase(Locale.ROOT);
                    if (target.isEmpty() || target.equals(source)) {
                        continue;
                    }
                    String rawRate = targets.getString(targetRaw, "");
                    BigDecimal rate;
                    try {
                        rate = new BigDecimal(rawRate.trim());
                    } catch (RuntimeException ex) {
                        throw new IllegalArgumentException(
                                "exchange.rates." + sourceRaw + "." + targetRaw + " 不是合法数字");
                    }
                    if (rate.signum() <= 0) {
                        throw new IllegalArgumentException(
                                "exchange.rates." + sourceRaw + "." + targetRaw + " 必须大于 0");
                    }
                    targetRates.put(target, rate);
                }
                if (!targetRates.isEmpty()) {
                    rates.put(source, targetRates);
                }
            }
        }
        return new ExchangeSettings(enabled, bidirectional, rates);
    }

    public static ExchangeSettings disabled() {
        return new ExchangeSettings(false, false, Map.of());
    }
}
