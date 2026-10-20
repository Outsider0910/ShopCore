package com.example.shopcore.exchange;

import com.example.shopcore.economy.CurrencyAsset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 把配置里的有向费率和 Wallet 货币表合成为可点击的兑换方向。 */
public final class ExchangePairFactory {

    private ExchangePairFactory() {
    }

    public static List<ExchangePair> build(ExchangeSettings settings, List<CurrencyAsset> assets) {
        if (!settings.enabled()) {
            return List.of();
        }
        Map<String, CurrencyAsset> byCode = new LinkedHashMap<>();
        for (CurrencyAsset asset : assets) {
            byCode.put(asset.code().toUpperCase(Locale.ROOT), asset);
        }

        Map<String, ExchangePair> pairs = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, BigDecimal>> source : settings.rates().entrySet()) {
            CurrencyAsset from = byCode.get(source.getKey());
            if (from == null || !from.enabled()) {
                continue;
            }
            for (Map.Entry<String, BigDecimal> target : source.getValue().entrySet()) {
                CurrencyAsset to = byCode.get(target.getKey());
                if (to == null || !to.enabled()) {
                    continue;
                }
                ExchangePair pair = new ExchangePair(from, to, target.getValue(), true);
                pairs.put(pair.key(), pair);
            }
        }

        if (settings.bidirectional()) {
            List<ExchangePair> explicit = new ArrayList<>(pairs.values());
            for (ExchangePair pair : explicit) {
                String reverseKey = pair.to().code() + ">" + pair.from().code();
                if (pairs.containsKey(reverseKey)) {
                    continue;
                }
                BigDecimal inverse = BigDecimal.ONE.divide(pair.rate(), 16, RoundingMode.HALF_UP);
                ExchangePair reverse = new ExchangePair(pair.to(), pair.from(), inverse, false);
                pairs.put(reverse.key(), reverse);
            }
        }

        List<ExchangePair> result = new ArrayList<>(pairs.values());
        result.sort(Comparator.comparing((ExchangePair p) -> p.from().code())
                .thenComparing(p -> p.to().code()));
        return List.copyOf(result);
    }
}
