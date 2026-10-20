package com.example.shopcore.model;

import java.math.BigDecimal;
import java.util.UUID;

/** 订单审计记录。businessNo 用于经济系统幂等对账。 */
public record Order(
        String id,
        String businessNo,
        String assetCode,
        UUID buyerUuid,
        UUID sellerUuid,
        String listingId,
        String officialItemId,
        byte[] itemData,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        OrderStatus status,
        long createdAt,
        long updatedAt
) {

    public Order {
        assetCode = assetCode == null ? "" : assetCode.trim().toUpperCase(java.util.Locale.ROOT);
        itemData = itemData == null ? new byte[0] : itemData.clone();
        unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        totalPrice = totalPrice == null ? BigDecimal.ZERO : totalPrice;
    }

    @Override
    public byte[] itemData() {
        return itemData.clone();
    }

    public boolean officialPurchase() {
        return officialItemId != null;
    }
}
