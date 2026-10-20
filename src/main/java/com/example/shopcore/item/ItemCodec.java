package com.example.shopcore.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 物品组件边界。
 *
 * <p>商店把数量为 1 的 ItemStack 用 {@link ItemStack#serializeAsBytes()} 完整落库，
 * 但“是否同一件物品”使用 Paper 的 {@link ItemStack#isSimilar(ItemStack)}
 * （底层是 {@code ItemStack.isSameItemSameComponents}）。它比较材质与全部组件、
 * 忽略数量，能正确处理序列化往返时默认组件补全/编码顺序变化；附魔、耐久、容器内容、
 * custom_data、custom_model_data 等都会参与比较。</p>
 */
public final class ItemCodec {

    private ItemCodec() {
    }

    public static boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    /** 数量为 1、包含完整组件数据的序列化字节。 */
    public static byte[] unitBytes(ItemStack item) {
        if (isEmpty(item)) {
            throw new IllegalArgumentException("物品为空");
        }
        ItemStack one = item.clone();
        one.setAmount(1);
        return one.serializeAsBytes();
    }

    public static ItemStack unitItem(byte[] bytes) {
        ItemStack item = ItemStack.deserializeBytes(bytes);
        item.setAmount(1);
        return item;
    }

    public static boolean sameComponents(ItemStack first, ItemStack second) {
        if (isEmpty(first) || isEmpty(second)) {
            return false;
        }
        return first.isSimilar(second);
    }

    public static String displayName(ItemStack item) {
        if (isEmpty(item)) {
            return "空气";
        }
        String name = PlainTextComponentSerializer.plainText().serialize(item.effectiveName());
        return name.isBlank() ? item.getType().name() : name;
    }

    /**
     * 聊天里使用的物品名 Component。
     *
     * <p>不能用 plain text：原版物品名是 translatable component，转成纯文本会变成英文或
     * translation key。保留 Component 让客户端按自己的语言显示（中文客户端显示“钻石”）。</p>
     */
    public static Component nameComponent(ItemStack item) {
        if (isEmpty(item)) {
            return Component.text("物品");
        }
        Component name = item.effectiveName();
        return name == null ? Component.text("物品") : name;
    }

    public static Component nameComponent(byte[] unitData) {
        try {
            return nameComponent(unitItem(unitData));
        } catch (RuntimeException ex) {
            return Component.text("物品");
        }
    }
}
