package org.jestron.duelsira.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jestron.duelsira.util.ItemBuilder;
import org.jestron.duelsira.util.Msg;

import java.util.function.Consumer;

/**
 * Salt-okunur envanter goruntuleme.
 * Duzen: 0-26 ana envanter (9-35), 27-35 hotbar (0-8),
 * 36 kask, 37 gogusluk, 38 pantolon, 39 bot, 40 sol el, 49 bilgi/geri.
 */
public final class InventoryViewMenu extends Menu {

    private InventoryViewMenu(String title, ItemStack[] contents, Consumer<Player> back) {
        super(6, title);
        ItemStack[] c = contents == null ? new ItemStack[0] : contents;
        for (int i = 9; i < 36; i++) set(i - 9, clone(c, i));
        for (int i = 0; i < 9; i++) set(27 + i, clone(c, i));
        set(36, orLabel(clone(c, 39), "view-helmet"));
        set(37, orLabel(clone(c, 38), "view-chestplate"));
        set(38, orLabel(clone(c, 37), "view-leggings"));
        set(39, orLabel(clone(c, 36), "view-boots"));
        set(40, orLabel(clone(c, 40), "view-offhand"));
        ItemStack filler = ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 41; i < 54; i++) set(i, filler);
        if (back != null) {
            set(49, new ItemBuilder(Material.ARROW).name(Msg.raw("gui-back")).build(), back);
        } else {
            set(49, new ItemBuilder(Material.BARRIER).name(Msg.raw("gui-close")).build(), Player::closeInventory);
        }
    }

    private static ItemStack clone(ItemStack[] c, int i) {
        if (i >= c.length || c[i] == null || c[i].getType().isAir()) return null;
        return c[i].clone();
    }

    private static ItemStack orLabel(ItemStack it, String key) {
        if (it != null) return it;
        return new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(Msg.raw(key)).build();
    }

    /** Duel gecmisindeki kayitli envanter. */
    public static void openRecorded(Player viewer, String name, ItemStack[] contents, Consumer<Player> back) {
        new InventoryViewMenu(Msg.raw("gui-view-title", "player", name), contents, back).open(viewer);
    }
}
