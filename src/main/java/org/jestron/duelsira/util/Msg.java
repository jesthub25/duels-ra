package org.jestron.duelsira.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jestron.duelsira.DuelSira;

/**
 * Mesaj yardimcisi. Tum mesajlar config.yml -> messages altindan okunur.
 * Config'de olmayan anahtarlar jar icindeki varsayilan config'den gelir.
 */
public final class Msg {

    private Msg() {
    }

    public static String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Ham mesaj (prefix yok), placeholder: {anahtar}, deger, {anahtar2}, deger2 ... */
    public static String raw(String key, Object... placeholders) {
        FileConfiguration cfg = DuelSira.get().getConfig();
        String s = cfg.getString("messages." + key);
        if (s == null) {
            s = "&c[eksik mesaj: " + key + "]";
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            s = s.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return color(s);
    }

    public static String prefixed(String key, Object... placeholders) {
        return color(DuelSira.get().getConfig().getString("messages.prefix", "")) + raw(key, placeholders);
    }

    public static void send(CommandSender to, String key, Object... placeholders) {
        if (to == null) return;
        String body = raw(key, placeholders);
        if (ChatColor.stripColor(body).isEmpty()) return; // bos mesaj = kapali
        to.sendMessage(color(DuelSira.get().getConfig().getString("messages.prefix", "")) + body);
    }

    public static void actionBar(Player p, String text) {
        if (p == null || !p.isOnline()) return;
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
    }
}
