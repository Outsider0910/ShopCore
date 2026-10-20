package com.example.shopcore.model;

/** 玩家商品下单前成功占住寄售数量的结果。 */
public record PlayerReservation(
        Order order,
        PlayerListing listing
) {
}
