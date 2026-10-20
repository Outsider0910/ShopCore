package com.example.shopcore.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 聊天输入与点击确认会话。所有方法线程安全；超时项在读取/定时清理时淘汰。 */
public final class SessionManager {

    private final Map<UUID, InputSession> inputs = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmationSession> confirmations = new ConcurrentHashMap<>();

    public void startInput(InputSession session) {
        inputs.put(session.playerUuid(), session);
    }

    public Optional<InputSession> peekInput(UUID player, long now) {
        InputSession session = inputs.get(player);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expired(now)) {
            inputs.remove(player, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public Optional<InputSession> takeInput(UUID player, long now) {
        InputSession session = inputs.get(player);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expired(now)) {
            inputs.remove(player, session);
            return Optional.empty();
        }
        return inputs.remove(player, session) ? Optional.of(session) : Optional.empty();
    }

    public void putInputBack(InputSession session, long now) {
        if (!session.expired(now)) {
            inputs.putIfAbsent(session.playerUuid(), session);
        }
    }

    public UUID createConfirmation(ConfirmationSession sessionWithoutToken) {
        UUID token = UUID.randomUUID();
        ConfirmationSession stored = new ConfirmationSession(
                token,
                sessionWithoutToken.playerUuid(),
                sessionWithoutToken.type(),
                sessionWithoutToken.targetId(),
                sessionWithoutToken.assetCode(),
                sessionWithoutToken.itemData(),
                sessionWithoutToken.itemName(),
                sessionWithoutToken.quantity(),
                sessionWithoutToken.unitPrice(),
                sessionWithoutToken.totalPrice(),
                sessionWithoutToken.exchangeSourceAmount(),
                sessionWithoutToken.exchangeTargetAmount(),
                sessionWithoutToken.expiresAt()
        );
        confirmations.put(token, stored);
        return token;
    }

    public Optional<ConfirmationSession> takeConfirmation(UUID token, UUID player, long now) {
        ConfirmationSession session = confirmations.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expired(now)) {
            confirmations.remove(token, session);
            return Optional.empty();
        }
        if (!session.playerUuid().equals(player)) {
            return Optional.empty();
        }
        return confirmations.remove(token, session) ? Optional.of(session) : Optional.empty();
    }

    public void clearPlayer(UUID player) {
        inputs.remove(player);
        confirmations.entrySet().removeIf(entry -> entry.getValue().playerUuid().equals(player));
    }

    public void cleanupExpired(long now) {
        inputs.entrySet().removeIf(entry -> entry.getValue().expired(now));
        confirmations.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }

    public List<ConfirmationSession> confirmationsOf(UUID player) {
        List<ConfirmationSession> result = new ArrayList<>();
        for (ConfirmationSession session : confirmations.values()) {
            if (session.playerUuid().equals(player)) {
                result.add(session);
            }
        }
        return result;
    }
}
