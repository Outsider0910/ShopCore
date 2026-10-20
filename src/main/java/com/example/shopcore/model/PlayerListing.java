package com.example.shopcore.model;

import java.math.BigDecimal;
import java.util.UUID;

/** 玩家商店的一笔寄售。itemData 是数量为 1 的完整 ItemStack 序列化字节。 */
public record PlayerListing(
        String id,
        String assetCode,
        UUID sellerUuid,
        String sellerName,
        byte[] itemData,
        BigDecimal unitPrice,
        int quantity,
        long createdAt,
        long updatedAt
) {

    public PlayerListing {
        assetCode = assetCode == null ? "" : assetCode.trim().toUpperCase(java.util.Locale.ROOT);
        itemData = itemData == null ? new byte[0] : itemData.clone();
        unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
    }

    @Override
    public byte[] itemData() {
        return itemData.clone();
    }

    public boolean active() {
        return quantity > 0;
    }
}
