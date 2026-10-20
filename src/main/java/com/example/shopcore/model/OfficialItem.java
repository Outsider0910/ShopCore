package com.example.shopcore.model;

import com.example.shopcore.util.ResetPeriod;

import java.math.BigDecimal;
import java.util.Arrays;

/** 官方商店的一件商品。itemData 是数量为 1 的完整 ItemStack 序列化字节。 */
public record OfficialItem(
        String id,
        String assetCode,
        byte[] itemData,
        BigDecimal basePrice,
        int stock,
        int perPlayerLimit,
        BigDecimal growthPercent,
        BigDecimal maxPrice,
        ResetPeriod resetPeriod,
        boolean enabled,
        long createdAt,
        long updatedAt
) {

    public OfficialItem {
        assetCode = assetCode == null ? "" : assetCode.trim().toUpperCase(java.util.Locale.ROOT);
        itemData = itemData == null ? new byte[0] : itemData.clone();
        basePrice = basePrice == null ? BigDecimal.ZERO : basePrice;
        growthPercent = growthPercent == null ? BigDecimal.ZERO : growthPercent;
        maxPrice = maxPrice == null ? BigDecimal.ZERO : maxPrice;
        resetPeriod = resetPeriod == null ? ResetPeriod.NEVER : resetPeriod;
    }

    @Override
    public byte[] itemData() {
        return itemData.clone();
    }

    public boolean unlimitedStock() {
        return stock < 0;
    }

    public boolean unlimitedPerPlayer() {
        return perPlayerLimit <= 0;
    }

    public boolean hasPriceGrowth() {
        return growthPercent.signum() > 0;
    }

    public boolean hasPriceCap() {
        return maxPrice.signum() > 0;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof OfficialItem other
                && id.equals(other.id)
                && assetCode.equals(other.assetCode)
                && Arrays.equals(itemData, other.itemData)
                && basePrice.compareTo(other.basePrice) == 0
                && stock == other.stock
                && perPlayerLimit == other.perPlayerLimit
                && growthPercent.compareTo(other.growthPercent) == 0
                && maxPrice.compareTo(other.maxPrice) == 0
                && resetPeriod == other.resetPeriod
                && enabled == other.enabled;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
