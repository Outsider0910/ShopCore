package com.example.shopcore.session;

import java.math.BigDecimal;
import java.util.UUID;

/** 等待点击 [确认] 的会话。 */
public record ConfirmationSession(
        UUID token,
        UUID playerUuid,
        ConfirmationType type,
        String targetId,
        String assetCode,
        byte[] itemData,
        String itemName,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        BigDecimal exchangeSourceAmount,
        BigDecimal exchangeTargetAmount,
        long expiresAt
) {

    public ConfirmationSession {
        assetCode = assetCode == null ? "" : assetCode.trim().toUpperCase(java.util.Locale.ROOT);
        itemData = itemData == null ? null : itemData.clone();
    }

    @Override
    public byte[] itemData() {
        return itemData == null ? null : itemData.clone();
    }

    public boolean expired(long now) {
        return now >= expiresAt;
    }
}
