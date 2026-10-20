package com.example.shopcore.model;

public enum OrderStatus {
    /** 已占住库存/寄售数量，等待经济系统返回结果。 */
    PENDING,
    /** 支付成功，物品已进入买家邮箱。 */
    COMPLETED,
    /** 支付确定失败，库存已回滚。 */
    FAILED,
    /** 支付结果未知，库存保持占用，需管理员凭订单号对账。 */
    UNKNOWN
}
