package com.example.shopcore.model;

import java.util.UUID;

/** 待领取物品。寄存而不是直接掉落，避免背包满时吞物品。 */
public record MailboxEntry(
        String id,
        UUID playerUuid,
        byte[] itemData,
        int quantity,
        long createdAt
) {

    public MailboxEntry {
        itemData = itemData == null ? new byte[0] : itemData.clone();
    }

    @Override
    public byte[] itemData() {
        return itemData.clone();
    }
}
