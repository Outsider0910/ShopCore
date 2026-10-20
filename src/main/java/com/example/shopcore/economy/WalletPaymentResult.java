package com.example.shopcore.economy;

/**
 * Wallet 支付结果。{@link Status#UNKNOWN} 表示请求可能已经执行，商店不会发货，等管理员对账。
 */
public record WalletPaymentResult(Status status, String message) {

    public enum Status {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        FAILED,
        UNKNOWN
    }

    public static WalletPaymentResult success() {
        return new WalletPaymentResult(Status.SUCCESS, "");
    }

    public static WalletPaymentResult insufficientFunds(String message) {
        return new WalletPaymentResult(Status.INSUFFICIENT_FUNDS, message);
    }

    public static WalletPaymentResult failed(String message) {
        return new WalletPaymentResult(Status.FAILED, message);
    }

    public static WalletPaymentResult unknown(String message) {
        return new WalletPaymentResult(Status.UNKNOWN, message);
    }

    public boolean successful() {
        return status == Status.SUCCESS;
    }
}
