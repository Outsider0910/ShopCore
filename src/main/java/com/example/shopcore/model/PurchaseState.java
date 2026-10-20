package com.example.shopcore.model;

/** 某位玩家在某个官方商品上的当前周期购买件数。 */
public record PurchaseState(int purchased, long windowStart) {
}
