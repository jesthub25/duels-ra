package org.jestron.duelsira.util;

import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;

/**
 * Duel oncesi oyuncu durumu.
 * contents != null ise envanter de saklanir (kitli duel) ve geri yuklenir.
 */
public final class PlayerState {

    private final ItemStack[] contents;
    private final GameMode gameMode;
    private final int level;
    private final float exp;
    private final boolean allowFlight;

    private PlayerState(ItemStack[] contents, GameMode gameMode, int level, float exp, boolean allowFlight) {
        this.contents = contents;
        this.gameMode = gameMode == null ? GameMode.SURVIVAL : gameMode;
        this.level = level;
        this.exp = exp;
        this.allowFlight = allowFlight;
    }

    public static PlayerState capture(Player p, boolean withInventory) {
        ItemStack[] inv = null;
        if (withInventory) {
            ItemStack[] src = p.getInventory().getContents();
            inv = new ItemStack[src.length];
            for (int i = 0; i < src.length; i++) {
                inv[i] = src[i] == null ? null : src[i].clone();
            }
        }
        return new PlayerState(inv, p.getGameMode(), p.getLevel(), p.getExp(), p.getAllowFlight());
    }

    public boolean hasInventory() {
        return contents != null;
    }

    public void restore(Player p) {
        if (contents != null) {
            p.getInventory().clear();
            ItemStack[] copy = new ItemStack[contents.length];
            for (int i = 0; i < contents.length; i++) {
                copy[i] = contents[i] == null ? null : contents[i].clone();
            }
            p.getInventory().setContents(copy);
            p.setLevel(level);
            p.setExp(exp);
            p.updateInventory();
        }
        if (p.getGameMode() != gameMode) {
            p.setGameMode(gameMode);
        }
        p.setFlying(false);
        p.setAllowFlight(allowFlight || gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR);
    }

    public void save(File file) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        if (contents != null) y.set("contents", InventorySerializer.toBase64(contents));
        y.set("gamemode", gameMode.name());
        y.set("level", level);
        y.set("exp", (double) exp);
        y.set("allow-flight", allowFlight);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Klasor olusturulamadi: " + parent);
        }
        y.save(file);
    }

    public static PlayerState load(File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        String c = y.getString("contents");
        ItemStack[] inv = c == null ? null : InventorySerializer.fromBase64(c);
        GameMode gm;
        try {
            gm = GameMode.valueOf(y.getString("gamemode", "SURVIVAL"));
        } catch (IllegalArgumentException ex) {
            gm = GameMode.SURVIVAL;
        }
        return new PlayerState(inv, gm, y.getInt("level"), (float) y.getDouble("exp"), y.getBoolean("allow-flight"));
    }
}
