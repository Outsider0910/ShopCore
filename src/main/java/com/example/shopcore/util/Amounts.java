package com.example.shopcore.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 金额解析与格式化。商店内部统一用 BigDecimal，落库为纯文本，避免二进制浮点误差。 */
public final class Amounts {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Amounts() {
    }

    public static BigDecimal parse(String input, int scale) {
        if (input == null) {
            throw new IllegalArgumentException("金额不能为空");
        }
        String text = input.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("金额不能为空");
        }
        if (text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
            throw new IllegalArgumentException("金额格式不正确：" + input);
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("金额格式不正确：" + input);
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("金额不能为负数");
        }
        if (value.stripTrailingZeros().scale() > scale) {
            throw new IllegalArgumentException("金额最多允许 " + scale + " 位小数");
        }
        return value.setScale(scale, ROUNDING);
    }

    public static BigDecimal scale(BigDecimal value, int scale) {
        return value.setScale(scale, ROUNDING);
    }

    public static String format(BigDecimal value, int scale) {
        return value.setScale(scale, ROUNDING).toPlainString();
    }

    public static BigDecimal parsePositive(String input, int scale) {
        BigDecimal value = parse(input, scale);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("金额必须大于 0");
        }
        return value;
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
