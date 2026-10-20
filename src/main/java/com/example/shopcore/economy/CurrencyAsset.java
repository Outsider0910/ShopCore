package com.example.shopcore.economy;

import com.example.wallet.api.WalletAsset;

import java.math.BigDecimal;
import java.util.Locale;

/** 商店侧的货币视图，直接来自 Wallet 官网资产表（WalletAsset）。 */
public record CurrencyAsset(
        String code,
        String name,
        String symbol,
        int scale,
        boolean money,
        boolean enabled,
        boolean transferEnabled
) {

    public CurrencyAsset {
        code = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        name = name == null ? "" : name;
        symbol = symbol == null ? "" : symbol;
        scale = Math.max(0, Math.min(scale, 8));
    }

    public CurrencyAsset(String code, String name, String symbol, int scale, boolean enabled) {
        this(code, name, symbol, scale, false, enabled, false);
    }

    public static CurrencyAsset from(WalletAsset asset) {
        return new CurrencyAsset(
                asset.code(),
                asset.name(),
                asset.symbol(),
                asset.scale(),
                asset.money(),
                asset.enabled(),
                asset.transferEnabled()
        );
    }

    public String displayName() {
        return name.isBlank() ? code : name;
    }

    public String symbolOrCode() {
        return symbol.isBlank() ? code : symbol;
    }

    public BigDecimal scale(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount.setScale(scale, java.math.RoundingMode.HALF_UP);
    }
}
