package com.example.shopcore.service;

import com.example.shopcore.util.Amounts;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 官方商品价格规则。
 *
 * <p>涨价按“每位玩家已购买件数”计算，且一次购买多件时逐件递增：
 * 第 n 件（从 0 开始）的价格 = basePrice × (1 + growthPercent% × (purchased + n))，
 * 再按货币精度四舍五入；maxPrice > 0 时单件价格不超过该值。</p>
 */
public final class PriceCalculator {

    private PriceCalculator() {
    }

    public static Quote quote(BigDecimal basePrice,
                              BigDecimal growthPercent,
                              BigDecimal maxPrice,
                              int purchased,
                              int quantity,
                              int scale) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于 0");
        }
        BigDecimal growth = growthPercent == null ? BigDecimal.ZERO : growthPercent;
        if (growth.signum() < 0) {
            growth = BigDecimal.ZERO;
        }
        BigDecimal growthFactorPerUnit = growth.divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
        BigDecimal cap = maxPrice == null ? BigDecimal.ZERO : maxPrice;

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal firstUnit = null;
        for (int i = 0; i < quantity; i++) {
            long effectiveIndex = (long) purchased + i;
            BigDecimal factor = BigDecimal.ONE.add(growthFactorPerUnit.multiply(BigDecimal.valueOf(effectiveIndex)));
            BigDecimal unit = Amounts.scale(basePrice.multiply(factor), scale);
            if (cap.signum() > 0 && unit.compareTo(cap) > 0) {
                unit = Amounts.scale(cap, scale);
            }
            if (firstUnit == null) {
                firstUnit = unit;
            }
            total = total.add(unit);
        }
        return new Quote(firstUnit, Amounts.scale(total, scale), purchased, quantity);
    }

    public record Quote(BigDecimal firstUnitPrice, BigDecimal totalPrice, int purchasedBefore, int quantity) {
    }
}
