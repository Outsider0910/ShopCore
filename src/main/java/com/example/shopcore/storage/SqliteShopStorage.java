package com.example.shopcore.storage;

import com.example.shopcore.economy.CurrencyLookup;
import com.example.shopcore.exchange.ExchangeOrder;
import com.example.shopcore.exchange.ExchangeStatus;
import com.example.shopcore.model.MailboxEntry;
import com.example.shopcore.model.OfficialItem;
import com.example.shopcore.model.OfficialQuoteData;
import com.example.shopcore.model.OfficialReservation;
import com.example.shopcore.model.OperationResult;
import com.example.shopcore.model.Order;
import com.example.shopcore.model.OrderStatus;
import com.example.shopcore.model.PlayerListing;
import com.example.shopcore.model.PlayerQuoteData;
import com.example.shopcore.model.PlayerReservation;
import com.example.shopcore.model.PurchaseState;
import com.example.shopcore.service.PriceCalculator;
import com.example.shopcore.util.Amounts;
import com.example.shopcore.util.ResetPeriod;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQLite 持久化。
 *
 * <p>所有数据库访问都排进单线程 executor：SQLite 连接不跨线程使用，
 * 商店内的库存/寄售数量变更也自然串行，避免超卖。</p>
 */
public final class SqliteShopStorage implements AutoCloseable {

    private static final int SCHEMA_VERSION = 2;

    private final Path dbPath;
    private final Connection connection;
    private final ExecutorService executor;
    private final CurrencyLookup currencies;
    private final ZoneId resetZone;

    public SqliteShopStorage(Path dbPath, CurrencyLookup currencies, ZoneId resetZone) throws SQLException {
        this.dbPath = dbPath.toAbsolutePath();
        this.currencies = currencies;
        this.resetZone = resetZone == null ? ZoneId.systemDefault() : resetZone;
        try {
            if (this.dbPath.getParent() != null) {
                Files.createDirectories(this.dbPath.getParent());
            }
            Class.forName("org.sqlite.JDBC");
        } catch (Exception ex) {
            throw new SQLException("无法加载 SQLite 驱动", ex);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbPath);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ShopCore-DB");
            thread.setDaemon(true);
            return thread;
        });
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        connection.setAutoCommit(true);
        String[] ddl = {
                """
                CREATE TABLE IF NOT EXISTS official_items (
                    id TEXT PRIMARY KEY,
                    asset_code TEXT NOT NULL DEFAULT '',
                    item_data BLOB NOT NULL,
                    base_price TEXT NOT NULL,
                    stock INTEGER NOT NULL DEFAULT -1,
                    per_player_limit INTEGER NOT NULL DEFAULT 0,
                    growth_percent TEXT NOT NULL DEFAULT '0',
                    max_price TEXT NOT NULL DEFAULT '0',
                    reset_period TEXT NOT NULL DEFAULT 'NEVER',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS player_listings (
                    id TEXT PRIMARY KEY,
                    asset_code TEXT NOT NULL DEFAULT '',
                    seller_uuid TEXT NOT NULL,
                    seller_name TEXT NOT NULL,
                    item_data BLOB NOT NULL,
                    unit_price TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_listings_seller ON player_listings(seller_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_listings_created ON player_listings(created_at)",
                """
                CREATE TABLE IF NOT EXISTS official_purchases (
                    player_uuid TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    purchase_count INTEGER NOT NULL DEFAULT 0,
                    window_start INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (player_uuid, item_id)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS mailbox (
                    id TEXT PRIMARY KEY,
                    player_uuid TEXT NOT NULL,
                    item_data BLOB NOT NULL,
                    quantity INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_mailbox_player ON mailbox(player_uuid)",
                """
                CREATE TABLE IF NOT EXISTS orders (
                    id TEXT PRIMARY KEY,
                    business_no TEXT NOT NULL UNIQUE,
                    asset_code TEXT NOT NULL DEFAULT '',
                    buyer_uuid TEXT NOT NULL,
                    seller_uuid TEXT,
                    listing_id TEXT,
                    official_item_id TEXT,
                    item_data BLOB NOT NULL,
                    quantity INTEGER NOT NULL,
                    unit_price TEXT NOT NULL,
                    total_price TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status)",
                "CREATE INDEX IF NOT EXISTS idx_orders_buyer ON orders(buyer_uuid)",
                """
                CREATE TABLE IF NOT EXISTS exchange_orders (
                    id TEXT PRIMARY KEY,
                    business_no TEXT NOT NULL UNIQUE,
                    player_uuid TEXT NOT NULL,
                    from_code TEXT NOT NULL,
                    to_code TEXT NOT NULL,
                    source_amount TEXT NOT NULL,
                    target_amount TEXT NOT NULL,
                    status TEXT NOT NULL,
                    message TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_exchange_status ON exchange_orders(status)",
                "CREATE INDEX IF NOT EXISTS idx_exchange_player ON exchange_orders(player_uuid)",
                """
                CREATE TABLE IF NOT EXISTS meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """
        };
        for (String sql : ddl) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
        // 旧库升级：Beta_0_2_0 之前没有 asset_code，空值在运行期解析为官网第一个启用货币。
        ensureColumn("official_items", "asset_code", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("player_listings", "asset_code", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("orders", "asset_code", "TEXT NOT NULL DEFAULT ''");
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO meta(key, value) VALUES('schema_version', ?)")) {
            ps.setString(1, Integer.toString(SCHEMA_VERSION));
            ps.executeUpdate();
        }
    }

    private void ensureColumn(String table, String column, String definition) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    public Path dbPath() {
        return dbPath;
    }

    public CompletableFuture<Void> warmUp() {
        return submit(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
            return null;
        });
    }

    // ------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------

    public CompletableFuture<List<OfficialItem>> listOfficialItems(boolean enabledOnly) {
        return submit(() -> {
            String sql = "SELECT * FROM official_items"
                    + (enabledOnly ? " WHERE enabled=1" : "")
                    + " ORDER BY created_at ASC, id ASC";
            List<OfficialItem> items = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql)) {
                while (rs.next()) {
                    items.add(mapOfficialItem(rs));
                }
            }
            return items;
        });
    }

    public CompletableFuture<Optional<OfficialItem>> getOfficialItem(String id) {
        return submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM official_items WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapOfficialItem(rs));
                }
            }
        });
    }

    public CompletableFuture<Integer> countPlayerListings() {
        return submit(() -> count("SELECT COUNT(*) FROM player_listings WHERE quantity > 0"));
    }

    public CompletableFuture<List<PlayerListing>> listPlayerListings(int offset, int limit) {
        return submit(() -> {
            List<PlayerListing> listings = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM player_listings WHERE quantity > 0"
                            + " ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?")) {
                ps.setInt(1, Math.max(0, limit));
                ps.setInt(2, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        listings.add(mapListing(rs));
                    }
                }
            }
            return listings;
        });
    }

    public CompletableFuture<Integer> countListingsBySeller(UUID seller) {
        return submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM player_listings WHERE seller_uuid=? AND quantity > 0")) {
                ps.setString(1, seller.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public CompletableFuture<List<PlayerListing>> listListingsBySeller(UUID seller, int offset, int limit) {
        return submit(() -> {
            List<PlayerListing> listings = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM player_listings WHERE seller_uuid=? AND quantity > 0"
                            + " ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?")) {
                ps.setString(1, seller.toString());
                ps.setInt(2, Math.max(0, limit));
                ps.setInt(3, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        listings.add(mapListing(rs));
                    }
                }
            }
            return listings;
        });
    }

    public CompletableFuture<Optional<PlayerListing>> getPlayerListing(String id) {
        return submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM player_listings WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapListing(rs));
                }
            }
        });
    }

    public CompletableFuture<Integer> countMailbox(UUID player) {
        return submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM mailbox WHERE player_uuid=?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public CompletableFuture<List<MailboxEntry>> listMailbox(UUID player, int offset, int limit) {
        return submit(() -> {
            List<MailboxEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM mailbox WHERE player_uuid=?"
                            + " ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?")) {
                ps.setString(1, player.toString());
                ps.setInt(2, Math.max(0, limit));
                ps.setInt(3, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(mapMailbox(rs));
                    }
                }
            }
            return entries;
        });
    }

    public CompletableFuture<List<MailboxEntry>> listAllMailbox(UUID player) {
        return submit(() -> {
            List<MailboxEntry> entries = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM mailbox WHERE player_uuid=? ORDER BY created_at ASC, id ASC")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(mapMailbox(rs));
                    }
                }
            }
            return entries;
        });
    }

    public CompletableFuture<Optional<Order>> getOrder(String orderId) {
        return submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM orders WHERE id=?")) {
                ps.setString(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapOrder(rs));
                }
            }
        });
    }

    public CompletableFuture<Optional<Order>> getOrderByBusinessNo(String businessNo) {
        return submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM orders WHERE business_no=?")) {
                ps.setString(1, businessNo);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapOrder(rs));
                }
            }
        });
    }

    public CompletableFuture<List<Order>> listUnsettledOrders(int limit) {
        return submit(() -> {
            List<Order> orders = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM orders WHERE status IN ('PENDING','UNKNOWN')"
                            + " ORDER BY created_at ASC LIMIT ?")) {
                ps.setInt(1, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        orders.add(mapOrder(rs));
                    }
                }
            }
            return orders;
        });
    }

    // ------------------------------------------------------------
    // 官方商品维护
    // ------------------------------------------------------------

    public CompletableFuture<Void> insertOfficialItem(OfficialItem item) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO official_items(id,asset_code,item_data,base_price,stock,per_player_limit,"
                            + "growth_percent,max_price,reset_period,enabled,created_at,updated_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                bindOfficialItemInsert(ps, item);
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> updateOfficialItem(OfficialItem item) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE official_items SET asset_code=?,item_data=?,base_price=?,stock=?,per_player_limit=?,"
                            + "growth_percent=?,max_price=?,reset_period=?,enabled=?,updated_at=? WHERE id=?")) {
                bindOfficialItemUpdate(ps, item);
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> deleteOfficialItem(String id) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM official_items WHERE id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------
    // 玩家寄售
    // ------------------------------------------------------------

    /** 上架：检查和插入在同一个事务里，避免并发上架绕过笔数上限。 */
    public CompletableFuture<OperationResult<PlayerListing>> insertPlayerListing(PlayerListing listing,
                                                                                 int maxListings) {
        return submit(() -> inTransaction(conn -> {
            int active;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM player_listings WHERE seller_uuid=? AND quantity>0")) {
                ps.setString(1, listing.sellerUuid().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    active = rs.next() ? rs.getInt(1) : 0;
                }
            }
            if (active >= maxListings) {
                return OperationResult.fail("error.max-listings",
                        Map.of("limit", Integer.toString(maxListings)),
                        "你的寄售数量已达上限。");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_listings(id,asset_code,seller_uuid,seller_name,item_data,unit_price,"
                            + "quantity,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, listing.id());
                ps.setString(2, currencies.resolveCode(listing.assetCode()));
                ps.setString(3, listing.sellerUuid().toString());
                ps.setString(4, listing.sellerName());
                ps.setBytes(5, listing.itemData());
                ps.setString(6, listing.unitPrice().toPlainString());
                ps.setInt(7, listing.quantity());
                ps.setLong(8, listing.createdAt());
                ps.setLong(9, listing.updatedAt());
                ps.executeUpdate();
            }
            return OperationResult.ok(listing);
        }));
    }

    /** 下架：删除寄售并把物品放进卖家待领取，两步在同一个事务里。 */
    public CompletableFuture<OperationResult<PlayerListing>> removePlayerListing(String id, UUID owner) {
        return submit(() -> inTransaction(conn -> {
            PlayerListing listing;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM player_listings WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return OperationResult.fail("error.listing-not-found",
                                "该寄售不存在或已被下架。");
                    }
                    listing = mapListing(rs);
                }
            }
            if (!listing.sellerUuid().equals(owner)) {
                return OperationResult.fail("error.listing-not-found",
                        "该寄售不存在或已被下架。");
            }
            if (listing.quantity() <= 0) {
                return OperationResult.fail("error.listing-not-found",
                        "该寄售已售罄。");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM player_listings WHERE id=? AND seller_uuid=? AND quantity>0")) {
                ps.setString(1, id);
                ps.setString(2, owner.toString());
                if (ps.executeUpdate() != 1) {
                    return OperationResult.fail("error.listing-not-found",
                            "该寄售不存在或已被下架。");
                }
            }
            insertMailbox(conn, owner, listing.itemData(), listing.quantity(), System.currentTimeMillis());
            return OperationResult.ok(listing);
        }));
    }

    // ------------------------------------------------------------
    // 报价与下单占位
    // ------------------------------------------------------------

    public CompletableFuture<OperationResult<OfficialQuoteData>> quoteOfficialPurchase(String itemId,
                                                                                      UUID buyer,
                                                                                      int quantity,
                                                                                      long now) {
        return submit(() -> inTransaction(conn -> {
            if (quantity <= 0) {
                return OperationResult.fail("error.invalid-input", "数量必须大于 0。");
            }
            Optional<OfficialItem> itemOpt = selectOfficialItem(conn, itemId);
            if (itemOpt.isEmpty()) {
                return OperationResult.fail("error.item-not-found", "商品不存在或已下架。");
            }
            OfficialItem item = itemOpt.get();
            if (!item.enabled()) {
                return OperationResult.fail("error.disabled-item", "该商品已被管理员停用。");
            }
            if (hasPendingOrder(conn, buyer, "official_item_id", itemId)) {
                return OperationResult.fail("error.order-pending", "该商品有订单正在处理，请稍后再试。");
            }
            PurchaseState state = loadPurchaseState(conn, buyer, itemId, item.resetPeriod(), now);
            if (!item.unlimitedPerPlayer()
                    && state.purchased() + (long) quantity > item.perPlayerLimit()) {
                return OperationResult.fail("error.limit-reached",
                        Map.of("limit", Integer.toString(item.perPlayerLimit())),
                        "你已达到该商品的限购数量。");
            }
            if (!item.unlimitedStock() && item.stock() < quantity) {
                return OperationResult.fail("error.sold-out",
                        Map.of("stock", Integer.toString(Math.max(0, item.stock()))),
                        "库存不足。");
            }
            PriceCalculator.Quote quote = PriceCalculator.quote(
                    item.basePrice(), item.growthPercent(), item.maxPrice(),
                    state.purchased(), quantity, currencies.scaleOf(item.assetCode()));
            return OperationResult.ok(new OfficialQuoteData(item, quote));
        }));
    }

    public CompletableFuture<OperationResult<OfficialReservation>> reserveOfficialPurchase(String itemId,
                                                                                           UUID buyer,
                                                                                           int quantity,
                                                                                           String serverId,
                                                                                           long now) {
        return submit(() -> inTransaction(conn -> {
            if (quantity <= 0) {
                return OperationResult.fail("error.invalid-input", "数量必须大于 0。");
            }
            Optional<OfficialItem> itemOpt = selectOfficialItem(conn, itemId);
            if (itemOpt.isEmpty()) {
                return OperationResult.fail("error.item-not-found", "商品不存在或已下架。");
            }
            OfficialItem item = itemOpt.get();
            if (!item.enabled()) {
                return OperationResult.fail("error.disabled-item", "该商品已被管理员停用。");
            }
            if (hasPendingOrder(conn, buyer, "official_item_id", itemId)) {
                return OperationResult.fail("error.order-pending", "该商品有订单正在处理，请稍后再试。");
            }
            PurchaseState state = loadPurchaseState(conn, buyer, itemId, item.resetPeriod(), now);
            if (!item.unlimitedPerPlayer()
                    && state.purchased() + (long) quantity > item.perPlayerLimit()) {
                return OperationResult.fail("error.limit-reached",
                        Map.of("limit", Integer.toString(item.perPlayerLimit())),
                        "你已达到该商品的限购数量。");
            }
            if (!item.unlimitedStock() && item.stock() < quantity) {
                return OperationResult.fail("error.sold-out",
                        Map.of("stock", Integer.toString(Math.max(0, item.stock()))),
                        "库存不足。");
            }
            PriceCalculator.Quote quote = PriceCalculator.quote(
                    item.basePrice(), item.growthPercent(), item.maxPrice(),
                    state.purchased(), quantity, currencies.scaleOf(item.assetCode()));

            if (!item.unlimitedStock()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE official_items SET stock=stock-?, updated_at=? WHERE id=? AND stock>=?")) {
                    ps.setInt(1, quantity);
                    ps.setLong(2, now);
                    ps.setString(3, itemId);
                    ps.setInt(4, quantity);
                    if (ps.executeUpdate() != 1) {
                        return OperationResult.fail("error.sold-out",
                                Map.of("stock", "0"), "库存不足。");
                    }
                }
            }

            String orderId = UUID.randomUUID().toString();
            String businessNo = "mc:" + serverId + ":shop:" + orderId;
            Order order = new Order(
                    orderId, businessNo, currencies.resolveCode(item.assetCode()),
                    buyer, null, null, itemId,
                    item.itemData(), quantity, quote.firstUnitPrice(), quote.totalPrice(),
                    OrderStatus.PENDING, now, now);
            insertOrder(conn, order);
            return OperationResult.ok(new OfficialReservation(order, item, quote));
        }));
    }

    public CompletableFuture<OperationResult<PlayerQuoteData>> quotePlayerPurchase(String listingId,
                                                                                    UUID buyer,
                                                                                    int quantity) {
        return submit(() -> {
            if (quantity <= 0) {
                return OperationResult.fail("error.invalid-input", "数量必须大于 0。");
            }
            Optional<PlayerListing> listingOpt;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM player_listings WHERE id=?")) {
                ps.setString(1, listingId);
                try (ResultSet rs = ps.executeQuery()) {
                    listingOpt = rs.next() ? Optional.of(mapListing(rs)) : Optional.empty();
                }
            }
            if (listingOpt.isEmpty() || !listingOpt.get().active()) {
                return OperationResult.fail("error.listing-not-found", "该寄售不存在或已售罄。");
            }
            PlayerListing listing = listingOpt.get();
            if (listing.sellerUuid().equals(buyer)) {
                return OperationResult.fail("error.own-listing", "不能购买自己上架的物品。");
            }
            if (listing.quantity() < quantity) {
                return OperationResult.fail("error.not-enough-listing",
                        Map.of("stock", Integer.toString(listing.quantity())),
                        "该寄售剩余数量不足。");
            }
            BigDecimal total = Amounts.scale(
                    listing.unitPrice().multiply(BigDecimal.valueOf(quantity)),
                    currencies.scaleOf(listing.assetCode()));
            return OperationResult.ok(new PlayerQuoteData(listing, quantity, total));
        });
    }

    public CompletableFuture<OperationResult<PlayerReservation>> reservePlayerPurchase(String listingId,
                                                                                         UUID buyer,
                                                                                         int quantity,
                                                                                         String serverId,
                                                                                         long now) {
        return submit(() -> inTransaction(conn -> {
            if (quantity <= 0) {
                return OperationResult.fail("error.invalid-input", "数量必须大于 0。");
            }
            PlayerListing listing;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM player_listings WHERE id=?")) {
                ps.setString(1, listingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return OperationResult.fail("error.listing-not-found",
                                "该寄售不存在或已售罄。");
                    }
                    listing = mapListing(rs);
                }
            }
            if (!listing.active()) {
                return OperationResult.fail("error.listing-not-found", "该寄售已售罄。");
            }
            if (listing.sellerUuid().equals(buyer)) {
                return OperationResult.fail("error.own-listing", "不能购买自己上架的物品。");
            }
            if (hasPendingOrder(conn, buyer, "listing_id", listingId)) {
                return OperationResult.fail("error.order-pending", "该寄售有订单正在处理，请稍后再试。");
            }
            if (listing.quantity() < quantity) {
                return OperationResult.fail("error.not-enough-listing",
                        Map.of("stock", Integer.toString(listing.quantity())),
                        "该寄售剩余数量不足。");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE player_listings SET quantity=quantity-?, updated_at=?"
                            + " WHERE id=? AND quantity>=?")) {
                ps.setInt(1, quantity);
                ps.setLong(2, now);
                ps.setString(3, listingId);
                ps.setInt(4, quantity);
                if (ps.executeUpdate() != 1) {
                    return OperationResult.fail("error.listing-not-found", "该寄售已售罄。");
                }
            }
            BigDecimal total = Amounts.scale(
                    listing.unitPrice().multiply(BigDecimal.valueOf(quantity)),
                    currencies.scaleOf(listing.assetCode()));
            String orderId = UUID.randomUUID().toString();
            String businessNo = "mc:" + serverId + ":shop:" + orderId;
            Order order = new Order(
                    orderId, businessNo, currencies.resolveCode(listing.assetCode()),
                    buyer, listing.sellerUuid(), listingId, null,
                    listing.itemData(), quantity, listing.unitPrice(), total,
                    OrderStatus.PENDING, now, now);
            insertOrder(conn, order);
            return OperationResult.ok(new PlayerReservation(order, listing));
        }));
    }

    // ------------------------------------------------------------
    // 订单收口
    // ------------------------------------------------------------

    public CompletableFuture<Void> finalizeOfficialOrder(String orderId) {
        return execute(() -> inTransaction(conn -> {
            Optional<Order> orderOpt = selectOrder(conn, orderId);
            if (orderOpt.isEmpty()) {
                return null;
            }
            Order order = orderOpt.get();
            if (order.status() != OrderStatus.PENDING && order.status() != OrderStatus.UNKNOWN) {
                return null;
            }
            insertMailbox(conn, order.buyerUuid(), order.itemData(), order.quantity(), System.currentTimeMillis());
            if (order.officialItemId() != null) {
                ResetPeriod period = ResetPeriod.NEVER;
                Optional<OfficialItem> itemOpt = selectOfficialItem(conn, order.officialItemId());
                if (itemOpt.isPresent()) {
                    period = itemOpt.get().resetPeriod();
                }
                PurchaseState state = loadPurchaseState(
                        conn, order.buyerUuid(), order.officialItemId(), period, System.currentTimeMillis());
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO official_purchases(player_uuid,item_id,purchase_count,window_start)"
                                + " VALUES(?,?,?,?) ON CONFLICT(player_uuid,item_id) DO UPDATE SET"
                                + " purchase_count=purchase_count+excluded.purchase_count,"
                                + " window_start=excluded.window_start")) {
                    ps.setString(1, order.buyerUuid().toString());
                    ps.setString(2, order.officialItemId());
                    ps.setInt(3, order.quantity());
                    ps.setLong(4, state.windowStart());
                    ps.executeUpdate();
                }
            }
            updateOrderStatus(conn, orderId, OrderStatus.COMPLETED);
            return null;
        }));
    }

    public CompletableFuture<Void> cancelOfficialOrder(String orderId) {
        return execute(() -> inTransaction(conn -> {
            Optional<Order> orderOpt = selectOrder(conn, orderId);
            if (orderOpt.isEmpty()) {
                return null;
            }
            Order order = orderOpt.get();
            if (order.status() != OrderStatus.PENDING && order.status() != OrderStatus.UNKNOWN) {
                return null;
            }
            if (order.officialItemId() != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE official_items SET stock=stock+?, updated_at=?"
                                + " WHERE id=? AND stock>=0")) {
                    ps.setInt(1, order.quantity());
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, order.officialItemId());
                    ps.executeUpdate();
                }
            }
            updateOrderStatus(conn, orderId, OrderStatus.FAILED);
            return null;
        }));
    }

    public CompletableFuture<Void> finalizePlayerOrder(String orderId) {
        return execute(() -> inTransaction(conn -> {
            Optional<Order> orderOpt = selectOrder(conn, orderId);
            if (orderOpt.isEmpty()) {
                return null;
            }
            Order order = orderOpt.get();
            if (order.status() != OrderStatus.PENDING && order.status() != OrderStatus.UNKNOWN) {
                return null;
            }
            insertMailbox(conn, order.buyerUuid(), order.itemData(), order.quantity(), System.currentTimeMillis());
            updateOrderStatus(conn, orderId, OrderStatus.COMPLETED);
            return null;
        }));
    }

    public CompletableFuture<Void> cancelPlayerOrder(String orderId) {
        return execute(() -> inTransaction(conn -> {
            Optional<Order> orderOpt = selectOrder(conn, orderId);
            if (orderOpt.isEmpty()) {
                return null;
            }
            Order order = orderOpt.get();
            if (order.status() != OrderStatus.PENDING && order.status() != OrderStatus.UNKNOWN) {
                return null;
            }
            if (order.listingId() != null) {
                boolean restored;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE player_listings SET quantity=quantity+?, updated_at=? WHERE id=?")) {
                    ps.setInt(1, order.quantity());
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, order.listingId());
                    restored = ps.executeUpdate() == 1;
                }
                if (!restored && order.sellerUuid() != null) {
                    // 卖家在订单等待支付期间把剩余寄售下架了：退回数量改放卖家待领取。
                    insertMailbox(conn, order.sellerUuid(), order.itemData(),
                            order.quantity(), System.currentTimeMillis());
                }
            }
            updateOrderStatus(conn, orderId, OrderStatus.FAILED);
            return null;
        }));
    }

    public CompletableFuture<Void> markOrderUnknown(String orderId) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE orders SET status=?, updated_at=? WHERE id=? AND status=?")) {
                ps.setString(1, OrderStatus.UNKNOWN.name());
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, orderId);
                ps.setString(4, OrderStatus.PENDING.name());
                ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------
    // 货币兑换订单
    // ------------------------------------------------------------

    public CompletableFuture<Void> createExchangeOrder(ExchangeOrder order) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO exchange_orders(id,business_no,player_uuid,from_code,to_code,"
                            + "source_amount,target_amount,status,message,created_at,updated_at)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, order.id());
                ps.setString(2, order.businessNo());
                ps.setString(3, order.playerUuid().toString());
                ps.setString(4, order.fromCode());
                ps.setString(5, order.toCode());
                ps.setString(6, order.sourceAmount().toPlainString());
                ps.setString(7, order.targetAmount().toPlainString());
                ps.setString(8, order.status().name());
                ps.setString(9, order.message());
                ps.setLong(10, order.createdAt());
                ps.setLong(11, order.updatedAt());
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> updateExchangeOrderStatus(String id, ExchangeStatus status, String message) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE exchange_orders SET status=?, message=?, updated_at=? WHERE id=?")) {
                ps.setString(1, status.name());
                ps.setString(2, message);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, id);
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<List<ExchangeOrder>> listExchangeOrders(int limit) {
        return submit(() -> {
            List<ExchangeOrder> orders = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM exchange_orders ORDER BY created_at DESC LIMIT ?")) {
                ps.setInt(1, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        orders.add(mapExchangeOrder(rs));
                    }
                }
            }
            return orders;
        });
    }

    private ExchangeOrder mapExchangeOrder(ResultSet rs) throws SQLException {
        return new ExchangeOrder(
                rs.getString("id"),
                rs.getString("business_no"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("from_code"),
                rs.getString("to_code"),
                new BigDecimal(rs.getString("source_amount")),
                new BigDecimal(rs.getString("target_amount")),
                ExchangeStatus.valueOf(rs.getString("status")),
                rs.getString("message"),
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    // ------------------------------------------------------------
    // 邮箱
    // ------------------------------------------------------------

    public CompletableFuture<Void> addMailbox(UUID player, byte[] itemData, int quantity) {
        return execute(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO mailbox(id,player_uuid,item_data,quantity,created_at) VALUES(?,?,?,?,?)")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, player.toString());
                ps.setBytes(3, itemData);
                ps.setInt(4, quantity);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
    }

    public CompletableFuture<Void> consumeMailbox(String id, UUID player, int amount) {
        return execute(() -> inTransaction(conn -> {
            if (amount <= 0) {
                return null;
            }
            int current;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT quantity FROM mailbox WHERE id=? AND player_uuid=?")) {
                ps.setString(1, id);
                ps.setString(2, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    current = rs.getInt(1);
                }
            }
            if (amount >= current) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM mailbox WHERE id=? AND player_uuid=?")) {
                    ps.setString(1, id);
                    ps.setString(2, player.toString());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mailbox SET quantity=quantity-? WHERE id=? AND player_uuid=?")) {
                    ps.setInt(1, amount);
                    ps.setString(2, id);
                    ps.setString(3, player.toString());
                    ps.executeUpdate();
                }
            }
            return null;
        }));
    }

    // ------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------

    private int count(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private Optional<OfficialItem> selectOfficialItem(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM official_items WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapOfficialItem(rs));
            }
        }
    }

    private Optional<Order> selectOrder(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM orders WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapOrder(rs));
            }
        }
    }

    private boolean hasPendingOrder(Connection conn, UUID buyer, String column, String value) throws SQLException {
        if (!"official_item_id".equals(column) && !"listing_id".equals(column)) {
            throw new IllegalArgumentException("非法列名");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM orders WHERE buyer_uuid=? AND " + column + "=? AND status='PENDING' LIMIT 1")) {
            ps.setString(1, buyer.toString());
            ps.setString(2, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private PurchaseState loadPurchaseState(Connection conn,
                                            UUID player,
                                            String itemId,
                                            ResetPeriod period,
                                            long now) throws SQLException {
        long currentWindow = period.windowStart(now, resetZone);
        int count;
        long storedWindow;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT purchase_count, window_start FROM official_purchases"
                        + " WHERE player_uuid=? AND item_id=?")) {
            ps.setString(1, player.toString());
            ps.setString(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO official_purchases(player_uuid,item_id,purchase_count,window_start)"
                                    + " VALUES(?,?,0,?)")) {
                        insert.setString(1, player.toString());
                        insert.setString(2, itemId);
                        insert.setLong(3, currentWindow);
                        insert.executeUpdate();
                    }
                    return new PurchaseState(0, currentWindow);
                }
                count = rs.getInt(1);
                storedWindow = rs.getLong(2);
            }
        }
        if (period.shouldReset(storedWindow, now, resetZone)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE official_purchases SET purchase_count=0, window_start=?"
                            + " WHERE player_uuid=? AND item_id=?")) {
                ps.setLong(1, currentWindow);
                ps.setString(2, player.toString());
                ps.setString(3, itemId);
                ps.executeUpdate();
            }
            return new PurchaseState(0, currentWindow);
        }
        return new PurchaseState(count, storedWindow);
    }

    private void insertOrder(Connection conn, Order order) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO orders(id,business_no,asset_code,buyer_uuid,seller_uuid,listing_id,"
                        + "official_item_id,item_data,quantity,unit_price,total_price,status,"
                        + "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, order.id());
            ps.setString(2, order.businessNo());
            ps.setString(3, currencies.resolveCode(order.assetCode()));
            ps.setString(4, order.buyerUuid().toString());
            ps.setString(5, order.sellerUuid() == null ? null : order.sellerUuid().toString());
            ps.setString(6, order.listingId());
            ps.setString(7, order.officialItemId());
            ps.setBytes(8, order.itemData());
            ps.setInt(9, order.quantity());
            ps.setString(10, order.unitPrice().toPlainString());
            ps.setString(11, order.totalPrice().toPlainString());
            ps.setString(12, order.status().name());
            ps.setLong(13, order.createdAt());
            ps.setLong(14, order.updatedAt());
            ps.executeUpdate();
        }
    }

    private void updateOrderStatus(Connection conn, String orderId, OrderStatus status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE orders SET status=?, updated_at=? WHERE id=?")) {
            ps.setString(1, status.name());
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, orderId);
            ps.executeUpdate();
        }
    }

    private void insertMailbox(Connection conn, UUID player, byte[] itemData, int quantity, long now)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mailbox(id,player_uuid,item_data,quantity,created_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, player.toString());
            ps.setBytes(3, itemData);
            ps.setInt(4, quantity);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    private void bindOfficialItemInsert(PreparedStatement ps, OfficialItem item) throws SQLException {
        ps.setString(1, item.id());
        ps.setString(2, currencies.resolveCode(item.assetCode()));
        ps.setBytes(3, item.itemData());
        ps.setString(4, item.basePrice().toPlainString());
        ps.setInt(5, item.stock());
        ps.setInt(6, item.perPlayerLimit());
        ps.setString(7, item.growthPercent().toPlainString());
        ps.setString(8, item.maxPrice().toPlainString());
        ps.setString(9, item.resetPeriod().name());
        ps.setInt(10, item.enabled() ? 1 : 0);
        ps.setLong(11, item.createdAt());
        ps.setLong(12, item.updatedAt());
    }

    private void bindOfficialItemUpdate(PreparedStatement ps, OfficialItem item) throws SQLException {
        ps.setString(1, currencies.resolveCode(item.assetCode()));
        ps.setBytes(2, item.itemData());
        ps.setString(3, item.basePrice().toPlainString());
        ps.setInt(4, item.stock());
        ps.setInt(5, item.perPlayerLimit());
        ps.setString(6, item.growthPercent().toPlainString());
        ps.setString(7, item.maxPrice().toPlainString());
        ps.setString(8, item.resetPeriod().name());
        ps.setInt(9, item.enabled() ? 1 : 0);
        ps.setLong(10, item.updatedAt());
        ps.setString(11, item.id());
    }

    private OfficialItem mapOfficialItem(ResultSet rs) throws SQLException {
        return new OfficialItem(
                rs.getString("id"),
                rs.getString("asset_code"),
                rs.getBytes("item_data"),
                new BigDecimal(rs.getString("base_price")),
                rs.getInt("stock"),
                rs.getInt("per_player_limit"),
                new BigDecimal(rs.getString("growth_percent")),
                new BigDecimal(rs.getString("max_price")),
                ResetPeriod.parse(rs.getString("reset_period")),
                rs.getInt("enabled") != 0,
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    private PlayerListing mapListing(ResultSet rs) throws SQLException {
        return new PlayerListing(
                rs.getString("id"),
                rs.getString("asset_code"),
                UUID.fromString(rs.getString("seller_uuid")),
                rs.getString("seller_name"),
                rs.getBytes("item_data"),
                new BigDecimal(rs.getString("unit_price")),
                rs.getInt("quantity"),
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    private MailboxEntry mapMailbox(ResultSet rs) throws SQLException {
        return new MailboxEntry(
                rs.getString("id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getBytes("item_data"),
                rs.getInt("quantity"),
                rs.getLong("created_at")
        );
    }

    private Order mapOrder(ResultSet rs) throws SQLException {
        String seller = rs.getString("seller_uuid");
        return new Order(
                rs.getString("id"),
                rs.getString("business_no"),
                rs.getString("asset_code"),
                UUID.fromString(rs.getString("buyer_uuid")),
                seller == null ? null : UUID.fromString(seller),
                rs.getString("listing_id"),
                rs.getString("official_item_id"),
                rs.getBytes("item_data"),
                rs.getInt("quantity"),
                new BigDecimal(rs.getString("unit_price")),
                new BigDecimal(rs.getString("total_price")),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    private <T> CompletableFuture<T> submit(SqlCallable<T> callable) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return callable.call();
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        }, executor);
    }

    private CompletableFuture<Void> execute(SqlRunnable runnable) {
        return submit(() -> {
            runnable.run();
            return null;
        });
    }

    private <T> T inTransaction(TransactionCallable<T> callable) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = callable.call(connection);
            connection.commit();
            return result;
        } catch (Exception ex) {
            try {
                connection.rollback();
            } catch (SQLException rollbackEx) {
                ex.addSuppressed(rollbackEx);
            }
            if (ex instanceof SQLException sql) {
                throw sql;
            }
            throw new SQLException(ex);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    @Override
    public void close() {
        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                connection.close();
                closeFuture.complete(null);
            } catch (SQLException ex) {
                closeFuture.completeExceptionally(ex);
            }
        });
        executor.shutdown();
        try {
            closeFuture.get(5, TimeUnit.SECONDS);
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface SqlCallable<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface TransactionCallable<T> {
        T call(Connection connection) throws Exception;
    }
}
