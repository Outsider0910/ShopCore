package com.example.shopcore.economy;

/**
 * 存储层需要的货币信息：只读 Wallet 资产表，不保存任何余额。
 *
 * <p>空 code 表示历史数据没有记录币种，由实现解析为官网当前启用资产表中的第一个。</p>
 */
public interface CurrencyLookup {

    /** 把可能为空的资产 code 解析为 Wallet 资产表里的实际 code。 */
    String resolveCode(String assetCode);

    /** 资产精度；未知资产返回一个安全的回退值。 */
    int scaleOf(String assetCode);
}
