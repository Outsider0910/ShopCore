package com.example.shopcore.session;

import java.util.UUID;

/** 等待玩家下一条聊天输入的会话。 */
public record InputSession(
        UUID playerUuid,
        InputType type,
        String targetId,
        String assetCode,
        byte[] itemData,
        String itemName,
        long expiresAt
) {

    public InputSession {
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
