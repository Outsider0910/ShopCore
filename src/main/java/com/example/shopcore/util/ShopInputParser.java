package com.example.shopcore.util;

import java.math.BigDecimal;

/** 解析聊天输入。输入格式保持简单、可预测，所有数值都在这里先做本地校验。 */
public final class ShopInputParser {

    private ShopInputParser() {
    }

    /** 解析“数量 单价”，支持空格、逗号、x/× 分隔。 */
    public static ListingInput parseListing(String text, int scale, BigDecimal minPrice, BigDecimal maxPrice, int maxQuantity) {
        if (text == null) {
            throw new IllegalArgumentException("请输入：数量 单价");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("请输入：数量 单价");
        }
        String[] parts = trimmed.split("[\\s,，xX×]+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("格式不正确，请输入：数量 单价（例如：10 100）");
        }
        int quantity = parseQuantity(parts[0], maxQuantity);
        BigDecimal price = Amounts.parsePositive(parts[1], scale);
        if (price.compareTo(minPrice) < 0) {
            throw new IllegalArgumentException("单价不能低于 " + Amounts.format(minPrice, scale));
        }
        if (maxPrice.signum() > 0 && price.compareTo(maxPrice) > 0) {
            throw new IllegalArgumentException("单价不能高于 " + Amounts.format(maxPrice, scale));
        }
        return new ListingInput(quantity, price);
    }

    /** 解析购买数量，允许尾部“个/件”。 */
    public static int parseQuantity(String text, int maxQuantity) {
        if (text == null) {
            throw new IllegalArgumentException("请输入数量");
        }
        String trimmed = text.trim();
        if (!trimmed.matches("\\d+(?:\\s*(?:个|件))?")) {
            throw new IllegalArgumentException("数量必须是正整数");
        }
        trimmed = trimmed.replaceAll("\\s*(?:个|件)$", "");
        long value;
        try {
            value = Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("数量过大");
        }
        if (value <= 0) {
            throw new IllegalArgumentException("数量必须大于 0");
        }
        if (value > maxQuantity) {
            throw new IllegalArgumentException("单次数量不能超过 " + maxQuantity);
        }
        return (int) value;
    }

    /** 解析管理员输入的库存：-1 表示无限。 */
    public static int parseStock(String text) {
        if (text == null) {
            throw new IllegalArgumentException("请输入库存，-1 表示无限");
        }
        String trimmed = text.trim();
        if (!trimmed.matches("-?\\d+")) {
            throw new IllegalArgumentException("库存必须是整数，-1 表示无限");
        }
        long value;
        try {
            value = Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("库存过大");
        }
        if (value < -1 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("库存必须是 -1 或非负整数");
        }
        return (int) value;
    }

    /** 解析管理员输入的限购件数：0 表示不限。 */
    public static int parseLimit(String text) {
        if (text == null) {
            throw new IllegalArgumentException("请输入限购件数，0 表示不限");
        }
        int value = parseNonNegativeInt(text, "限购件数");
        return value;
    }

    public static int parseNonNegativeInt(String text, String label) {
        if (text == null) {
            throw new IllegalArgumentException("请输入" + label);
        }
        String trimmed = text.trim();
        if (!trimmed.matches("\\d+")) {
            throw new IllegalArgumentException(label + "必须是非负整数");
        }
        long value;
        try {
            value = Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + "过大");
        }
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + "过大");
        }
        return (int) value;
    }

    public record ListingInput(int quantity, BigDecimal unitPrice) {
    }
}
