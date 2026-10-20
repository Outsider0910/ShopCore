package com.example.shopcore.service;

import com.example.shopcore.config.Messages;
import com.example.shopcore.config.PluginSettings;
import com.example.shopcore.economy.CurrencyAsset;
import com.example.shopcore.economy.WalletEconomy;
import com.example.shopcore.economy.WalletPaymentResult;
import com.example.shopcore.exchange.ExchangeService;
import com.example.shopcore.item.ItemCodec;
import com.example.shopcore.item.UiItems;
import com.example.shopcore.menu.SelectionMode;
import com.example.shopcore.model.MailboxEntry;
import com.example.shopcore.model.OfficialItem;
import com.example.shopcore.model.OfficialQuoteData;
import com.example.shopcore.model.OfficialReservation;
import com.example.shopcore.model.OperationResult;
import com.example.shopcore.model.Order;
import com.example.shopcore.model.PageResult;
import com.example.shopcore.model.PlayerListing;
import com.example.shopcore.model.PlayerQuoteData;
import com.example.shopcore.model.PlayerReservation;
import com.example.shopcore.session.ConfirmationSession;
import com.example.shopcore.session.ConfirmationType;
import com.example.shopcore.session.InputSession;
import com.example.shopcore.session.InputType;
import com.example.shopcore.session.SessionManager;
import com.example.shopcore.storage.SqliteShopStorage;
import com.example.shopcore.util.Amounts;
import com.example.shopcore.util.ResetPeriod;
import com.example.shopcore.util.Scheduler;
import com.example.shopcore.util.ShopInputParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 商店业务编排：GUI 只负责展示和收集点击，真正的校验/扣款/发货都在这里。
 *
 * <p>货币种类、精度、余额全部来自 {@link WalletEconomy}（Wallet -> 官网）；
 * 商品和寄售各自记录一个 Wallet 资产 code，购买时按该资产扣款。</p>
 */
public final class ShopService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final long PENDING_SELECTION_TTL_MS = 120_000L;

    private enum PendingMode {
        PLAYER_LISTING,
        ADMIN_ADD,
        ADMIN_EDIT_CURRENCY
    }

    private record PendingSelection(PendingMode mode, String targetId, byte[] itemData,
                                    String itemName, long expiresAt) {
        PendingSelection {
            itemData = itemData == null ? null : itemData.clone();
        }

        @Override
        public byte[] itemData() {
            return itemData == null ? null : itemData.clone();
        }
    }

    private final Plugin plugin;
    private final PluginSettings settings;
    private final Messages messages;
    private final SqliteShopStorage storage;
    private final WalletEconomy wallet;
    private final DeliveryService delivery;
    private final SessionManager sessions;
    private final UiItems uiItems;
    private final ExchangeService exchange;
    private final Map<UUID, PendingSelection> pendingSelections = new ConcurrentHashMap<>();

    public ShopService(Plugin plugin,
                       PluginSettings settings,
                       Messages messages,
                       SqliteShopStorage storage,
                       WalletEconomy wallet,
                       DeliveryService delivery,
                       SessionManager sessions,
                       UiItems uiItems,
                       ExchangeService exchange) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.storage = storage;
        this.wallet = wallet;
        this.delivery = delivery;
        this.sessions = sessions;
        this.uiItems = uiItems;
        this.exchange = exchange;
    }

    // ------------------------------------------------------------
    // 数据读取
    // ------------------------------------------------------------

    public CompletableFuture<PageResult<OfficialItem>> officialPage(int page, int pageSize) {
        return storage.listOfficialItems(true).thenApply(items -> {
            int total = items.size();
            int from = Math.min(items.size(), Math.max(0, page) * pageSize);
            int to = Math.min(items.size(), from + pageSize);
            return new PageResult<>(items.subList(from, to), total, page, pageSize);
        });
    }

    public CompletableFuture<PageResult<OfficialItem>> allOfficialPage(int page, int pageSize) {
        return storage.listOfficialItems(false).thenApply(items -> {
            int total = items.size();
            int from = Math.min(items.size(), Math.max(0, page) * pageSize);
            int to = Math.min(items.size(), from + pageSize);
            return new PageResult<>(items.subList(from, to), total, page, pageSize);
        });
    }

    public CompletableFuture<PageResult<PlayerListing>> playerPage(int page, int pageSize) {
        int offset = Math.max(0, page) * pageSize;
        return storage.countPlayerListings().thenCombine(
                storage.listPlayerListings(offset, pageSize),
                (total, items) -> new PageResult<>(items, total, page, pageSize));
    }

    public CompletableFuture<PageResult<PlayerListing>> myListingsPage(UUID seller, int page, int pageSize) {
        int offset = Math.max(0, page) * pageSize;
        return storage.countListingsBySeller(seller).thenCombine(
                storage.listListingsBySeller(seller, offset, pageSize),
                (total, items) -> new PageResult<>(items, total, page, pageSize));
    }

    public CompletableFuture<PageResult<MailboxEntry>> mailboxPage(UUID player, int page, int pageSize) {
        int offset = Math.max(0, page) * pageSize;
        return storage.countMailbox(player).thenCombine(
                storage.listMailbox(player, offset, pageSize),
                (total, items) -> new PageResult<>(items, total, page, pageSize));
    }

    public CompletableFuture<Integer> mailboxCount(UUID player) {
        return storage.countMailbox(player);
    }

    public CompletableFuture<List<Order>> unsettledOrders() {
        return storage.listUnsettledOrders(30);
    }

    /**
     * 管理员处理 PENDING/UNKNOWN 订单。complete 前必须先在官网钱包确认已扣款；
     * cancel 只释放库存/寄售，不会自动退款。
     */
    public CompletableFuture<OperationResult<Order>> resolveOrder(String identifier, boolean complete) {
        return storage.getOrder(identifier).thenCompose(optional -> {
            if (optional.isPresent()) {
                return resolveOrder(optional.get(), complete);
            }
            return storage.getOrderByBusinessNo(identifier)
                    .thenCompose(byBusiness -> byBusiness
                            .map(order -> resolveOrder(order, complete))
                            .orElseGet(() -> CompletableFuture.completedFuture(
                                    OperationResult.fail("error.order-not-found",
                                            "找不到该订单。"))));
        });
    }

    private CompletableFuture<OperationResult<Order>> resolveOrder(Order order, boolean complete) {
        if (order.status() == com.example.shopcore.model.OrderStatus.COMPLETED
                || order.status() == com.example.shopcore.model.OrderStatus.FAILED) {
            return CompletableFuture.completedFuture(
                    OperationResult.fail("error.order-finished", "该订单已经处理过了。"));
        }
        CompletableFuture<Void> action;
        if (order.officialPurchase()) {
            action = complete
                    ? storage.finalizeOfficialOrder(order.id())
                    : storage.cancelOfficialOrder(order.id());
        } else {
            action = complete
                    ? storage.finalizePlayerOrder(order.id())
                    : storage.cancelPlayerOrder(order.id());
        }
        return action.thenApply(ignored -> OperationResult.ok(order))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.SEVERE, "处理订单失败：" + order.id(), error);
                    return OperationResult.fail("error.database", "处理订单失败。");
                });
    }

    public CompletableFuture<Optional<OfficialItem>> officialItem(String itemId) {
        return storage.getOfficialItem(itemId);
    }

    public PluginSettings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public WalletEconomy wallet() {
        return wallet;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public DeliveryService delivery() {
        return delivery;
    }

    public ExchangeService exchange() {
        return exchange;
    }

    // ------------------------------------------------------------
    // 货币选择（上架/官方商品先选 Wallet 官网货币）
    // ------------------------------------------------------------

    public boolean stageSelection(Player player, ItemStack selected, SelectionMode mode) {
        if (ItemCodec.isEmpty(selected)) {
            send(player, "error.not-enough-items", Map.of(), "物品为空。");
            return false;
        }
        if (uiItems.isUiItem(selected)) {
            send(player, "error.ui-item", Map.of(), "商店菜单物品不能上架。");
            return false;
        }
        pendingSelections.put(player.getUniqueId(), new PendingSelection(
                mode == SelectionMode.ADMIN_ADD ? PendingMode.ADMIN_ADD : PendingMode.PLAYER_LISTING,
                null,
                ItemCodec.unitBytes(selected),
                ItemCodec.displayName(selected),
                System.currentTimeMillis() + PENDING_SELECTION_TTL_MS));
        return true;
    }

    public void stageEditCurrency(Player player, String itemId) {
        pendingSelections.put(player.getUniqueId(), new PendingSelection(
                PendingMode.ADMIN_EDIT_CURRENCY,
                itemId,
                null,
                null,
                System.currentTimeMillis() + PENDING_SELECTION_TTL_MS));
    }

    /**
     * 玩家在“选择货币”界面点击了一种 Wallet 官网货币。
     * 上架/官方商品进入价格输入；管理员改币种则直接更新商品。
     */
    public void chooseCurrency(Player player, String assetCode) {
        PendingSelection pending = pendingSelections.remove(player.getUniqueId());
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
            send(player, "error.currency-selection-expired", Map.of(), "货币选择已过期，请重新操作。");
            return;
        }
        Optional<CurrencyAsset> asset = wallet.enabledAsset(assetCode);
        if (asset.isEmpty()) {
            send(player, "error.currency-not-found", Map.of("currency", assetCode),
                    "Wallet 中没有该货币或该货币未启用。");
            return;
        }
        String code = asset.get().code();
        player.closeInventory();
        switch (pending.mode()) {
            case PLAYER_LISTING -> {
                sessions.startInput(new InputSession(
                        player.getUniqueId(), InputType.LISTING, null, code,
                        pending.itemData(), pending.itemName(), inputExpiry()));
                send(player, "prompt.list", currencyPlaceholders(code), "请输入要上架的数量和单价。");
            }
            case ADMIN_ADD -> {
                sessions.startInput(new InputSession(
                        player.getUniqueId(), InputType.ADMIN_ADD_PRICE, null, code,
                        pending.itemData(), pending.itemName(), inputExpiry()));
                send(player, "prompt.admin-price", currencyPlaceholders(code), "请输入官方商品单价。");
            }
            case ADMIN_EDIT_CURRENCY -> changeOfficialCurrency(player, pending.targetId(), code);
        }
    }

    private void changeOfficialCurrency(Player player, String itemId, String assetCode) {
        storage.getOfficialItem(itemId).whenComplete((optional, error) -> {
            if (error != null || optional.isEmpty()) {
                Scheduler.runSync(plugin, () -> send(player, "error.item-not-found",
                        Map.of(), "商品不存在。"));
                return;
            }
            OfficialItem item = optional.get();
            int scale = wallet.scaleOf(assetCode);
            BigDecimal newPrice = item.basePrice().setScale(scale, RoundingMode.DOWN);
            if (newPrice.signum() <= 0) {
                Scheduler.runSync(plugin, () -> send(player, "error.invalid-input",
                        Map.of("message", "当前价格按新货币精度取整后为 0，请先修改单价"),
                        "当前价格按新货币精度取整后为 0，请先修改单价。"));
                return;
            }
            BigDecimal newCap = item.maxPrice();
            if (item.maxPrice().signum() > 0) {
                newCap = item.maxPrice().setScale(scale, RoundingMode.DOWN);
                if (newCap.signum() <= 0) {
                    Scheduler.runSync(plugin, () -> send(player, "error.invalid-input",
                            Map.of("message", "单件最高价按新货币精度取整后为 0，请先关闭或修改最高价"),
                            "单件最高价按新货币精度取整后为 0，请先关闭或修改最高价。"));
                    return;
                }
            }
            OfficialItem updated = new OfficialItem(
                    item.id(), assetCode, item.itemData(),
                    newPrice,
                    item.stock(), item.perPlayerLimit(), item.growthPercent(),
                    newCap,
                    item.resetPeriod(), item.enabled(), item.createdAt(),
                    System.currentTimeMillis());
            storage.updateOfficialItem(updated).whenComplete((ignored, updateError) -> {
                if (updateError != null) {
                    plugin.getLogger().log(Level.SEVERE, "修改官方商品币种失败", updateError);
                    Scheduler.runSync(plugin, () -> send(player, "error.database",
                            Map.of(), "更新失败。"));
                } else {
                    Scheduler.runSync(plugin, () -> send(player, "success.admin-updated",
                            Map.of(), "设置已更新。"));
                }
            });
        });
    }

    public void cleanupPendingSelections() {
        long now = System.currentTimeMillis();
        pendingSelections.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    // ------------------------------------------------------------
    // 上架
    // ------------------------------------------------------------

    private void handleListingInput(Player player, InputSession session, String text) {
        int scale = wallet.scaleOf(session.assetCode());
        ShopInputParser.ListingInput input;
        try {
            input = ShopInputParser.parseListing(
                    text, scale, settings.minPrice(), settings.maxPrice(), settings.maxQuantity());
        } catch (IllegalArgumentException ex) {
            sendInvalid(player, ex);
            sessions.putInputBack(session, System.currentTimeMillis());
            return;
        }
        int available = countMatching(player, session.itemData());
        if (available < input.quantity()) {
            send(player, "error.not-enough-items",
                    Map.of("quantity", Integer.toString(input.quantity()), "available", Integer.toString(available)),
                    "背包中的物品数量不足。");
            sessions.putInputBack(session, System.currentTimeMillis());
            return;
        }
        BigDecimal total = Amounts.scale(
                input.unitPrice().multiply(BigDecimal.valueOf(input.quantity())), scale);
        ConfirmationSession confirmation = new ConfirmationSession(
                null, player.getUniqueId(), ConfirmationType.LISTING, null, session.assetCode(),
                session.itemData(), session.itemName(), input.quantity(), input.unitPrice(), total,
                null, null, confirmExpiry());
        UUID token = sessions.createConfirmation(confirmation);
        Map<String, String> ph = placeholders(
                "quantity", Integer.toString(input.quantity()),
                "price", wallet.format(session.assetCode(), input.unitPrice()),
                "total", wallet.format(session.assetCode(), total),
                "currency", wallet.displayName(session.assetCode()),
                "code", session.assetCode());
        messages.sendConfirm(player, "confirm.list", ph,
                itemPlaceholder("item", session.itemData()),
                "确认上架？", token);
    }

    private void confirmListing(Player player, ConfirmationSession session) {
        if (wallet.enabledAsset(session.assetCode()).isEmpty()) {
            send(player, "error.currency-not-found", Map.of("currency", session.assetCode()),
                    "该货币已停用，请重新上架。");
            return;
        }
        int available = countMatching(player, session.itemData());
        if (available < session.quantity()) {
            send(player, "error.not-enough-items", Map.of(), "背包中的物品数量不足。");
            return;
        }
        if (!removeMatching(player, session.itemData(), session.quantity())) {
            send(player, "error.not-enough-items", Map.of(), "背包中的物品数量不足。");
            return;
        }
        long now = System.currentTimeMillis();
        PlayerListing listing = new PlayerListing(
                UUID.randomUUID().toString(),
                session.assetCode(),
                player.getUniqueId(),
                player.getName(),
                session.itemData(),
                session.unitPrice(),
                session.quantity(),
                now,
                now
        );
        storage.insertPlayerListing(listing, settings.maxListingsPerPlayer())
                .whenComplete((result, error) -> Scheduler.runSync(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "上架写入失败", error);
                        returnItems(player, session.itemData(), session.quantity());
                        send(player, "error.database", Map.of(), "上架失败。");
                        send(player, "info.items-returned", placeholders(
                                "quantity", Integer.toString(session.quantity())),
                                itemPlaceholder("item", session.itemData()), "物品已退回。");
                        return;
                    }
                    if (!result.success()) {
                        returnItems(player, session.itemData(), session.quantity());
                        send(player, result.messageKey(), result.placeholders(), result.fallback());
                        send(player, "info.items-returned", placeholders(
                                "quantity", Integer.toString(session.quantity())),
                                itemPlaceholder("item", session.itemData()), "物品已退回。");
                        return;
                    }
                    send(player, "success.listed", placeholders(
                            "quantity", Integer.toString(session.quantity()),
                            "price", wallet.format(session.assetCode(), session.unitPrice()),
                            "currency", wallet.displayName(session.assetCode()),
                            "code", session.assetCode()),
                            itemPlaceholder("item", session.itemData()), "上架成功。");
                }));
    }

    /**
     * 上架写入失败时退回物品。先写邮箱再尝试放进背包，避免在线玩家背包恰好在此时满掉。
     */
    private void returnItems(Player player, byte[] unitData, int quantity) {
        ItemStack unit;
        try {
            unit = ItemCodec.unitItem(unitData);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "退回物品反序列化失败", ex);
            return;
        }
        delivery.toMailbox(player, unit, quantity).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "退回邮箱失败，改为直接放回背包/掉落", error);
                Scheduler.runSync(plugin, () -> giveOrDrop(player, unit, quantity));
                return;
            }
            Scheduler.runSync(plugin, () -> delivery.deliverAll(player));
        });
    }

    /** 数据库都不可用时的最后兜底：放回背包，放不下的掉落在玩家脚下。 */
    private void giveOrDrop(Player player, ItemStack unit, int quantity) {
        if (!player.isOnline()) {
            plugin.getLogger().severe("玩家已离线，无法退回物品；请管理员检查日志。");
            return;
        }
        int remaining = quantity;
        int maxStack = Math.max(1, unit.getMaxStackSize());
        while (remaining > 0) {
            int batch = Math.min(maxStack, remaining);
            ItemStack stack = unit.asQuantity(batch);
            java.util.HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            remaining -= batch;
        }
        player.updateInventory();
    }

    // ------------------------------------------------------------
    // 购买
    // ------------------------------------------------------------

    public void startBuyOfficial(Player player, String itemId) {
        player.closeInventory();
        sessions.startInput(new InputSession(
                player.getUniqueId(), InputType.BUY_OFFICIAL, itemId, null,
                null, null, inputExpiry()));
        send(player, "prompt.buy", Map.of(), "请输入要购买的数量。");
    }

    public void startBuyPlayer(Player player, String listingId) {
        player.closeInventory();
        sessions.startInput(new InputSession(
                player.getUniqueId(), InputType.BUY_PLAYER, listingId, null,
                null, null, inputExpiry()));
        send(player, "prompt.buy", Map.of(), "请输入要购买的数量。");
    }

    private void handleBuyInput(Player player, InputSession session, String text) {
        int quantity;
        try {
            quantity = ShopInputParser.parseQuantity(text, settings.maxQuantity());
        } catch (IllegalArgumentException ex) {
            sendInvalid(player, ex);
            sessions.putInputBack(session, System.currentTimeMillis());
            return;
        }
        long now = System.currentTimeMillis();
        if (session.type() == InputType.BUY_OFFICIAL) {
            storage.quoteOfficialPurchase(session.targetId(), player.getUniqueId(), quantity, now)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            plugin.getLogger().log(Level.SEVERE, "官方商品报价失败", error);
                            Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "读取商品失败。"));
                            return;
                        }
                        Scheduler.runSync(plugin, () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (!result.success()) {
                                send(player, result.messageKey(), result.placeholders(), result.fallback());
                                return;
                            }
                            OfficialQuoteData quote = result.value();
                            String assetCode = wallet.resolveCode(quote.item().assetCode());
                            if (wallet.enabledAsset(assetCode).isEmpty()) {
                                send(player, "error.currency-not-found",
                                        Map.of("currency", assetCode),
                                        "该商品使用的货币在 Wallet 中不可用。");
                                return;
                            }
                            String name = ItemCodec.displayName(ItemCodec.unitItem(quote.item().itemData()));
                            ConfirmationSession confirmation = new ConfirmationSession(
                                    null, player.getUniqueId(), ConfirmationType.PURCHASE_OFFICIAL,
                                    quote.item().id(), assetCode, quote.item().itemData(), name, quantity,
                                    quote.quote().firstUnitPrice(), quote.quote().totalPrice(),
                                    null, null, confirmExpiry());
                            UUID token = sessions.createConfirmation(confirmation);
                            sendPurchaseConfirmation(player, confirmation, token);
                        });
                    });
        } else {
            storage.quotePlayerPurchase(session.targetId(), player.getUniqueId(), quantity)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            plugin.getLogger().log(Level.SEVERE, "玩家寄售报价失败", error);
                            Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "读取寄售失败。"));
                            return;
                        }
                        Scheduler.runSync(plugin, () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (!result.success()) {
                                send(player, result.messageKey(), result.placeholders(), result.fallback());
                                return;
                            }
                            PlayerQuoteData quote = result.value();
                            PlayerListing listing = quote.listing();
                            String assetCode = wallet.resolveCode(listing.assetCode());
                            if (wallet.enabledAsset(assetCode).isEmpty()) {
                                send(player, "error.currency-not-found",
                                        Map.of("currency", assetCode),
                                        "该寄售使用的货币在 Wallet 中不可用。");
                                return;
                            }
                            String name = ItemCodec.displayName(ItemCodec.unitItem(listing.itemData()));
                            ConfirmationSession confirmation = new ConfirmationSession(
                                    null, player.getUniqueId(), ConfirmationType.PURCHASE_PLAYER,
                                    listing.id(), assetCode, listing.itemData(), name, quantity,
                                    listing.unitPrice(), quote.totalPrice(),
                                    null, null, confirmExpiry());
                            UUID token = sessions.createConfirmation(confirmation);
                            sendPurchaseConfirmation(player, confirmation, token);
                        });
                    });
        }
    }

    private void sendPurchaseConfirmation(Player player, ConfirmationSession confirmation, UUID token) {
        Map<String, String> ph = placeholders(
                "quantity", Integer.toString(confirmation.quantity()),
                "price", wallet.format(confirmation.assetCode(), confirmation.unitPrice()),
                "total", wallet.format(confirmation.assetCode(), confirmation.totalPrice()),
                "currency", wallet.displayName(confirmation.assetCode()),
                "code", confirmation.assetCode());
        messages.sendConfirm(player, "confirm.purchase", ph,
                itemPlaceholder("item", confirmation.itemData()),
                "确认购买？", token);
    }

    private void confirmPurchaseOfficial(Player player, ConfirmationSession session) {
        if (!wallet.available()) {
            send(player, "error.no-wallet", Map.of(), "Wallet 尚未就绪，暂时无法购买。");
            return;
        }
        String buyerName = player.getName();
        long now = System.currentTimeMillis();
        storage.reserveOfficialPurchase(
                        session.targetId(), player.getUniqueId(), session.quantity(),
                        settings.serverId(), now)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "官方商品占库存失败", error);
                        Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "下单失败。"));
                        return;
                    }
                    if (!result.success()) {
                        Scheduler.runSync(plugin, () ->
                                send(player, result.messageKey(), result.placeholders(), result.fallback()));
                        return;
                    }
                    OfficialReservation reservation = result.value();
                    if (reservation.quote().totalPrice().compareTo(session.totalPrice()) != 0
                            || !reservation.order().assetCode().equals(session.assetCode())) {
                        storage.cancelOfficialOrder(reservation.order().id());
                        Scheduler.runSync(plugin, () ->
                                send(player, "error.price-changed", Map.of(), "价格已变化，请重新操作。"));
                        return;
                    }
                    Scheduler.runSync(plugin, () ->
                            payOfficial(player, buyerName, session, reservation));
                });
    }

    private void payOfficial(Player player, String buyerName, ConfirmationSession session,
                             OfficialReservation reservation) {
        Order order = reservation.order();
        CompletableFuture<WalletPaymentResult> paymentFuture;
        try {
            paymentFuture = wallet.transfer(
                    player.getUniqueId(), buyerName, null, null, order.assetCode(),
                    order.totalPrice(), order.businessNo(), "官方商店购买");
        } catch (Throwable ex) {
            paymentFuture = CompletableFuture.failedFuture(ex);
        }
        paymentFuture = paymentFuture.orTimeout(settings.paymentTimeoutSeconds(), TimeUnit.SECONDS);
        paymentFuture.whenComplete((payment, paymentError) -> {
            if (paymentError != null || payment == null) {
                markUnknown(order, paymentError);
                Scheduler.runSync(plugin, () -> sendPaymentUnknown(player, order));
                return;
            }
            if (payment.successful()) {
                storage.finalizeOfficialOrder(order.id()).whenComplete((ignored, finalizeError) -> {
                    if (finalizeError != null) {
                        plugin.getLogger().log(Level.SEVERE, "官方订单收口失败：" + order.id(), finalizeError);
                        storage.markOrderUnknown(order.id());
                        Scheduler.runSync(plugin, () -> sendPaymentUnknown(player, order));
                        return;
                    }
                    Scheduler.runSync(plugin, () -> {
                        send(player, "success.purchased", placeholders(
                                "quantity", Integer.toString(order.quantity()),
                                "total", wallet.format(order.assetCode(), order.totalPrice()),
                                "price", wallet.format(order.assetCode(), session.unitPrice()),
                                "currency", wallet.displayName(order.assetCode()),
                                "code", order.assetCode()),
                                itemPlaceholder("item", session.itemData()), "购买成功。");
                        delivery.deliverAll(player).thenAccept(result -> {
                            if (result.remaining() > 0) {
                                Scheduler.runSync(plugin, () -> send(player, "info.mailbox-remaining",
                                        Map.of("count", Integer.toString(result.remaining())),
                                        "背包已满，物品留在待领取。"));
                            }
                        });
                    });
                });
                return;
            }
            if (payment.status() == WalletPaymentResult.Status.UNKNOWN) {
                markUnknown(order, null);
                Scheduler.runSync(plugin, () -> sendPaymentUnknown(player, order));
                return;
            }
            storage.cancelOfficialOrder(order.id());
            Scheduler.runSync(plugin, () -> {
                if (payment.status() == WalletPaymentResult.Status.INSUFFICIENT_FUNDS) {
                    send(player, "error.not-enough-money", placeholders(
                            "total", wallet.format(order.assetCode(), order.totalPrice()),
                            "currency", wallet.displayName(order.assetCode()),
                            "code", order.assetCode()), "余额不足。");
                } else {
                    send(player, "error.payment-failed",
                            Map.of("reason", payment.message() == null ? "支付失败" : payment.message()),
                            "支付失败。");
                }
            });
        });
    }

    private void confirmPurchasePlayer(Player player, ConfirmationSession session) {
        if (!wallet.available()) {
            send(player, "error.no-wallet", Map.of(), "Wallet 尚未就绪，暂时无法购买。");
            return;
        }
        String buyerName = player.getName();
        long now = System.currentTimeMillis();
        storage.reservePlayerPurchase(
                        session.targetId(), player.getUniqueId(), session.quantity(),
                        settings.serverId(), now)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "玩家寄售占位失败", error);
                        Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "下单失败。"));
                        return;
                    }
                    if (!result.success()) {
                        Scheduler.runSync(plugin, () ->
                                send(player, result.messageKey(), result.placeholders(), result.fallback()));
                        return;
                    }
                    PlayerReservation reservation = result.value();
                    if (reservation.order().totalPrice().compareTo(session.totalPrice()) != 0
                            || !reservation.order().assetCode().equals(session.assetCode())) {
                        storage.cancelPlayerOrder(reservation.order().id());
                        Scheduler.runSync(plugin, () ->
                                send(player, "error.price-changed", Map.of(), "价格已变化，请重新操作。"));
                        return;
                    }
                    Scheduler.runSync(plugin, () ->
                            payPlayer(player, buyerName, session, reservation));
                });
    }

    private void payPlayer(Player player, String buyerName, ConfirmationSession session,
                           PlayerReservation reservation) {
        Order order = reservation.order();
        String sellerName = nameOf(order.sellerUuid());
        CompletableFuture<WalletPaymentResult> paymentFuture;
        try {
            paymentFuture = wallet.transfer(
                    player.getUniqueId(), buyerName, order.sellerUuid(),
                    sellerName, order.assetCode(), order.totalPrice(),
                    order.businessNo(), "玩家商店购买");
        } catch (Throwable ex) {
            paymentFuture = CompletableFuture.failedFuture(ex);
        }
        paymentFuture = paymentFuture.orTimeout(settings.paymentTimeoutSeconds(), TimeUnit.SECONDS);
        paymentFuture.whenComplete((payment, paymentError) -> {
            if (paymentError != null || payment == null) {
                markUnknown(order, paymentError);
                Scheduler.runSync(plugin, () -> sendPaymentUnknown(player, order));
                return;
            }
            if (payment.successful()) {
                storage.finalizePlayerOrder(order.id()).whenComplete((ignored, finalizeError) -> {
                    if (finalizeError != null) {
                        plugin.getLogger().log(Level.SEVERE, "玩家订单收口失败：" + order.id(), finalizeError);
                        storage.markOrderUnknown(order.id());
                        Scheduler.runSync(plugin, () -> sendPaymentUnknown(player, order));
                        return;
                    }
                    Scheduler.runSync(plugin, () -> {
                        send(player, "success.purchased", placeholders(
                                "quantity", Integer.toString(order.quantity()),
                                "total", wallet.format(order.assetCode(), order.totalPrice()),
                                "price", wallet.format(order.assetCode(), session.unitPrice()),
                                "currency", wallet.displayName(order.assetCode()),
                                "code", order.assetCode()),
                                itemPlaceholder("item", session.itemData()), "购买成功。");
                        delivery.deliverAll(player).thenAccept(result -> {
                            if (result.remaining() > 0) {
                                Scheduler.runSync(plugin, () -> send(player, "info.mailbox-remaining",
                                        Map.of("count", Integer.toString(result.remaining())),
                                        "背包已满，物品留在待领取。"));
                            }
                        });
                        Player seller = Bukkit.getPlayer(order.sellerUuid());
                        if (seller != null && seller.isOnline()) {
                            send(seller, "info.item-sold", placeholders(
                                    "quantity", Integer.toString(order.quantity()),
                                    "total", wallet.format(order.assetCode(), order.totalPrice()),
                                    "currency", wallet.displayName(order.assetCode()),
                                    "code", order.assetCode()),
                                    itemPlaceholder("item", session.itemData()), "你的寄售已售出。");
                        }
                    });
                });
                return;
            }
            if (payment.status() == WalletPaymentResult.Status.UNKNOWN) {
                markUnknown(order, null);
                Scheduler.runSync(plugin, () -> sendPaymentUnknown(player, order));
                return;
            }
            storage.cancelPlayerOrder(order.id());
            Scheduler.runSync(plugin, () -> {
                if (payment.status() == WalletPaymentResult.Status.INSUFFICIENT_FUNDS) {
                    send(player, "error.not-enough-money", placeholders(
                            "total", wallet.format(order.assetCode(), order.totalPrice()),
                            "currency", wallet.displayName(order.assetCode()),
                            "code", order.assetCode()), "余额不足。");
                } else {
                    send(player, "error.payment-failed",
                            Map.of("reason", payment.message() == null ? "支付失败" : payment.message()),
                            "支付失败。");
                }
            });
        });
    }

    private void markUnknown(Order order, Throwable error) {
        if (error != null) {
            plugin.getLogger().log(Level.SEVERE, "支付结果未知：" + order.businessNo(), error);
        }
        storage.markOrderUnknown(order.id()).exceptionally(ex -> {
            plugin.getLogger().log(Level.SEVERE, "标记订单未知失败：" + order.id(), ex);
            return null;
        });
    }

    private void sendPaymentUnknown(Player player, Order order) {
        send(player, "error.payment-unknown",
                Map.of("order", order.businessNo()),
                "支付结果未知，请联系管理员对账。");
    }

    // ------------------------------------------------------------
    // 下架 / 邮件
    // ------------------------------------------------------------

    public void requestDelist(Player player, String listingId) {
        storage.getPlayerListing(listingId).whenComplete((optional, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "读取寄售失败", error);
                Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "读取寄售失败。"));
                return;
            }
            Scheduler.runSync(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (optional.isEmpty() || !optional.get().sellerUuid().equals(player.getUniqueId())
                        || !optional.get().active()) {
                    send(player, "error.listing-not-found", Map.of(), "该寄售不存在、已售罄或不属于你。");
                    return;
                }
                PlayerListing listing = optional.get();
                String name = ItemCodec.displayName(ItemCodec.unitItem(listing.itemData()));
                player.closeInventory();
                ConfirmationSession confirmation = new ConfirmationSession(
                        null, player.getUniqueId(), ConfirmationType.DELIST, listing.id(),
                        listing.assetCode(), listing.itemData(), name, listing.quantity(),
                        listing.unitPrice(),
                        Amounts.scale(listing.unitPrice().multiply(BigDecimal.valueOf(listing.quantity())),
                                wallet.scaleOf(listing.assetCode())),
                        null, null, confirmExpiry());
                UUID token = sessions.createConfirmation(confirmation);
                Map<String, String> ph = placeholders(
                        "quantity", Integer.toString(listing.quantity()));
                messages.sendConfirm(player, "confirm.delist", ph,
                        itemPlaceholder("item", listing.itemData()),
                        "确认下架？", token);
            });
        });
    }

    private void confirmDelist(Player player, ConfirmationSession session) {
        storage.removePlayerListing(session.targetId(), player.getUniqueId())
                .whenComplete((result, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "下架失败", error);
                        Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "下架失败。"));
                        return;
                    }
                    Scheduler.runSync(plugin, () -> {
                        if (!result.success()) {
                            send(player, result.messageKey(), result.placeholders(), result.fallback());
                            return;
                        }
                        send(player, "success.delisted", placeholders(
                                "quantity", Integer.toString(session.quantity())),
                                itemPlaceholder("item", session.itemData()), "下架成功。");
                        delivery.deliverAll(player).thenAccept(deliveryResult -> {
                            if (deliveryResult.remaining() > 0) {
                                Scheduler.runSync(plugin, () -> send(player, "info.mailbox-remaining",
                                        Map.of("count", Integer.toString(deliveryResult.remaining())),
                                        "背包已满，物品留在待领取。"));
                            }
                        });
                    });
                });
    }

    public CompletableFuture<DeliveryService.DeliveryResult> deliverMailbox(Player player) {
        return delivery.deliverAll(player);
    }

    // ------------------------------------------------------------
    // 管理员商品
    // ------------------------------------------------------------

    public void startAdminEdit(Player player, String itemId, InputType type) {
        player.closeInventory();
        sessions.startInput(new InputSession(
                player.getUniqueId(), type, itemId, null, null, null, inputExpiry()));
        String key = switch (type) {
            case ADMIN_EDIT_PRICE -> "prompt.admin-price";
            case ADMIN_EDIT_STOCK -> "prompt.admin-stock";
            case ADMIN_EDIT_LIMIT -> "prompt.admin-limit";
            case ADMIN_EDIT_GROWTH -> "prompt.admin-growth";
            case ADMIN_EDIT_MAX_PRICE -> "prompt.admin-max-price";
            default -> "prompt.admin-price";
        };
        if (type == InputType.ADMIN_EDIT_PRICE || type == InputType.ADMIN_EDIT_MAX_PRICE) {
            storage.getOfficialItem(itemId).whenComplete((optional, error) -> Scheduler.runSync(plugin, () -> {
                String code = optional.map(OfficialItem::assetCode).orElse("");
                send(player, key, currencyPlaceholders(code), "请输入新的值。");
            }));
        } else {
            send(player, key, Map.of(), "请输入新的值。");
        }
    }

    public void requestDeleteOfficial(Player player, OfficialItem item) {
        player.closeInventory();
        String name = ItemCodec.displayName(ItemCodec.unitItem(item.itemData()));
        ConfirmationSession confirmation = new ConfirmationSession(
                null, player.getUniqueId(), ConfirmationType.ADMIN_DELETE, item.id(),
                item.assetCode(), null, name, 0, ZERO, ZERO, null, null, confirmExpiry());
        UUID token = sessions.createConfirmation(confirmation);
        messages.sendConfirm(player, "confirm.admin-delete",
                Map.of(), itemPlaceholder("item", item.itemData()),
                "确认删除官方商品？", token);
    }

    public CompletableFuture<Void> cycleResetPeriod(Player player, String itemId) {
        return storage.getOfficialItem(itemId).thenCompose(optional -> {
            if (optional.isEmpty()) {
                Scheduler.runSync(plugin, () -> send(player, "error.item-not-found", Map.of(), "商品不存在。"));
                return CompletableFuture.completedFuture(null);
            }
            OfficialItem item = optional.get();
            ResetPeriod next = item.resetPeriod().next();
            OfficialItem updated = new OfficialItem(
                    item.id(), item.assetCode(), item.itemData(), item.basePrice(), item.stock(),
                    item.perPlayerLimit(), item.growthPercent(), item.maxPrice(), next,
                    item.enabled(), item.createdAt(), System.currentTimeMillis());
            return storage.updateOfficialItem(updated).whenComplete((ignored, updateError) -> {
                if (updateError != null) {
                    Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "更新失败。"));
                } else {
                    Scheduler.runSync(plugin, () -> send(player, "success.admin-updated",
                            Map.of(), "设置已更新。"));
                }
            });
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "切换重置周期失败", error);
            Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "更新失败。"));
            return null;
        });
    }

    public CompletableFuture<Void> toggleEnabled(Player player, String itemId) {
        return storage.getOfficialItem(itemId).thenCompose(optional -> {
            if (optional.isEmpty()) {
                Scheduler.runSync(plugin, () -> send(player, "error.item-not-found", Map.of(), "商品不存在。"));
                return CompletableFuture.completedFuture(null);
            }
            OfficialItem item = optional.get();
            OfficialItem updated = new OfficialItem(
                    item.id(), item.assetCode(), item.itemData(), item.basePrice(), item.stock(),
                    item.perPlayerLimit(), item.growthPercent(), item.maxPrice(), item.resetPeriod(),
                    !item.enabled(), item.createdAt(), System.currentTimeMillis());
            return storage.updateOfficialItem(updated).whenComplete((ignored, updateError) -> {
                if (updateError != null) {
                    Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "更新失败。"));
                } else {
                    Scheduler.runSync(plugin, () -> send(player, "success.admin-updated",
                            Map.of(), "设置已更新。"));
                }
            });
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "切换启用状态失败", error);
            Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "更新失败。"));
            return null;
        });
    }

    private void confirmDeleteOfficial(Player player, ConfirmationSession session) {
        storage.deleteOfficialItem(session.targetId()).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "删除官方商品失败", error);
                Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "删除失败。"));
            } else {
                Scheduler.runSync(plugin, () -> send(player, "success.admin-deleted",
                        Map.of(), "官方商品已删除。"));
            }
        });
    }

    // ------------------------------------------------------------
    // 输入分发
    // ------------------------------------------------------------

    public void handleInput(Player player, InputSession session, String text) {
        switch (session.type()) {
            case LISTING -> handleListingInput(player, session, text);
            case BUY_OFFICIAL, BUY_PLAYER -> handleBuyInput(player, session, text);
            case ADMIN_ADD_PRICE -> handleAdminAddPrice(player, session, text);
            case ADMIN_EDIT_PRICE, ADMIN_EDIT_STOCK, ADMIN_EDIT_LIMIT,
                 ADMIN_EDIT_GROWTH, ADMIN_EDIT_MAX_PRICE -> handleAdminEditInput(player, session, text);
            case EXCHANGE_AMOUNT -> exchange.handleInput(player, session, text);
        }
    }

    private void handleAdminAddPrice(Player player, InputSession session, String text) {
        String assetCode = session.assetCode();
        if (wallet.enabledAsset(assetCode).isEmpty()) {
            send(player, "error.currency-not-found", Map.of("currency", assetCode),
                    "Wallet 中没有该货币或该货币未启用。");
            return;
        }
        BigDecimal price;
        try {
            price = Amounts.parsePositive(text, wallet.scaleOf(assetCode));
            checkPriceRange(price, assetCode);
        } catch (IllegalArgumentException ex) {
            sendInvalid(player, ex);
            sessions.putInputBack(session, System.currentTimeMillis());
            return;
        }
        long now = System.currentTimeMillis();
        OfficialItem item = new OfficialItem(
                UUID.randomUUID().toString(), assetCode, session.itemData(), price,
                -1, 0, ZERO, ZERO, ResetPeriod.NEVER, true, now, now);
        storage.insertOfficialItem(item).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "官方商品写入失败", error);
                Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "写入失败。"));
            } else {
                Scheduler.runSync(plugin, () -> send(player, "success.admin-added", placeholders(
                        "price", wallet.format(assetCode, price),
                        "currency", wallet.displayName(assetCode),
                        "code", assetCode),
                        itemPlaceholder("item", session.itemData()), "官方商品已上架。"));
            }
        });
    }

    private void handleAdminEditInput(Player player, InputSession session, String text) {
        storage.getOfficialItem(session.targetId()).whenComplete((optional, readError) -> {
            if (readError != null) {
                plugin.getLogger().log(Level.SEVERE, "读取官方商品失败", readError);
                Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "读取失败。"));
                return;
            }
            if (optional.isEmpty()) {
                Scheduler.runSync(plugin, () -> send(player, "error.item-not-found", Map.of(), "商品不存在。"));
                return;
            }
            OfficialItem current = optional.get();
            OfficialItem updated;
            try {
                updated = applyEdit(current, session.type(), text);
            } catch (IllegalArgumentException ex) {
                Scheduler.runSync(plugin, () -> {
                    sendInvalid(player, ex);
                    sessions.startInput(session);
                });
                return;
            }
            storage.updateOfficialItem(updated).whenComplete((ignored, updateError) -> {
                if (updateError != null) {
                    plugin.getLogger().log(Level.SEVERE, "更新官方商品失败", updateError);
                    Scheduler.runSync(plugin, () -> send(player, "error.database", Map.of(), "更新失败。"));
                } else {
                    Scheduler.runSync(plugin, () -> send(player, "success.admin-updated",
                            Map.of(), "设置已更新。"));
                }
            });
        });
    }

    private OfficialItem applyEdit(OfficialItem item, InputType type, String text) {
        long now = System.currentTimeMillis();
        int scale = wallet.scaleOf(item.assetCode());
        return switch (type) {
            case ADMIN_EDIT_PRICE -> {
                BigDecimal price = Amounts.parsePositive(text, scale);
                checkPriceRange(price, item.assetCode());
                yield new OfficialItem(item.id(), item.assetCode(), item.itemData(), price, item.stock(),
                        item.perPlayerLimit(), item.growthPercent(), item.maxPrice(),
                        item.resetPeriod(), item.enabled(), item.createdAt(), now);
            }
            case ADMIN_EDIT_STOCK -> new OfficialItem(item.id(), item.assetCode(), item.itemData(),
                    item.basePrice(), ShopInputParser.parseStock(text), item.perPlayerLimit(),
                    item.growthPercent(), item.maxPrice(), item.resetPeriod(), item.enabled(),
                    item.createdAt(), now);
            case ADMIN_EDIT_LIMIT -> new OfficialItem(item.id(), item.assetCode(), item.itemData(),
                    item.basePrice(), item.stock(), ShopInputParser.parseLimit(text),
                    item.growthPercent(), item.maxPrice(), item.resetPeriod(), item.enabled(),
                    item.createdAt(), now);
            case ADMIN_EDIT_GROWTH -> {
                BigDecimal growth = Amounts.parse(text, 2);
                if (growth.compareTo(new BigDecimal("10000")) > 0) {
                    throw new IllegalArgumentException("涨价百分比过大");
                }
                yield new OfficialItem(item.id(), item.assetCode(), item.itemData(), item.basePrice(),
                        item.stock(), item.perPlayerLimit(), growth, item.maxPrice(), item.resetPeriod(),
                        item.enabled(), item.createdAt(), now);
            }
            case ADMIN_EDIT_MAX_PRICE -> {
                BigDecimal cap = Amounts.parse(text, scale);
                yield new OfficialItem(item.id(), item.assetCode(), item.itemData(), item.basePrice(),
                        item.stock(), item.perPlayerLimit(), item.growthPercent(), cap,
                        item.resetPeriod(), item.enabled(), item.createdAt(), now);
            }
            default -> throw new IllegalArgumentException("不支持的修改类型");
        };
    }

    public void confirm(Player player, ConfirmationSession session) {
        switch (session.type()) {
            case LISTING -> confirmListing(player, session);
            case DELIST -> confirmDelist(player, session);
            case PURCHASE_OFFICIAL -> confirmPurchaseOfficial(player, session);
            case PURCHASE_PLAYER -> confirmPurchasePlayer(player, session);
            case ADMIN_DELETE -> confirmDeleteOfficial(player, session);
            case EXCHANGE -> exchange.confirm(player, session);
        }
    }

    // ------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------

    public void notifyMailboxOnJoin(Player player) {
        storage.countMailbox(player.getUniqueId()).thenAccept(count -> {
            if (count <= 0) {
                return;
            }
            Scheduler.runSync(plugin, () -> {
                send(player, "info.mailbox-count", Map.of("count", Integer.toString(count)),
                        "你有待领取物品。");
                delivery.deliverAll(player).thenAccept(result -> {
                    if (result.delivered() > 0) {
                        Scheduler.runSync(plugin, () ->
                                send(player, "success.delivered",
                                        Map.of("amount", Integer.toString(result.delivered())),
                                        "已领取物品。"));
                    }
                    if (result.remaining() > 0) {
                        Scheduler.runSync(plugin, () -> send(player, "info.mailbox-remaining",
                                Map.of("count", Integer.toString(result.remaining())),
                                "背包已满，物品留在待领取。"));
                    }
                });
            });
        });
    }

    private void checkPriceRange(BigDecimal price, String assetCode) {
        if (price.compareTo(settings.minPrice()) < 0) {
            throw new IllegalArgumentException("单价不能低于 "
                    + wallet.format(assetCode, settings.minPrice()));
        }
        if (settings.maxPrice().signum() > 0 && price.compareTo(settings.maxPrice()) > 0) {
            throw new IllegalArgumentException("单价不能高于 "
                    + wallet.format(assetCode, settings.maxPrice()));
        }
    }

    private int countMatching(Player player, byte[] unitData) {
        ItemStack reference = referenceItem(unitData);
        if (reference == null) {
            return 0;
        }
        int count = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (ItemStack item : contents) {
            if (ItemCodec.isEmpty(item)) {
                continue;
            }
            if (ItemCodec.sameComponents(item, reference)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private boolean removeMatching(Player player, byte[] unitData, int quantity) {
        ItemStack reference = referenceItem(unitData);
        if (reference == null || quantity <= 0) {
            return false;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = quantity;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (ItemCodec.isEmpty(item) || !ItemCodec.sameComponents(item, reference)) {
                continue;
            }
            int take = Math.min(item.getAmount(), remaining);
            int left = item.getAmount() - take;
            if (left <= 0) {
                player.getInventory().setItem(slot, null);
            } else {
                ItemStack copy = item.clone();
                copy.setAmount(left);
                player.getInventory().setItem(slot, copy);
            }
            remaining -= take;
        }
        if (remaining > 0) {
            return false;
        }
        player.updateInventory();
        return true;
    }

    private ItemStack referenceItem(byte[] unitData) {
        if (unitData == null) {
            return null;
        }
        try {
            return ItemCodec.unitItem(unitData);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "商品组件反序列化失败", ex);
            return null;
        }
    }

    private long inputExpiry() {
        return System.currentTimeMillis() + settings.inputTimeoutSeconds() * 1000L;
    }

    private String nameOf(UUID uuid) {
        if (uuid == null) {
            return "";
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? "" : name;
    }

    private long confirmExpiry() {
        return System.currentTimeMillis() + settings.confirmTimeoutSeconds() * 1000L;
    }

    private Map<String, String> currencyPlaceholders(String assetCode) {
        return placeholders(
                "currency", wallet.displayName(assetCode),
                "code", assetCode == null ? "" : assetCode);
    }

    private void sendInvalid(Player player, IllegalArgumentException ex) {
        String message = ex.getMessage() == null ? "输入格式不正确" : ex.getMessage();
        send(player, "error.invalid-input", Map.of("message", message), message);
    }

    private void send(Player player, String key, Map<String, String> placeholders, String fallback) {
        send(player, key, placeholders, Map.of(), fallback);
    }

    private void send(Player player, String key, Map<String, String> placeholders,
                      Map<String, Component> componentPlaceholders, String fallback) {
        if (player != null && player.isOnline()) {
            messages.send(player, key, placeholders, componentPlaceholders, fallback);
        }
    }

    private Map<String, Component> itemPlaceholder(String key, byte[] itemData) {
        if (itemData == null) {
            return Map.of(key, Component.text("物品"));
        }
        return Map.of(key, ItemCodec.nameComponent(itemData));
    }

    private static Map<String, String> placeholders(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return map;
    }
}
