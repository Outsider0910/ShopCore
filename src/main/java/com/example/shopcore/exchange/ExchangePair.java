package com.example.shopcore.exchange;

import com.example.shopcore.economy.CurrencyAsset;

import java.math.BigDecimal;

/** 一个可执行的兑换方向：1 单位 from = rate 单位 to。 */
public record ExchangePair(
        CurrencyAsset from,
        CurrencyAsset to,
        BigDecimal rate,
        boolean explicit
) {

    public String key() {
        return from.code() + ">" + to.code();
    }

    public String label() {
        return from.code() + " → " + to.code();
    }

    public String rateText() {
        return rate.stripTrailingZeros().toPlainString();
    }
}
