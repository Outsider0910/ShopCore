package com.example.shopcore.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 兑换金额计算。目标金额向下取整，避免按显示精度“换出”比配置更多的目标货币。 */
public final class ExchangeRateCalculator {

    private ExchangeRateCalculator() {
    }

    public static BigDecimal targetAmount(ExchangePair pair, BigDecimal sourceAmount) {
        if (sourceAmount == null || sourceAmount.signum() <= 0) {
            throw new IllegalArgumentException("源货币数量必须大于 0");
        }
        BigDecimal raw = sourceAmount.multiply(pair.rate());
        return raw.setScale(pair.to().scale(), RoundingMode.DOWN);
    }
}
