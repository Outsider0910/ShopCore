package com.example.shopcore.menu;

import com.example.mainmenu.api.MainMenuApi;
import com.example.shopcore.economy.CurrencyAsset;
import com.example.shopcore.exchange.ExchangePair;
import com.example.shopcore.item.ItemCodec;
import com.example.shopcore.item.UiItems;
import com.example.shopcore.model.MailboxEntry;
import com.example.shopcore.model.OfficialItem;
import com.example.shopcore.model.PageResult;
import com.example.shopcore.model.PlayerListing;
import com.example.shopcore.service.DeliveryService;
import com.example.shopcore.service.ShopService;
import com.example.shopcore.session.InputType;
import com.example.shopcore.util.Amounts;
import com.example.shopcore.util.ResetPeriod;
import com.example.shopcore.util.Scheduler;
import com.example.wallet.api.WalletBalance;
import com.example.wallet.api.WalletTransaction;
import com.example.wallet.api.WalletTransactionPage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * GUI 渲染。所有展示物品都是克隆，不会改动寄售/官方商品的组件数据。
 */
public final class MenuService {

    private static final int SIZE = 54;
    private static final int CONTENT = 45;
    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter WALLET_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Plugin plugin;
    private final ShopService shop;
    private final UiItems uiItems;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MenuService(Plugin plugin, ShopService shop, UiItems uiItems) {
        this.plugin = plugin;
        this.shop = shop;
        this.uiItems = uiItems;
    }

    public ShopService shop() {
        return shop;
    }

    // ------------------------------------------------------------
    // 打开入口
    // ------------------------------------------------------------

    public void openMain(Player player) {
        ShopMenuHolder holder = create(player, new MenuView(MenuType.MAIN), shop.settings().titleMain());
        renderMain(holder);
    }

    public void openOfficial(Player player, int page) {
        ShopMenuHolder holder = create(player,
                new MenuView(MenuType.OFFICIAL, page, null, null), shop.settings().titleOfficial());
        loadOfficial(holder, page);
    }

    public void openPlayer(Player player, int page) {
        ShopMenuHolder holder = create(player,
                new MenuView(MenuType.PLAYER, page, null, null), shop.settings().titlePlayer());
        loadPlayer(holder, page);
    }

    public void openMyListings(Player player, int page) {
        ShopMenuHolder holder = create(player,
                new MenuView(MenuType.MY_LISTINGS, page, null, null), shop.settings().titleListings());
        loadMyListings(holder, page);
    }

    public void openMailbox(Player player, int page) {
        ShopMenuHolder holder = create(player,
                new MenuView(MenuType.MAILBOX, page, null, null), shop.settings().titleMailbox());
        loadMailbox(holder, page);
    }

    public void openExchange(Player player, int page) {
        ShopMenuHolder holder = create(player,
                new MenuView(MenuType.EXCHANGE, page, null, null), shop.settings().titleExchange());
        loadExchange(holder, page);
    }

    public void openWalletHistory(Player player, int page) {
        ShopMenuHolder holder = create(player,
                new MenuView(MenuType.WALLET_HISTORY, page, null, null),
                shop.settings().titleWalletHistory());
        loadWalletHistory(holder, page);
    }

    public void openAdminMain(Player player) {
        if (!isAdmin(player)) {
            shop.messages().send(player, "no-permission", "<red>你没有权限执行此操作。</red>");
            return;
        }
        ShopMenuHolder holder = create(player,
                new MenuView(MenuType.ADMIN_MAIN), shop.settings().titleAdmin());
        renderAdminMain(holder);
    }

    public void openAdminManage(Player player, int page) {
        if (!isAdmin(player)) {
            shop.messages().send(player, "no-permission", "<red>你没有权限执行此操作。</red>");
            return;
        }
        ShopMenuHolder holder = create(player,
                new MenuView(MenuType.ADMIN_MANAGE, page, null, null),
                shop.settings().titleAdminManage());
        loadAdminManage(holder, page);
    }

    public void openAdminEdit(Player player, String itemId) {
        if (!isAdmin(player)) {
            shop.messages().send(player, "no-permission", "<red>你没有权限执行此操作。</red>");
            return;
        }
        ShopMenuHolder holder = create(player,
                new MenuView(MenuType.ADMIN_EDIT, 0, itemId, null),
                shop.settings().titleAdminEdit());
        loadAdminEdit(holder, itemId);
    }

    public void openSelection(Player player, SelectionMode mode) {
        ShopMenuHolder holder = create(player,
                new MenuView(MenuType.SELECT_ITEM, 0, null, mode),
                shop.settings().titleSelectItem());
        renderSelection(holder);
    }

    public void openCurrencySelection(Player player, SelectionMode mode) {
        openCurrencySelection(player, mode, null);
    }

    public void openCurrencySelection(Player player, SelectionMode mode, String contextId) {
        ShopMenuHolder holder = create(player,
                new MenuView(MenuType.SELECT_CURRENCY, 0, contextId, mode),
                shop.settings().titleSelectCurrency());
        loadCurrencySelection(holder);
    }

    // ------------------------------------------------------------
    // 基础渲染
    // ------------------------------------------------------------

    private ShopMenuHolder create(Player player, MenuView view, String titleRaw) {
        ShopMenuHolder holder = new ShopMenuHolder(player, view);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, safeTitle(titleRaw));
        holder.setInventory(inventory);
        fill(holder);
        setLoading(holder);
        player.openInventory(inventory);
        return holder;
    }

    private Component safeTitle(String raw) {
        try {
            return UiItems.noItalic(miniMessage.deserialize(raw));
        } catch (RuntimeException ex) {
            return UiItems.noItalic(Component.text(raw));
        }
    }

    private void setLoading(ShopMenuHolder holder) {
        Component name = shop.messages().get("info.loading", "<gray>加载中...</gray>");
        holder.getInventory().setItem(22,
                uiItems.create(Material.CLOCK, UiItems.NAV_PAGE, name, List.of(), false));
    }

    private void fill(ShopMenuHolder holder) {
        holder.clearActions();
        ItemStack filler = uiItems.create(Material.GRAY_STAINED_GLASS_PANE, UiItems.FILLER,
                Component.text(" "), List.of(), false);
        for (int i = 0; i < SIZE; i++) {
            holder.getInventory().setItem(i, filler);
        }
    }

    private void clear(ShopMenuHolder holder) {
        holder.clearActions();
        holder.getInventory().clear();
    }

    private void button(ShopMenuHolder holder,
                        int slot,
                        Material material,
                        String id,
                        Component name,
                        List<Component> lore,
                        boolean glow,
                        MenuAction action) {
        holder.getInventory().setItem(slot, uiItems.create(material, id, name, lore, glow));
        if (action != null) {
            holder.action(slot, action);
        }
    }

    private void setItem(ShopMenuHolder holder, int slot, ItemStack item, MenuAction action) {
        holder.getInventory().setItem(slot, item);
        if (action != null) {
            holder.action(slot, action);
        }
    }

    private Component msg(String key, String fallback) {
        return shop.messages().get(key, fallback);
    }

    private Component msg(String key, Map<String, String> placeholders, String fallback) {
        return shop.messages().get(key, placeholders, fallback);
    }

    private String raw(String key, String fallback) {
        return shop.messages().raw(key, fallback);
    }

    /** 与 Wallet 相同的返回主菜单：下一 tick 调 MainMenuApi.open，旧版 MainMenu 退回 /cd。 */
    private void returnToMainMenu(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean opened = false;
            try {
                opened = MainMenuApi.openOrNull(player);
            } catch (Throwable ignored) {
                // 运行期是旧版 MainMenu（没有 open API）时退回命令
            }
            if (!opened) {
                Bukkit.dispatchCommand(player, "cd");
            }
        });
    }

    private void clickSound(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    private boolean allowed(Player player) {
        return player.hasPermission("shopcore.use");
    }

    private boolean isAdmin(Player player) {
        return player != null && (player.isOp() || player.hasPermission("shopcore.admin"));
    }

    // ------------------------------------------------------------
    // 主菜单
    // ------------------------------------------------------------

    private void renderMain(ShopMenuHolder holder) {
        Player player = holder.player();
        fill(holder);
        button(holder, 19, Material.EMERALD, UiItems.MAIN_OFFICIAL,
                msg("ui.main.official-name", "<green><bold>官方商店</bold></green>"),
                List.of(msg("ui.main.official-lore", "<gray>管理员上架的商品</gray>")),
                true,
                (p, h, slot) -> openOfficial(p, 0));
        button(holder, 21, Material.CHEST, UiItems.MAIN_PLAYER,
                msg("ui.main.player-name", "<aqua><bold>玩家商店</bold></aqua>"),
                List.of(msg("ui.main.player-lore", "<gray>玩家自由寄售</gray>")),
                true,
                (p, h, slot) -> openPlayer(p, 0));
        button(holder, 23, Material.WRITABLE_BOOK, UiItems.MAIN_LISTINGS,
                msg("ui.main.listings-name", "<yellow><bold>我的上架</bold></yellow>"),
                List.of(msg("ui.main.listings-lore", "<gray>查看并下架自己的寄售</gray>")),
                false,
                (p, h, slot) -> openMyListings(p, 0));
        button(holder, 25, Material.HOPPER, UiItems.MAIN_MAILBOX,
                msg("ui.main.mailbox-name", "<gold><bold>待领取</bold></gold>"),
                List.of(msg("ui.main.mailbox-lore", "<gray>背包满时购买/下架的物品会寄存在这里</gray>")),
                false,
                (p, h, slot) -> openMailbox(p, 0));
        button(holder, 27, Material.GOLD_NUGGET, UiItems.MAIN_EXCHANGE,
                msg("ui.main.exchange-name", "<gold><bold>货币兑换</bold></gold>"),
                List.of(msg("ui.main.exchange-lore", "<gray>按配置比例兑换两种货币</gray>")),
                true,
                (p, h, slot) -> openExchange(p, 0));
        button(holder, 40, Material.BARRIER, UiItems.MAIN_CLOSE,
                msg("ui.main.close-name", "<gray>返回主菜单</gray>"),
                List.of(), false, (p, h, slot) -> returnToMainMenu(p));
        if (isAdmin(player)) {
            button(holder, 45, Material.COMMAND_BLOCK, UiItems.ADMIN_ENTRY,
                    msg("ui.admin.entry-name", "<red><bold>商店管理</bold></red>"),
                    List.of(msg("ui.admin.entry-lore", "<gray>管理官方商品</gray>")),
                    false, (p, h, slot) -> openAdminMain(p));
        }
        refreshBalance(holder);
    }

    /**
     * 与 Wallet 主菜单一致：每秒重绘，只读 Wallet 余额缓存，5 秒节流异步刷新。
     */
    private void refreshBalance(ShopMenuHolder holder) {
        Player player = holder.player();
        shop.wallet().refreshBalances(player.getUniqueId(), player.getName());

        List<CurrencyAsset> assets = shop.wallet().cachedEnabledAssets();
        List<Component> lore = new ArrayList<>();
        if (assets.isEmpty()) {
            String key = shop.wallet().cachedAssets().isEmpty()
                    ? "info.balance-loading" : "info.no-currencies";
            String fallback = "info.balance-loading".equals(key)
                    ? "<gray>正在加载货币…</gray>" : "<gray>本服暂无可用货币</gray>";
            lore.add(msg(key, fallback));
        } else {
            for (CurrencyAsset asset : assets) {
                WalletBalance balance = shop.wallet().cachedBalance(
                        player.getUniqueId(), asset.code());
                String value;
                if (balance != null) {
                    value = shop.wallet().formatLikeWallet(asset.code(), balance.balance());
                } else if (shop.wallet().unbound(player.getUniqueId())) {
                    value = raw("info.unbound", "未绑定网站账号");
                } else {
                    value = "…";
                }
                lore.add(msg("info.balance-line", Map.of(
                        "currency", asset.displayName(),
                        "code", asset.code(),
                        "value", value),
                        "<gray><currency>：<white><value></white></gray>"));
            }
            lore.add(msg("info.click-wallet-history",
                    "<dark_gray>点击查看流水</dark_gray>"));
        }

        button(holder, 4, Material.EMERALD, UiItems.MAIN_BALANCE,
                msg("ui.main.balance-name", "<white>钱包</white>"),
                lore, false, (p, h, slot) -> openWalletHistory(p, 0));
    }

    /** 主菜单打开时每秒调用：刷新余额按钮，实际请求由 WalletEconomy 做 5 秒节流。 */
    public void tickOpenMainBalances() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof ShopMenuHolder holder
                    && holder.view().type() == MenuType.MAIN
                    && holder.isOpen()) {
                refreshBalance(holder);
            }
        }
    }

    // ------------------------------------------------------------
    // 官方商店
    // ------------------------------------------------------------

    private void loadOfficial(ShopMenuHolder holder, int page) {
        holder.view().page(page);
        fill(holder);
        setLoading(holder);
        shop.officialPage(page, PAGE_SIZE).whenComplete((result, error) -> Scheduler.runSync(plugin, () -> {
            if (!holder.isOpen()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "加载官方商店失败", error);
                renderError(holder);
                return;
            }
            renderOfficial(holder, result);
        }));
    }

    private void renderOfficial(ShopMenuHolder holder, PageResult<OfficialItem> page) {
        Player player = holder.player();
        fill(holder);
        holder.view().page(page.page());
        if (page.items().isEmpty()) {
            holder.getInventory().setItem(22, uiItems.create(Material.GRAY_STAINED_GLASS_PANE,
                    UiItems.FILLER, msg("info.empty-official", "<gray>官方商店暂时没有商品。</gray>"),
                    List.of(), false));
        }
        int slot = 0;
        for (OfficialItem item : page.items()) {
            if (slot >= CONTENT) {
                break;
            }
            ItemStack display = ItemCodec.unitItem(item.itemData());
            String code = shop.wallet().resolveCode(item.assetCode());
            List<Component> lore = new ArrayList<>();
            lore.add(msg("info.price-base", Map.of(
                    "price", shop.wallet().format(code, item.basePrice()),
                    "currency", shop.wallet().displayName(code),
                    "code", code),
                    "<gray>单价：<gold><price> <currency></gold></gray>"));
            lore.add(stockLine(item));
            if (!item.unlimitedPerPlayer()) {
                lore.add(limitLine(item));
            }
            lore.add(growthLine(item));
            lore.add(msg("info.click-buy", "<yellow>点击购买</yellow>"));
            decorate(display, lore);
            setItem(holder, slot, display, (p, h, clicked) -> shop.startBuyOfficial(p, item.id()));
            slot++;
        }
        navigation(holder, page, () -> openMain(player),
                () -> loadOfficial(holder, page.page() - 1),
                () -> loadOfficial(holder, page.page() + 1));
        if (isAdmin(player)) {
            button(holder, 46, Material.ANVIL, UiItems.ADMIN_ADD,
                    msg("ui.admin.add-name", "<green><bold>上架官方商品</bold></green>"),
                    List.of(msg("ui.admin.add-lore", "<gray>选择背包物品，随后输入单价</gray>")),
                    true, (p, h, clicked) -> openSelection(p, SelectionMode.ADMIN_ADD));
            button(holder, 47, Material.BOOK, UiItems.ADMIN_MANAGE,
                    msg("ui.admin.manage-name", "<yellow><bold>管理官方商品</bold></yellow>"),
                    List.of(msg("ui.admin.manage-lore", "<gray>调整价格、库存、限购和涨价规则</gray>")),
                    false, (p, h, clicked) -> openAdminManage(p, 0));
        }
    }

    // ------------------------------------------------------------
    // 玩家商店
    // ------------------------------------------------------------

    private void loadPlayer(ShopMenuHolder holder, int page) {
        holder.view().page(page);
        fill(holder);
        setLoading(holder);
        shop.playerPage(page, PAGE_SIZE).whenComplete((result, error) -> Scheduler.runSync(plugin, () -> {
            if (!holder.isOpen()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "加载玩家商店失败", error);
                renderError(holder);
                return;
            }
            renderPlayer(holder, result);
        }));
    }

    private void renderPlayer(ShopMenuHolder holder, PageResult<PlayerListing> page) {
        Player player = holder.player();
        fill(holder);
        holder.view().page(page.page());
        if (page.items().isEmpty()) {
            holder.getInventory().setItem(22, uiItems.create(Material.GRAY_STAINED_GLASS_PANE,
                    UiItems.FILLER, msg("info.empty-player", "<gray>玩家商店暂时没有寄售。</gray>"),
                    List.of(), false));
        }
        int slot = 0;
        for (PlayerListing listing : page.items()) {
            if (slot >= CONTENT) {
                break;
            }
            ItemStack display = ItemCodec.unitItem(listing.itemData());
            String code = shop.wallet().resolveCode(listing.assetCode());
            List<Component> lore = new ArrayList<>();
            lore.add(msg("info.listing-owner", Map.of("seller", listing.sellerName()),
                    "<gray>卖家：<white><seller></white></gray>"));
            lore.add(msg("info.price-base", Map.of(
                    "price", shop.wallet().format(code, listing.unitPrice()),
                    "currency", shop.wallet().displayName(code),
                    "code", code),
                    "<gray>单价：<gold><price> <currency></gold></gray>"));
            lore.add(msg("info.listing-remaining", Map.of("quantity", Integer.toString(listing.quantity())),
                    "<gray>剩余：<white><quantity></white></gray>"));
            boolean own = listing.sellerUuid().equals(player.getUniqueId());
            lore.add(own
                    ? msg("info.click-delist", "<yellow>点击下架</yellow>")
                    : msg("info.click-buy", "<yellow>点击购买</yellow>"));
            decorate(display, lore);
            setItem(holder, slot, display, (p, h, clicked) -> {
                if (own) {
                    shop.requestDelist(p, listing.id());
                } else {
                    shop.startBuyPlayer(p, listing.id());
                }
            });
            slot++;
        }
        navigation(holder, page, () -> openMain(player),
                () -> loadPlayer(holder, page.page() - 1),
                () -> loadPlayer(holder, page.page() + 1));
        button(holder, 46, Material.ANVIL, UiItems.PLAYER_LIST,
                msg("ui.player.list-name", "<green><bold>上架物品</bold></green>"),
                List.of(msg("ui.player.list-lore", "<gray>点击后选择背包中的物品</gray>")),
                true, (p, h, clicked) -> openSelection(p, SelectionMode.PLAYER_LISTING));
        button(holder, 47, Material.WRITABLE_BOOK, UiItems.PLAYER_DELIST,
                msg("ui.player.delist-name", "<yellow><bold>下架物品</bold></yellow>"),
                List.of(msg("ui.player.delist-lore", "<gray>点击选择自己的寄售并下架</gray>")),
                false, (p, h, clicked) -> openMyListings(p, 0));
        button(holder, 51, Material.HOPPER, UiItems.PLAYER_MAILBOX,
                msg("ui.player.mailbox-name", "<gold><bold>待领取</bold></gold>"),
                List.of(msg("ui.main.mailbox-lore", "<gray>背包满时物品会寄存在这里</gray>")),
                false, (p, h, clicked) -> openMailbox(p, 0));
    }

    // ------------------------------------------------------------
    // 货币兑换
    // ------------------------------------------------------------

    private void loadExchange(ShopMenuHolder holder, int page) {
        holder.view().page(page);
        fill(holder);
        setLoading(holder);
        shop.exchange().pairs(false).whenComplete((result, error) ->
                Scheduler.runSync(plugin, () -> {
                    if (!holder.isOpen()) {
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "加载货币兑换失败", error);
                        renderError(holder);
                        return;
                    }
                    if (!result.success()) {
                        shop.messages().send(holder.player(), result.messageKey(),
                                result.placeholders(), result.fallback());
                        renderExchange(holder, new PageResult<>(List.of(), 0, page, PAGE_SIZE), true);
                        return;
                    }
                    List<ExchangePair> pairs = result.value();
                    int pages = Math.max(1, (int) Math.ceil(pairs.size() / (double) PAGE_SIZE));
                    int safePage = Math.max(0, Math.min(page, pages - 1));
                    int from = Math.min(pairs.size(), safePage * PAGE_SIZE);
                    int to = Math.min(pairs.size(), from + PAGE_SIZE);
                    PageResult<ExchangePair> pageResult = new PageResult<>(
                            pairs.subList(from, to), pairs.size(), safePage, PAGE_SIZE);
                    renderExchange(holder, pageResult, pairs.isEmpty() && !shop.exchange().available());
                }));
    }

    private void renderExchange(ShopMenuHolder holder,
                                PageResult<ExchangePair> page,
                                boolean unavailable) {
        Player player = holder.player();
        fill(holder);
        holder.view().page(page.page());
        if (page.items().isEmpty()) {
            String key = unavailable ? "ui.exchange.unavailable" : "ui.exchange.empty";
            String fallback = unavailable
                    ? "<red>货币兑换未启用，或 Wallet 尚未就绪。</red>"
                    : "<gray>没有可用的兑换方向。</gray>";
            holder.getInventory().setItem(22, uiItems.create(
                    unavailable ? Material.RED_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    UiItems.FILLER, msg(key, fallback), List.of(), false));
        }
        int slot = 0;
        for (ExchangePair pair : page.items()) {
            if (slot >= CONTENT) {
                break;
            }
            Component name = msg("ui.exchange.pair-name", Map.of(
                    "from", pair.from().code(),
                    "to", pair.to().code(),
                    "from_name", pair.from().displayName(),
                    "to_name", pair.to().displayName()),
                    "<gold><bold>" + pair.from().displayName() + " → "
                            + pair.to().displayName() + "</bold></gold>");
            List<Component> lore = new ArrayList<>();
            lore.add(msg("ui.exchange.rate", Map.of(
                    "from", pair.from().code(),
                    "to", pair.to().code(),
                    "rate", pair.rateText()),
                    "<gray>1 " + pair.from().code() + " = " + pair.rateText()
                            + " " + pair.to().code() + "</gray>"));
            lore.add(msg("info.click-exchange", "<yellow>点击兑换</yellow>"));
            button(holder, slot, Material.GOLD_INGOT, UiItems.EXCHANGE_PAIR,
                    name, lore, false,
                    (p, h, clicked) -> shop.exchange().startExchange(p, pair));
            slot++;
        }
        navigation(holder, page, () -> openMain(player),
                () -> loadExchange(holder, page.page() - 1),
                () -> loadExchange(holder, page.page() + 1));
    }

    // ------------------------------------------------------------
    // 钱包流水（与 Wallet 流水页同口径）
    // ------------------------------------------------------------

    private void loadWalletHistory(ShopMenuHolder holder, int page) {
        holder.view().page(page);
        fill(holder);
        setLoading(holder);
        Player player = holder.player();
        shop.wallet().transactions(player.getUniqueId(), player.getName(), page + 1, PAGE_SIZE)
                .whenComplete((result, error) -> Scheduler.runSync(plugin, () -> {
                    if (!holder.isOpen()) {
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "读取 Wallet 流水失败", error);
                        renderError(holder);
                        return;
                    }
                    renderWalletHistory(holder, result, page);
                }));
    }

    private void renderWalletHistory(ShopMenuHolder holder, WalletTransactionPage result, int page) {
        Player player = holder.player();
        fill(holder);
        List<WalletTransaction> records = result == null ? List.of() : result.records();
        if (records.isEmpty()) {
            holder.getInventory().setItem(22, uiItems.create(Material.PAPER,
                    UiItems.FILLER, msg("info.wallet-history-empty", "<gray>暂无流水。</gray>"),
                    List.of(), false));
        }
        int slot = 0;
        for (WalletTransaction transaction : records) {
            if (slot >= CONTENT) {
                break;
            }
            holder.getInventory().setItem(slot++, transactionItem(transaction));
        }

        int total = result == null ? 0 : (int) Math.min(Integer.MAX_VALUE, result.total());
        PageResult<WalletTransaction> pageResult = new PageResult<>(records, total, page, PAGE_SIZE);
        holder.view().page(pageResult.page());
        navigation(holder, pageResult, () -> openMain(player),
                () -> loadWalletHistory(holder, pageResult.page() - 1),
                () -> loadWalletHistory(holder, pageResult.page() + 1));
    }

    private ItemStack transactionItem(WalletTransaction transaction) {
        String type = transaction.type() == null ? "" : transaction.type().toUpperCase(Locale.ROOT);
        boolean credit = "CREDIT".equals(type) || "IN".equalsIgnoreCase(transaction.direction());
        boolean debit = "DEBIT".equals(type) || "OUT".equalsIgnoreCase(transaction.direction());
        String label = switch (type) {
            case "CREDIT" -> "入账";
            case "DEBIT" -> "出账";
            case "TRANSFER" -> "转账";
            default -> type.isBlank() ? "流水" : type;
        };
        String sign = credit ? "+" : debit ? "-" : "";
        NamedTextColor color = credit ? NamedTextColor.GREEN
                : debit ? NamedTextColor.RED : NamedTextColor.YELLOW;
        String assetCode = transaction.assetCode() == null ? "" : transaction.assetCode();
        Component name = Component.text(label + " " + sign
                        + shop.wallet().formatLikeWallet(assetCode, transaction.amount()), color)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);

        Optional<CurrencyAsset> asset = shop.wallet().asset(assetCode);
        String assetLabel = asset.map(value -> value.displayName() + "（" + value.code() + "）")
                .orElse(assetCode);
        List<Component> lore = new ArrayList<>();
        lore.add(line("币种：", assetLabel));
        lore.add(line("时间：", timeText(transaction.createdAt())));
        lore.add(line("余额：", shop.wallet().formatLikeWallet(assetCode, transaction.balanceAfter())));
        if (transaction.remark() != null && !transaction.remark().isBlank()) {
            lore.add(line("备注：", transaction.remark()));
        }
        if (transaction.businessNo() != null && !transaction.businessNo().isBlank()) {
            lore.add(plain("业务号：" + transaction.businessNo(), NamedTextColor.DARK_GRAY));
        }
        return uiItems.create(Material.PAPER, UiItems.WALLET_HISTORY, name, lore, false);
    }

    private String timeText(long epochMillis) {
        if (epochMillis <= 0L) {
            return "-";
        }
        return WALLET_TIME.withZone(shop.settings().timeZone())
                .format(Instant.ofEpochMilli(epochMillis));
    }

    private static Component line(String label, String value) {
        return Component.text(label, NamedTextColor.GRAY)
                .append(Component.text(value == null ? "" : value, NamedTextColor.WHITE))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ------------------------------------------------------------
    // 我的上架
    // ------------------------------------------------------------

    private void loadMyListings(ShopMenuHolder holder, int page) {
        holder.view().page(page);
        fill(holder);
        setLoading(holder);
        shop.myListingsPage(holder.player().getUniqueId(), page, PAGE_SIZE)
                .whenComplete((result, error) -> Scheduler.runSync(plugin, () -> {
                    if (!holder.isOpen()) {
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "加载我的上架失败", error);
                        renderError(holder);
                        return;
                    }
                    renderMyListings(holder, result);
                }));
    }

    private void renderMyListings(ShopMenuHolder holder, PageResult<PlayerListing> page) {
        Player player = holder.player();
        fill(holder);
        holder.view().page(page.page());
        if (page.items().isEmpty()) {
            holder.getInventory().setItem(22, uiItems.create(Material.GRAY_STAINED_GLASS_PANE,
                    UiItems.FILLER, msg("info.empty-listings", "<gray>你还没有上架任何物品。</gray>"),
                    List.of(), false));
        }
        int slot = 0;
        for (PlayerListing listing : page.items()) {
            if (slot >= CONTENT) {
                break;
            }
            ItemStack display = ItemCodec.unitItem(listing.itemData());
            String code = shop.wallet().resolveCode(listing.assetCode());
            List<Component> lore = new ArrayList<>();
            lore.add(msg("info.price-base", Map.of(
                    "price", shop.wallet().format(code, listing.unitPrice()),
                    "currency", shop.wallet().displayName(code),
                    "code", code),
                    "<gray>单价：<gold><price> <currency></gold></gray>"));
            lore.add(msg("info.listing-remaining", Map.of("quantity", Integer.toString(listing.quantity())),
                    "<gray>剩余：<white><quantity></white></gray>"));
            lore.add(msg("info.click-delist", "<yellow>点击下架</yellow>"));
            decorate(display, lore);
            setItem(holder, slot, display, (p, h, clicked) -> shop.requestDelist(p, listing.id()));
            slot++;
        }
        navigation(holder, page, () -> openPlayer(player, 0),
                () -> loadMyListings(holder, page.page() - 1),
                () -> loadMyListings(holder, page.page() + 1));
    }

    // ------------------------------------------------------------
    // 邮箱
    // ------------------------------------------------------------

    private void loadMailbox(ShopMenuHolder holder, int page) {
        holder.view().page(page);
        fill(holder);
        setLoading(holder);
        shop.mailboxPage(holder.player().getUniqueId(), page, PAGE_SIZE)
                .whenComplete((result, error) -> Scheduler.runSync(plugin, () -> {
                    if (!holder.isOpen()) {
                        return;
                    }
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "加载待领取失败", error);
                        renderError(holder);
                        return;
                    }
                    renderMailbox(holder, result);
                }));
    }

    private void renderMailbox(ShopMenuHolder holder, PageResult<MailboxEntry> page) {
        Player player = holder.player();
        fill(holder);
        holder.view().page(page.page());
        if (page.items().isEmpty()) {
            holder.getInventory().setItem(22, uiItems.create(Material.GRAY_STAINED_GLASS_PANE,
                    UiItems.FILLER, msg("info.empty-mailbox", "<gray>没有待领取物品。</gray>"),
                    List.of(), false));
        }
        int slot = 0;
        for (MailboxEntry entry : page.items()) {
            if (slot >= CONTENT) {
                break;
            }
            ItemStack display = ItemCodec.unitItem(entry.itemData());
            List<Component> lore = new ArrayList<>();
            lore.add(msg("info.listing-remaining", Map.of("quantity", Integer.toString(entry.quantity())),
                    "<gray>数量：<white><quantity></white></gray>"));
            lore.add(msg("info.click-claim", "<yellow>点击领取</yellow>"));
            decorate(display, lore);
            setItem(holder, slot, display, (p, h, clicked) -> claimAll(h));
            slot++;
        }
        navigation(holder, page, () -> openMain(player),
                () -> loadMailbox(holder, page.page() - 1),
                () -> loadMailbox(holder, page.page() + 1));
        button(holder, 51, Material.HOPPER, UiItems.MAIL_CLAIM,
                msg("ui.mail.claim-name", "<green><bold>领取全部</bold></green>"),
                List.of(msg("ui.mail.claim-lore", "<gray>把待领取物品放进背包</gray>")),
                true, (p, h, clicked) -> claimAll(h));
    }

    private void claimAll(ShopMenuHolder holder) {
        Player player = holder.player();
        shop.deliverMailbox(player).thenAccept(result -> Scheduler.runSync(plugin, () -> {
            if (result.delivered() > 0) {
                shop.messages().send(player, "success.delivered",
                        Map.of("amount", Integer.toString(result.delivered())), "已领取物品。");
            } else if (result.remaining() == 0) {
                shop.messages().send(player, "success.mailbox-empty", "没有待领取物品。");
            }
            if (result.remaining() > 0) {
                shop.messages().send(player, "info.mailbox-remaining",
                        Map.of("count", Integer.toString(result.remaining())), "背包已满。");
            }
            if (holder.isOpen()) {
                loadMailbox(holder, holder.view().page());
            }
        }));
    }

    // ------------------------------------------------------------
    // 管理
    // ------------------------------------------------------------

    private void renderAdminMain(ShopMenuHolder holder) {
        Player player = holder.player();
        fill(holder);
        button(holder, 20, Material.ANVIL, UiItems.ADMIN_ADD,
                msg("ui.admin.add-name", "<green><bold>上架官方商品</bold></green>"),
                List.of(msg("ui.admin.add-lore", "<gray>选择背包物品，随后输入单价</gray>")),
                true, (p, h, slot) -> openSelection(p, SelectionMode.ADMIN_ADD));
        button(holder, 22, Material.BOOK, UiItems.ADMIN_MANAGE,
                msg("ui.admin.manage-name", "<yellow><bold>管理官方商品</bold></yellow>"),
                List.of(msg("ui.admin.manage-lore", "<gray>调整价格、库存、限购和涨价规则</gray>")),
                true, (p, h, slot) -> openAdminManage(p, 0));
        button(holder, 45, Material.ARROW, UiItems.NAV_BACK,
                msg("ui.nav.back-name", "<yellow>返回</yellow>"), List.of(), false,
                (p, h, slot) -> openMain(p));
        button(holder, 53, Material.BARRIER, UiItems.NAV_CLOSE,
                msg("ui.nav.close-name", "<gray>返回主菜单</gray>"), List.of(), false,
                (p, h, slot) -> returnToMainMenu(p));
    }

    private void loadAdminManage(ShopMenuHolder holder, int page) {
        holder.view().page(page);
        fill(holder);
        setLoading(holder);
        shop.allOfficialPage(page, PAGE_SIZE).whenComplete((result, error) -> Scheduler.runSync(plugin, () -> {
            if (!holder.isOpen()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "加载官方商品管理失败", error);
                renderError(holder);
                return;
            }
            renderAdminManage(holder, result);
        }));
    }

    private void renderAdminManage(ShopMenuHolder holder, PageResult<OfficialItem> page) {
        Player player = holder.player();
        fill(holder);
        holder.view().page(page.page());
        if (page.items().isEmpty()) {
            holder.getInventory().setItem(22, uiItems.create(Material.GRAY_STAINED_GLASS_PANE,
                    UiItems.FILLER, msg("info.empty-official", "<gray>还没有官方商品。</gray>"),
                    List.of(), false));
        }
        int slot = 0;
        for (OfficialItem item : page.items()) {
            if (slot >= CONTENT) {
                break;
            }
            ItemStack display = ItemCodec.unitItem(item.itemData());
            String code = shop.wallet().resolveCode(item.assetCode());
            List<Component> lore = new ArrayList<>();
            lore.add(msg("info.price-base", Map.of(
                    "price", shop.wallet().format(code, item.basePrice()),
                    "currency", shop.wallet().displayName(code),
                    "code", code),
                    "<gray>单价：<gold><price> <currency></gold></gray>"));
            lore.add(stockLine(item));
            lore.add(limitLine(item));
            lore.add(growthLine(item));
            lore.add(msg("info.reset-period", Map.of("period", periodLabel(item.resetPeriod())),
                    "<gray>重置周期：<white><period></white></gray>"));
            lore.add(msg("info.state", Map.of("state", stateLabel(item.enabled())),
                    "<gray>状态：<white><state></white></gray>"));
            lore.add(msg("info.click-edit", "<yellow>点击编辑</yellow>"));
            decorate(display, lore);
            setItem(holder, slot, display, (p, h, clicked) -> openAdminEdit(p, item.id()));
            slot++;
        }
        navigation(holder, page, () -> openAdminMain(player),
                () -> loadAdminManage(holder, page.page() - 1),
                () -> loadAdminManage(holder, page.page() + 1));
    }

    private void loadAdminEdit(ShopMenuHolder holder, String itemId) {
        fill(holder);
        setLoading(holder);
        shop.officialItem(itemId).whenComplete((optional, error) -> Scheduler.runSync(plugin, () -> {
            if (!holder.isOpen()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "加载官方商品失败", error);
                renderError(holder);
                return;
            }
            if (optional.isEmpty()) {
                shop.messages().send(holder.player(), "error.item-not-found",
                        "<red>商品不存在或已删除。</red>");
                openAdminManage(holder.player(), 0);
                return;
            }
            renderAdminEdit(holder, optional.get());
        }));
    }

    private void renderAdminEdit(ShopMenuHolder holder, OfficialItem item) {
        Player player = holder.player();
        fill(holder);
        ItemStack preview = ItemCodec.unitItem(item.itemData());
        String code = shop.wallet().resolveCode(item.assetCode());
        List<Component> previewLore = new ArrayList<>();
        previewLore.add(msg("info.price-base", Map.of(
                "price", shop.wallet().format(code, item.basePrice()),
                "currency", shop.wallet().displayName(code),
                "code", code),
                "<gray>单价：<gold><price> <currency></gold></gray>"));
        previewLore.add(stockLine(item));
        previewLore.add(limitLine(item));
        previewLore.add(growthLine(item));
        previewLore.add(msg("info.reset-period", Map.of("period", periodLabel(item.resetPeriod())),
                "<gray>重置周期：<white><period></white></gray>"));
        previewLore.add(msg("info.state", Map.of("state", stateLabel(item.enabled())),
                "<gray>状态：<white><state></white></gray>"));
        decorate(preview, previewLore);
        setItem(holder, 4, preview, null);

        button(holder, 10, Material.GOLD_INGOT, UiItems.ADMIN_PRICE,
                msg("ui.admin.price-name", "<gold>修改单价</gold>"),
                List.of(msg("info.price-current", Map.of(
                        "price", shop.wallet().format(code, item.basePrice()),
                        "currency", shop.wallet().displayName(code),
                        "code", code),
                        "<gray>当前单价：<gold><price> <currency></gold></gray>")),
                false, (p, h, slot) -> shop.startAdminEdit(p, item.id(), InputType.ADMIN_EDIT_PRICE));
        button(holder, 12, Material.CHEST, UiItems.ADMIN_STOCK,
                msg("ui.admin.stock-name", "<gold>修改库存</gold>"),
                List.of(stockLine(item)), false,
                (p, h, slot) -> shop.startAdminEdit(p, item.id(), InputType.ADMIN_EDIT_STOCK));
        button(holder, 14, Material.BOOK, UiItems.ADMIN_LIMIT,
                msg("ui.admin.limit-name", "<gold>修改限购</gold>"),
                List.of(limitLine(item)), false,
                (p, h, slot) -> shop.startAdminEdit(p, item.id(), InputType.ADMIN_EDIT_LIMIT));
        button(holder, 16, Material.EXPERIENCE_BOTTLE, UiItems.ADMIN_GROWTH,
                msg("ui.admin.growth-name", "<gold>修改涨价百分比</gold>"),
                List.of(growthLine(item)), false,
                (p, h, slot) -> shop.startAdminEdit(p, item.id(), InputType.ADMIN_EDIT_GROWTH));
        String capText = item.hasPriceCap()
                ? shop.wallet().format(code, item.maxPrice()) + " " + shop.wallet().displayName(code)
                : "不限";
        button(holder, 24, Material.EMERALD, UiItems.ADMIN_CURRENCY,
                msg("ui.admin.currency-name", "<gold>修改货币</gold>"),
                List.of(msg("ui.admin.currency-lore", Map.of(
                        "currency", shop.wallet().displayName(item.assetCode()),
                        "code", shop.wallet().resolveCode(item.assetCode())),
                        "<gray>当前：<gold><currency></gold>（<code>）</gray>"),
                        msg("info.click-edit", "<yellow>点击切换</yellow>")),
                false, (p, h, slot) -> {
                    shop.stageEditCurrency(p, item.id());
                    openCurrencySelection(p, SelectionMode.ADMIN_EDIT_CURRENCY, item.id());
                });
        button(holder, 28, Material.EMERALD, UiItems.ADMIN_MAX_PRICE,
                msg("ui.admin.max-price-name", "<gold>修改单件最高价</gold>"),
                List.of(msg("info.price-cap", Map.of("cap", capText),
                        "<gray>单件最高价：<gold><cap></gold></gray>")),
                false, (p, h, slot) -> shop.startAdminEdit(p, item.id(), InputType.ADMIN_EDIT_MAX_PRICE));
        button(holder, 30, Material.CLOCK, UiItems.ADMIN_RESET,
                msg("ui.admin.reset-name", "<gold>切换重置周期</gold>"),
                List.of(msg("info.reset-period", Map.of("period", periodLabel(item.resetPeriod())),
                        "<gray>当前：<white><period></white></gray>"),
                        msg("info.click-edit", "<yellow>点击切换</yellow>")),
                false, (p, h, slot) -> shop.cycleResetPeriod(p, item.id())
                        .thenRun(() -> Scheduler.runSync(plugin, () -> {
                            if (holder.isOpen()) {
                                loadAdminEdit(holder, item.id());
                            }
                        })));
        button(holder, 32, item.enabled() ? Material.LIME_DYE : Material.RED_DYE,
                UiItems.ADMIN_ENABLED,
                msg("ui.admin.enabled-name", "<gold>切换启用状态</gold>"),
                List.of(msg("info.state", Map.of("state", stateLabel(item.enabled())),
                        "<gray>当前：<white><state></white></gray>"),
                        msg("info.click-edit", "<yellow>点击切换</yellow>")),
                item.enabled(), (p, h, slot) -> shop.toggleEnabled(p, item.id())
                        .thenRun(() -> Scheduler.runSync(plugin, () -> {
                            if (holder.isOpen()) {
                                loadAdminEdit(holder, item.id());
                            }
                        })));
        button(holder, 40, Material.BARRIER, UiItems.ADMIN_DELETE,
                msg("ui.admin.delete-name", "<red>删除商品</red>"),
                List.of(msg("info.click-edit", "<yellow>点击删除</yellow>")),
                false, (p, h, slot) -> shop.requestDeleteOfficial(p, item));
        button(holder, 45, Material.ARROW, UiItems.NAV_BACK,
                msg("ui.nav.back-name", "<yellow>返回</yellow>"), List.of(), false,
                (p, h, slot) -> openAdminManage(p, 0));
        button(holder, 53, Material.BARRIER, UiItems.NAV_CLOSE,
                msg("ui.nav.close-name", "<gray>返回主菜单</gray>"), List.of(), false,
                (p, h, slot) -> returnToMainMenu(p));
    }

    // ------------------------------------------------------------
    // 选择物品
    // ------------------------------------------------------------

    private void renderSelection(ShopMenuHolder holder) {
        Player player = holder.player();
        clear(holder);
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (ItemCodec.isEmpty(item)) {
                continue;
            }
            ItemStack display = item.clone();
            setItem(holder, slot, display, (p, h, clicked) -> selectItem(h, clicked));
        }
        ItemStack filler = uiItems.create(Material.GRAY_STAINED_GLASS_PANE, UiItems.FILLER,
                Component.text(" "), List.of(), false);
        for (int slot = 36; slot < SIZE; slot++) {
            holder.getInventory().setItem(slot, filler);
        }
        button(holder, 45, Material.ARROW, UiItems.NAV_BACK,
                msg("ui.nav.back-name", "<yellow>返回</yellow>"), List.of(), false,
                (p, h, slot) -> {
                    SelectionMode mode = h.view().selectionMode();
                    if (mode == SelectionMode.ADMIN_ADD) {
                        openAdminMain(p);
                    } else {
                        openPlayer(p, 0);
                    }
                });
        button(holder, 53, Material.BARRIER, UiItems.NAV_CLOSE,
                msg("ui.nav.close-name", "<gray>返回主菜单</gray>"), List.of(), false,
                (p, h, slot) -> returnToMainMenu(p));
    }

    private void selectItem(ShopMenuHolder holder, int slot) {
        Player player = holder.player();
        ItemStack item = player.getInventory().getItem(slot);
        if (ItemCodec.isEmpty(item)) {
            return;
        }
        if (uiItems.isUiItem(item)) {
            shop.messages().send(player, "error.ui-item", "<red>商店菜单物品不能上架。</red>");
            return;
        }
        SelectionMode mode = holder.view().selectionMode();
        if (shop.stageSelection(player, item, mode)) {
            openCurrencySelection(player, mode);
        }
    }

    // ------------------------------------------------------------
    // 选择货币（Wallet 官网资产）
    // ------------------------------------------------------------

    private void loadCurrencySelection(ShopMenuHolder holder) {
        fill(holder);
        setLoading(holder);
        shop.wallet().assets(false).whenComplete((assets, error) -> Scheduler.runSync(plugin, () -> {
            if (!holder.isOpen()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "加载 Wallet 货币列表失败", error);
                renderError(holder);
                return;
            }
            renderCurrencySelection(holder, assets);
        }));
    }

    private void renderCurrencySelection(ShopMenuHolder holder, List<CurrencyAsset> assets) {
        Player player = holder.player();
        fill(holder);
        if (assets.isEmpty()) {
            holder.getInventory().setItem(22, uiItems.create(Material.RED_STAINED_GLASS_PANE,
                    UiItems.FILLER, msg("ui.currency.empty",
                            "<red>Wallet 没有官网启用的货币，暂时无法上架。</red>"),
                    List.of(), false));
        }
        int slot = 0;
        for (CurrencyAsset asset : assets) {
            if (slot >= CONTENT) {
                break;
            }
            List<Component> lore = List.of(
                    msg("ui.currency.entry-lore", Map.of(
                            "name", asset.displayName(),
                            "code", asset.code(),
                            "symbol", asset.symbolOrCode(),
                            "scale", Integer.toString(asset.scale())),
                            "<gray>官网货币：<white><name></white>（<code>），精度 <scale></gray>"),
                    msg("info.click-select", "<yellow>点击选择</yellow>"));
            button(holder, slot, asset.money() ? Material.GOLD_INGOT : Material.EMERALD,
                    UiItems.CURRENCY_ENTRY,
                    msg("ui.currency.entry-name", Map.of(
                            "name", asset.displayName(),
                            "code", asset.code()),
                            "<gold><bold><name></bold></gold> <gray>(<code>)</gray>"),
                    lore, false, (p, h, clicked) -> shop.chooseCurrency(p, asset.code()));
            slot++;
        }
        SelectionMode mode = holder.view().selectionMode();
        button(holder, 45, Material.ARROW, UiItems.NAV_BACK,
                msg("ui.nav.back-name", "<yellow>返回</yellow>"), List.of(), false,
                (p, h, slot1) -> backFromCurrency(p, mode, holder.view().contextId()));
        button(holder, 53, Material.BARRIER, UiItems.NAV_CLOSE,
                msg("ui.nav.close-name", "<gray>返回主菜单</gray>"), List.of(), false,
                (p, h, slot1) -> returnToMainMenu(p));
    }

    private void backFromCurrency(Player player, SelectionMode mode, String contextId) {
        if (mode == SelectionMode.ADMIN_ADD) {
            openAdminMain(player);
        } else if (mode == SelectionMode.ADMIN_EDIT_CURRENCY) {
            openAdminEdit(player, contextId);
        } else {
            openPlayer(player, 0);
        }
    }

    // ------------------------------------------------------------
    // 翻页/通用
    // ------------------------------------------------------------

    private void navigation(ShopMenuHolder holder,
                            PageResult<?> page,
                            Runnable back,
                            Runnable previous,
                            Runnable next) {
        button(holder, 45, Material.ARROW, UiItems.NAV_BACK,
                msg("ui.nav.back-name", "<yellow>返回</yellow>"), List.of(), false,
                (p, h, slot) -> back.run());
        if (page.hasPrevious()) {
            button(holder, 48, Material.ARROW, UiItems.NAV_PREV,
                    msg("ui.nav.prev-name", "<yellow>上一页</yellow>"), List.of(), false,
                    (p, h, slot) -> previous.run());
        } else {
            button(holder, 48, Material.GRAY_STAINED_GLASS_PANE, UiItems.NAV_PREV,
                    msg("ui.nav.prev-name", "<dark_gray>上一页</dark_gray>"),
                    List.of(msg("ui.nav.prev-lore", "<gray>没有上一页</gray>")), false, null);
        }
        button(holder, 49, Material.PAPER, UiItems.NAV_PAGE,
                msg("info.page", Map.of(
                        "page", Integer.toString(page.page() + 1),
                        "pages", Integer.toString(page.pages())),
                        "<gray>第 <white><page></white>/<white><pages></white> 页</gray>"),
                List.of(), false, null);
        if (page.hasNext()) {
            button(holder, 50, Material.ARROW, UiItems.NAV_NEXT,
                    msg("ui.nav.next-name", "<yellow>下一页</yellow>"), List.of(), false,
                    (p, h, slot) -> next.run());
        } else {
            button(holder, 50, Material.GRAY_STAINED_GLASS_PANE, UiItems.NAV_NEXT,
                    msg("ui.nav.next-name", "<dark_gray>下一页</dark_gray>"),
                    List.of(msg("ui.nav.next-lore", "<gray>没有下一页</gray>")), false, null);
        }
        button(holder, 53, Material.BARRIER, UiItems.NAV_CLOSE,
                msg("ui.nav.close-name", "<gray>返回主菜单</gray>"), List.of(), false,
                (p, h, slot) -> returnToMainMenu(p));
    }

    private void renderError(ShopMenuHolder holder) {
        fill(holder);
        holder.getInventory().setItem(22, uiItems.create(Material.RED_STAINED_GLASS_PANE,
                UiItems.FILLER, msg("error.database", "<red>商店数据读取失败。</red>"),
                List.of(), false));
        button(holder, 53, Material.BARRIER, UiItems.NAV_CLOSE,
                msg("ui.nav.close-name", "<gray>返回主菜单</gray>"), List.of(), false,
                (p, h, slot) -> returnToMainMenu(p));
    }

    private Component stockLine(OfficialItem item) {
        if (item.unlimitedStock()) {
            return msg("info.stock-unlimited", "<gray>库存：<white>无限</white></gray>");
        }
        return msg("info.stock-finite", Map.of("stock", Integer.toString(Math.max(0, item.stock()))),
                "<gray>库存：<white><stock></white></gray>");
    }

    private Component limitLine(OfficialItem item) {
        if (item.unlimitedPerPlayer()) {
            return msg("info.limit-unlimited", "<gray>限购：<white>不限</white></gray>");
        }
        return msg("info.limit-finite", Map.of(
                "limit", Integer.toString(item.perPlayerLimit()),
                "period", periodLabel(item.resetPeriod())),
                "<gray>限购：<white><limit></white> 件/<period></gray>");
    }

    private Component growthLine(OfficialItem item) {
        if (!item.hasPriceGrowth()) {
            return msg("info.growth-off", "<gray>涨价：<white>关闭</white></gray>");
        }
        Component line = msg("info.growth-on", Map.of(
                "percent", Amounts.format(item.growthPercent(), 2),
                "cap", ""), "<gray>涨价：每件 +<percent>%</gray>");
        if (item.hasPriceCap()) {
            line = line.append(msg("info.growth-cap", Map.of(
                    "cap", shop.wallet().format(item.assetCode(), item.maxPrice()),
                    "currency", shop.wallet().displayName(item.assetCode()),
                    "code", shop.wallet().resolveCode(item.assetCode())),
                    "<gray>，单件上限 <gold><cap> <currency></gold></gray>"));
        }
        return line;
    }

    private String periodLabel(ResetPeriod period) {
        String fallback = switch (period) {
            case NEVER -> "永不";
            case DAILY -> "每天";
            case WEEKLY -> "每周";
            case MONTHLY -> "每月";
        };
        return raw("period." + period.name().toLowerCase(Locale.ROOT), fallback);
    }

    private String stateLabel(boolean enabled) {
        return raw(enabled ? "state.enabled" : "state.disabled", enabled ? "启用" : "停用");
    }

    private void decorate(ItemStack display, List<Component> extraLore) {
        ItemMeta meta = display.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) {
            lore.addAll(meta.lore());
        }
        lore.addAll(extraLore.stream().map(UiItems::noItalic).toList());
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        display.setItemMeta(meta);
    }

    public void refresh(ShopMenuHolder holder) {
        switch (holder.view().type()) {
            case MAIN -> renderMain(holder);
            case OFFICIAL -> loadOfficial(holder, holder.view().page());
            case PLAYER -> loadPlayer(holder, holder.view().page());
            case EXCHANGE -> loadExchange(holder, holder.view().page());
            case WALLET_HISTORY -> loadWalletHistory(holder, holder.view().page());
            case MY_LISTINGS -> loadMyListings(holder, holder.view().page());
            case MAILBOX -> loadMailbox(holder, holder.view().page());
            case ADMIN_MAIN -> renderAdminMain(holder);
            case ADMIN_MANAGE -> loadAdminManage(holder, holder.view().page());
            case ADMIN_EDIT -> loadAdminEdit(holder, holder.view().contextId());
            case SELECT_ITEM -> renderSelection(holder);
            case SELECT_CURRENCY -> loadCurrencySelection(holder);
        }
    }

    public boolean isAllowed(Player player) {
        return allowed(player);
    }

    public void playClick(Player player) {
        clickSound(player);
    }
}
