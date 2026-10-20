package com.example.shopcore.exchange;

import com.example.shopcore.ShopCorePlugin;
import com.example.shopcore.config.Messages;
import com.example.shopcore.config.PluginSettings;
import com.example.shopcore.economy.CurrencyAsset;
import com.example.shopcore.economy.WalletEconomy;
import com.example.shopcore.economy.WalletPaymentResult;
import com.example.shopcore.model.OperationResult;
import com.example.shopcore.session.ConfirmationSession;
import com.example.shopcore.session.ConfirmationType;
import com.example.shopcore.session.InputSession;
import com.example.shopcore.session.InputType;
import com.example.shopcore.session.SessionManager;
import com.example.shopcore.storage.SqliteShopStorage;
import com.example.shopcore.util.Amounts;
import com.example.shopcore.util.Scheduler;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * 货币兑换。
 *
 * <p>Wallet 只提供 credit/debit，没有原子兑换端点，所以执行顺序是
 * 扣源币 → 加目标币；目标加币确定失败时自动退回源币，结果未知时保留订单并提示业务号。
 * 兑换方向只使用 Wallet 从官网读取到且 {@code enabled=true} 的资产。</p>
 */
public final class ExchangeService {

    private static final long PAIRS_CACHE_MS = 60_000L;

    private final ShopCorePlugin plugin;
    private final PluginSettings settings;
    private final Messages messages;
    private final SqliteShopStorage storage;
    private final WalletEconomy wallet;
    private final SessionManager sessions;

    private volatile CachedPairs cachedPairs;

    public ExchangeService(ShopCorePlugin plugin,
                           PluginSettings settings,
                           Messages messages,
                           SqliteShopStorage storage,
                           WalletEconomy wallet,
                           SessionManager sessions) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.storage = storage;
        this.wallet = wallet;
        this.sessions = sessions;
    }

    public boolean available() {
        return settings.exchange().enabled() && wallet.available();
    }

    public CompletableFuture<OperationResult<List<ExchangePair>>> pairs(boolean refresh) {
        if (!settings.exchange().enabled()) {
            return CompletableFuture.completedFuture(OperationResult.ok(List.of()));
        }
        CachedPairs cached = cachedPairs;
        if (!refresh && cached != null && System.currentTimeMillis() - cached.createdAt() < PAIRS_CACHE_MS) {
            return CompletableFuture.completedFuture(OperationResult.ok(cached.pairs()));
        }
        return wallet.assets(refresh).handle((assets, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "读取 Wallet 货币列表失败", error);
                return OperationResult.<List<ExchangePair>>fail(
                        "error.exchange-unavailable", "无法读取 Wallet 货币列表。");
            }
            List<ExchangePair> pairs = ExchangePairFactory.build(settings.exchange(), assets);
            plugin.getLogger().fine("兑换方向已加载：" + pairs.size() + " 个，"
                    + "Wallet 资产=" + assets.stream().map(a -> a.code() + "/" + a.scale()).toList());
            cachedPairs = new CachedPairs(pairs, System.currentTimeMillis());
            return OperationResult.ok(pairs);
        });
    }

    public void startExchange(Player player, ExchangePair pair) {
        if (!available()) {
            send(player, "error.exchange-unavailable",
                    "货币兑换未启用，或 Wallet 尚未就绪。");
            return;
        }
        player.closeInventory();
        sessions.startInput(new InputSession(
                player.getUniqueId(), InputType.EXCHANGE_AMOUNT, pair.key(), null,
                null, pair.label(), inputExpiry()));
        messages.send(player, "prompt.exchange", Map.of(
                "from", pair.from().code(),
                "to", pair.to().code(),
                "rate", pair.rateText()), "请输入要兑换的数量。");
    }

    public void handleInput(Player player, InputSession session, String text) {
        pairs(false).whenComplete((pairsResult, pairsError) -> {
            if (pairsError != null) {
                plugin.getLogger().log(Level.SEVERE, "读取兑换方向失败", pairsError);
                Scheduler.runSync(plugin, () -> send(player, "error.database", "读取兑换方向失败。"));
                return;
            }
            if (!pairsResult.success()) {
                Scheduler.runSync(plugin, () -> send(player, pairsResult.messageKey(),
                        pairsResult.placeholders(), pairsResult.fallback()));
                return;
            }
            ExchangePair pair = findPair(pairsResult.value(), session.targetId());
            if (pair == null) {
                Scheduler.runSync(plugin, () -> send(player, "error.exchange-pair-not-found",
                        "该兑换方向不存在或已停用。"));
                return;
            }
            quote(player.getUniqueId(), player.getName(), pair, text).whenComplete((quoteResult, quoteError) -> {
                if (quoteError != null) {
                    plugin.getLogger().log(Level.SEVERE, "兑换报价失败", quoteError);
                    Scheduler.runSync(plugin, () -> send(player, "error.database", "兑换报价失败。"));
                    return;
                }
                Scheduler.runSync(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (!quoteResult.success()) {
                        send(player, quoteResult.messageKey(), quoteResult.placeholders(), quoteResult.fallback());
                        if (isRetryable(quoteResult.messageKey())) {
                            sessions.putInputBack(session, System.currentTimeMillis());
                        }
                        return;
                    }
                    ExchangeQuote quote = quoteResult.value();
                    ConfirmationSession confirmation = new ConfirmationSession(
                            null, player.getUniqueId(), ConfirmationType.EXCHANGE,
                            quote.pair().key(), null, null, quote.pair().label(), 0,
                            quote.pair().rate(), quote.targetAmount(),
                            quote.sourceAmount(), quote.targetAmount(), confirmExpiry());
                    UUID token = sessions.createConfirmation(confirmation);
                    Map<String, String> ph = exchangePlaceholders(quote);
                    messages.sendConfirm(player, "confirm.exchange", ph,
                            "确认兑换？", token);
                });
            });
        });
    }

    public void confirm(Player player, ConfirmationSession session) {
        pairs(false).whenComplete((pairsResult, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "读取兑换方向失败", error);
                Scheduler.runSync(plugin, () -> send(player, "error.database", "读取兑换方向失败。"));
                return;
            }
            if (!pairsResult.success()) {
                Scheduler.runSync(plugin, () -> send(player, pairsResult));
                return;
            }
            ExchangePair pair = findPair(pairsResult.value(), session.targetId());
            if (pair == null) {
                Scheduler.runSync(plugin, () -> send(player, "error.exchange-pair-not-found",
                        "该兑换方向不存在或已停用。"));
                return;
            }
            BigDecimal source = session.exchangeSourceAmount();
            BigDecimal target;
            try {
                target = ExchangeRateCalculator.targetAmount(pair, source);
            } catch (RuntimeException ex) {
                Scheduler.runSync(plugin, () -> send(player, "error.exchange-too-small",
                        "兑换金额过小。"));
                return;
            }
            if (target.compareTo(session.exchangeTargetAmount()) != 0) {
                Scheduler.runSync(plugin, () -> send(player, "error.price-changed",
                        "兑换比例已变化，请重新操作。"));
                return;
            }
            executeExchange(player.getUniqueId(), player.getName(), pair, source, target)
                    .whenComplete((result, executeError) -> {
                        if (executeError != null) {
                            plugin.getLogger().log(Level.SEVERE, "兑换执行失败", executeError);
                            Scheduler.runSync(plugin, () ->
                                    send(player, "error.database", "兑换失败。"));
                            return;
                        }
                        Scheduler.runSync(plugin, () -> send(player, result));
                    });
        });
    }

    /**
     * 管理/测试入口：不走点击确认，直接按当前配置报价并执行。
     */
    public CompletableFuture<OperationResult<ExchangeOrder>> directExchange(OfflinePlayer player,
                                                                            String pairKey,
                                                                            String rawAmount) {
        String name = player.getName() == null ? "" : player.getName();
        return pairs(false).thenCompose(pairsResult -> {
            if (!pairsResult.success()) {
                plugin.getLogger().warning("管理员兑换：读取兑换方向失败 "
                        + pairsResult.messageKey() + " " + pairsResult.fallback());
                return CompletableFuture.completedFuture(
                        OperationResult.<ExchangeOrder>fail(
                                pairsResult.messageKey(), pairsResult.placeholders(), pairsResult.fallback()));
            }
            ExchangePair pair = findPair(pairsResult.value(), pairKey);
            if (pair == null) {
                plugin.getLogger().warning("管理员兑换：方向不存在 " + pairKey + "，当前="
                        + pairsResult.value().stream().map(ExchangePair::key).toList());
                return CompletableFuture.completedFuture(OperationResult.<ExchangeOrder>fail(
                        "error.exchange-pair-not-found", "该兑换方向不存在或已停用。"));
            }
            return quote(player.getUniqueId(), name, pair, rawAmount)
                    .thenCompose(quoteResult -> {
                        if (!quoteResult.success()) {
                            plugin.getLogger().warning("管理员兑换：报价失败 "
                                    + quoteResult.messageKey() + " " + quoteResult.fallback());
                            return CompletableFuture.completedFuture(OperationResult.<ExchangeOrder>fail(
                                    quoteResult.messageKey(), quoteResult.placeholders(), quoteResult.fallback()));
                        }
                        ExchangeQuote quote = quoteResult.value();
                        return executeExchange(player.getUniqueId(), name, pair,
                                quote.sourceAmount(), quote.targetAmount());
                    });
        });
    }

    private CompletableFuture<OperationResult<ExchangeQuote>> quote(UUID playerUuid,
                                                                   String playerName,
                                                                   ExchangePair pair,
                                                                   String rawAmount) {
        BigDecimal source;
        try {
            source = Amounts.parsePositive(rawAmount, pair.from().scale());
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.completedFuture(OperationResult.<ExchangeQuote>fail(
                    "error.invalid-input",
                    Map.of("message", ex.getMessage() == null ? "数量格式不正确" : ex.getMessage()),
                    ex.getMessage()));
        }
        BigDecimal target;
        try {
            target = ExchangeRateCalculator.targetAmount(pair, source);
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.completedFuture(OperationResult.<ExchangeQuote>fail(
                    "error.exchange-too-small", "兑换金额过小。"));
        }
        if (target.signum() <= 0) {
            return CompletableFuture.completedFuture(OperationResult.<ExchangeQuote>fail(
                    "error.exchange-too-small",
                    "按目标货币精度计算后的金额为 0，请增加兑换数量。"));
        }
        WalletEconomy bridge = wallet;
        if (!bridge.available()) {
            return CompletableFuture.completedFuture(OperationResult.<ExchangeQuote>fail(
                    "error.exchange-unavailable", "Wallet 尚未就绪。"));
        }
        return bridge.balance(playerUuid, playerName, pair.from().code())
                .handle((balance, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "读取源货币余额失败", error);
                        return OperationResult.<ExchangeQuote>fail(
                                "error.exchange-unavailable", "读取源货币余额失败。");
                    }
                    BigDecimal available = balance == null ? BigDecimal.ZERO : balance;
                    if (available.compareTo(source) < 0) {
                        return OperationResult.<ExchangeQuote>fail(
                                "error.not-enough-money",
                                Map.of("total", format(pair.from(), source),
                                        "currency", pair.from().code()),
                                "源货币余额不足。");
                    }
                    return OperationResult.ok(new ExchangeQuote(pair, source, target));
                });
    }

    private CompletableFuture<OperationResult<ExchangeOrder>> executeExchange(UUID playerUuid,
                                                                              String playerName,
                                                                              ExchangePair pair,
                                                                              BigDecimal source,
                                                                              BigDecimal target) {
        WalletEconomy bridge = wallet;
        if (!bridge.available()) {
            return CompletableFuture.completedFuture(OperationResult.<ExchangeOrder>fail(
                    "error.exchange-unavailable", "Wallet 尚未就绪。"));
        }
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        String businessNo = "mc:" + settings.serverId() + ":exchange:" + id;
        ExchangeOrder order = new ExchangeOrder(
                id, businessNo, playerUuid, pair.from().code(), pair.to().code(),
                source, target, ExchangeStatus.PENDING, "", now, now);
        return storage.createExchangeOrder(order)
                .thenCompose(ignored -> bridge.exchange(
                        playerUuid, playerName,
                        pair.from().code(), source, pair.to().code(), target,
                        businessNo, "商店货币兑换"))
                .thenCompose(payment -> finishOrder(order, payment, pair))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.SEVERE, "兑换订单执行失败", error);
                    return OperationResult.fail("error.database", "兑换失败。");
                });
    }

    private CompletableFuture<OperationResult<ExchangeOrder>> finishOrder(ExchangeOrder order,
                                                                          WalletPaymentResult payment,
                                                                          ExchangePair pair) {
        ExchangeStatus status;
        String message;
        OperationResult<ExchangeOrder> result;
        if (payment.successful()) {
            status = ExchangeStatus.COMPLETED;
            message = "ok";
            result = OperationResult.ok(order, "success.exchange",
                    orderPlaceholders(order, pair), exchangeFallback(order, pair));
        } else if (payment.status() == WalletPaymentResult.Status.UNKNOWN) {
            status = ExchangeStatus.UNKNOWN;
            message = payment.message();
            result = OperationResult.fail("error.payment-unknown",
                    Map.of("order", order.businessNo()),
                    "兑换结果未知，请把业务号提供给管理员：" + order.businessNo());
        } else if (payment.status() == WalletPaymentResult.Status.INSUFFICIENT_FUNDS) {
            status = ExchangeStatus.FAILED;
            message = payment.message();
            result = OperationResult.fail("error.not-enough-money",
                    Map.of("total", format(pair.from(), order.sourceAmount()),
                            "currency", order.fromCode()),
                    "源货币余额不足。");
        } else {
            status = ExchangeStatus.FAILED;
            message = payment.message();
            result = OperationResult.fail("error.payment-failed",
                    Map.of("reason", payment.message() == null ? "兑换失败" : payment.message()),
                    "兑换失败。");
        }
        return storage.updateExchangeOrderStatus(order.id(), status, message)
                .handle((ignored, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "更新兑换订单状态失败：" + order.id(), error);
                    }
                    return result;
                });
    }

    public CompletableFuture<List<ExchangeOrder>> recentOrders() {
        return storage.listExchangeOrders(30);
    }

    private ExchangePair findPair(List<ExchangePair> pairs, String key) {
        if (key == null) {
            return null;
        }
        for (ExchangePair pair : pairs) {
            if (pair.key().equals(key)) {
                return pair;
            }
        }
        return null;
    }

    private boolean isRetryable(String messageKey) {
        return "error.invalid-input".equals(messageKey)
                || "error.exchange-too-small".equals(messageKey)
                || "error.not-enough-money".equals(messageKey);
    }

    private Map<String, String> exchangePlaceholders(ExchangeQuote quote) {
        return placeholders(
                "source", format(quote.pair().from(), quote.sourceAmount()),
                "from", quote.pair().from().code(),
                "target", format(quote.pair().to(), quote.targetAmount()),
                "to", quote.pair().to().code(),
                "rate", quote.pair().rateText());
    }

    private Map<String, String> orderPlaceholders(ExchangeOrder order, ExchangePair pair) {
        return placeholders(
                "source", format(pair.from(), order.sourceAmount()),
                "from", order.fromCode(),
                "target", format(pair.to(), order.targetAmount()),
                "to", order.toCode());
    }

    private String exchangeFallback(ExchangeOrder order, ExchangePair pair) {
        return "兑换成功：" + format(pair.from(), order.sourceAmount())
                + " " + order.fromCode() + " → "
                + format(pair.to(), order.targetAmount())
                + " " + order.toCode();
    }

    private String format(CurrencyAsset asset, BigDecimal amount) {
        return Amounts.format(amount, asset.scale());
    }

    private void send(Player player, OperationResult<?> result) {
        if (player == null || !player.isOnline() || result.messageKey() == null) {
            return;
        }
        messages.send(player, result.messageKey(), result.placeholders(), result.fallback());
    }

    private void send(Player player, String key, String fallback) {
        if (player != null && player.isOnline()) {
            messages.send(player, key, fallback);
        }
    }

    private void send(Player player, String key, Map<String, String> placeholders, String fallback) {
        if (player != null && player.isOnline()) {
            messages.send(player, key, placeholders, fallback);
        }
    }

    private long inputExpiry() {
        return System.currentTimeMillis() + settings.inputTimeoutSeconds() * 1000L;
    }

    private long confirmExpiry() {
        return System.currentTimeMillis() + settings.confirmTimeoutSeconds() * 1000L;
    }

    private static Map<String, String> placeholders(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1] == null ? "" : keyValues[i + 1]);
        }
        return map;
    }

    private record CachedPairs(List<ExchangePair> pairs, long createdAt) {
    }
}
