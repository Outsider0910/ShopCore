package com.example.shopcore.exchange;

import java.math.BigDecimal;
import java.util.UUID;

/** 兑换审计记录；businessNo 用于 Wallet/官网对账。 */
public record ExchangeOrder(
        String id,
        String businessNo,
        UUID playerUuid,
        String fromCode,
        String toCode,
        BigDecimal sourceAmount,
        BigDecimal targetAmount,
        ExchangeStatus status,
        String message,
        long createdAt,
        long updatedAt
) {

    public ExchangeOrder {
        sourceAmount = sourceAmount == null ? BigDecimal.ZERO : sourceAmount;
        targetAmount = targetAmount == null ? BigDecimal.ZERO : targetAmount;
    }
}
