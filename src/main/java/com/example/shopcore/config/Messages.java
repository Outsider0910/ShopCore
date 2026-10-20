package com.example.shopcore.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * MiniMessage 文案。
 *
 * <p>默认文案直接从 jar 内 config.yml 读取，因此玩家改动 config.yml 时即使漏掉新增键，
 * 也不会出现空白提示；运行时 config.yml 优先。</p>
 */
public final class Messages {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final FileConfiguration runtime;
    private final YamlConfiguration defaults;
    private final Component prefix;

    public Messages(Plugin plugin) {
        this.runtime = plugin.getConfig();
        YamlConfiguration loaded = new YamlConfiguration();
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in != null) {
                loaded.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("读取内置默认文案失败：" + ex.getMessage());
        }
        this.defaults = loaded;
        String rawPrefix = raw("prefix", "");
        this.prefix = rawPrefix.isBlank() ? Component.empty() : safeDeserialize(rawPrefix);
    }

    public String raw(String key, String fallback) {
        String value = runtime.getString("messages." + key);
        if (value == null) {
            value = defaults.getString("messages." + key, fallback);
        }
        return value == null ? fallback : value;
    }

    public Component get(String key, String fallback) {
        return get(key, Collections.emptyMap(), Collections.emptyMap(), fallback);
    }

    public Component get(String key, Map<String, String> placeholders, String fallback) {
        return get(key, placeholders, Collections.emptyMap(), fallback);
    }

    public Component get(String key, Map<String, String> placeholders,
                         Map<String, Component> componentPlaceholders, String fallback) {
        String raw = raw(key, fallback);
        try {
            return noItalic(miniMessage.deserialize(raw, resolver(placeholders, componentPlaceholders)));
        } catch (RuntimeException ex) {
            // 配置里的 MiniMessage 写错时不要让整个商店报错
            return noItalic(Component.text(raw));
        }
    }

    private Component safeDeserialize(String raw) {
        try {
            return noItalic(miniMessage.deserialize(raw));
        } catch (RuntimeException ex) {
            return noItalic(Component.text(raw));
        }
    }

    /** 商店所有文案统一不用斜体。 */
    private static Component noItalic(Component component) {
        return component == null
                ? Component.empty()
                : component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public Component prefixed(String key, Map<String, String> placeholders, String fallback) {
        return prefixed(key, placeholders, Collections.emptyMap(), fallback);
    }

    public Component prefixed(String key, Map<String, String> placeholders,
                              Map<String, Component> componentPlaceholders, String fallback) {
        return prefix.append(get(key, placeholders, componentPlaceholders, fallback));
    }

    public void send(CommandSender sender, String key, String fallback) {
        send(sender, key, Collections.emptyMap(), Collections.emptyMap(), fallback);
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders, String fallback) {
        send(sender, key, placeholders, Collections.emptyMap(), fallback);
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders,
                     Map<String, Component> componentPlaceholders, String fallback) {
        sender.sendMessage(prefixed(key, placeholders, componentPlaceholders, fallback));
    }

    public void sendRaw(CommandSender sender, String key, Map<String, String> placeholders, String fallback) {
        sender.sendMessage(get(key, placeholders, fallback));
    }

    public void sendRaw(CommandSender sender, String key, Map<String, String> placeholders,
                        Map<String, Component> componentPlaceholders, String fallback) {
        sender.sendMessage(get(key, placeholders, componentPlaceholders, fallback));
    }

    /** 发送带 [确认] / [取消] 可点击按钮的提示。token 由 SessionManager 生成。 */
    public void sendConfirm(CommandSender sender,
                            String key,
                            Map<String, String> placeholders,
                            String fallback,
                            UUID token) {
        sendConfirm(sender, key, placeholders, Collections.emptyMap(), fallback, token);
    }

    public void sendConfirm(CommandSender sender,
                            String key,
                            Map<String, String> placeholders,
                            Map<String, Component> componentPlaceholders,
                            String fallback,
                            UUID token) {
        Component message = prefixed(key, placeholders, componentPlaceholders, fallback);
        Component confirm = get("buttons.confirm", "<green><bold>[确认]</bold></green>")
                .clickEvent(ClickEvent.runCommand("/shop confirm " + token))
                .hoverEvent(HoverEvent.showText(get("buttons.confirm-hover", "<gray>点击确认</gray>")));
        Component cancel = get("buttons.cancel", "<red><bold>[取消]</bold></red>")
                .clickEvent(ClickEvent.runCommand("/shop cancel " + token))
                .hoverEvent(HoverEvent.showText(get("buttons.cancel-hover", "<gray>点击取消</gray>")));
        sender.sendMessage(message
                .append(Component.text("  "))
                .append(confirm)
                .append(Component.text(" "))
                .append(cancel));
    }

    private TagResolver resolver(Map<String, String> placeholders,
                                 Map<String, Component> componentPlaceholders) {
        TagResolver.Builder builder = TagResolver.builder();
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                builder.resolver(Placeholder.unparsed(
                        entry.getKey(), entry.getValue() == null ? "" : entry.getValue()));
            }
        }
        if (componentPlaceholders != null) {
            for (Map.Entry<String, Component> entry : componentPlaceholders.entrySet()) {
                if (entry.getValue() != null) {
                    builder.resolver(Placeholder.component(entry.getKey(), entry.getValue()));
                }
            }
        }
        return builder.build();
    }
}
