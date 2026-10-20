package com.example.shopcore.economy;

import java.math.BigDecimal;

/**
 * 某玩家在一种 Wallet 资产上的余额。
 *
 * <p>{@code error} 非空表示该币种查询失败（例如玩家未绑定网站账号），
 * 其余币种的余额仍可正常展示。</p>
 */
public record CurrencyBalance(CurrencyAsset asset, BigDecimal balance, String error) {

    public boolean ok() {
        return error == null;
    }

    public BigDecimal amountOrZero() {
        return balance == null ? BigDecimal.ZERO : balance;
    }
}
