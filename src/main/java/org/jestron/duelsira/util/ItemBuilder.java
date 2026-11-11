package org.jestron.duelsira.util;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this.item = new ItemStack(material);
        this.meta = item.getItemMeta();
    }

    public ItemBuilder name(String name) {
        if (meta != null) meta.setDisplayName(Msg.color(name));
        return this;
    }

    public ItemBuilder lore(String... lines) {
        return lore(Arrays.asList(lines));
    }

    public ItemBuilder lore(List<String> lines) {
        if (meta != null && lines != null) {
            List<String> colored = new ArrayList<>(lines.size());
            for (String l : lines) colored.add(Msg.color(l));
            meta.setLore(colored);
        }
        return this;
    }

    public ItemBuilder skull(OfflinePlayer owner) {
        if (meta instanceof SkullMeta sm && owner != null) {
            sm.setOwningPlayer(owner);
        }
        return this;
    }

    public ItemStack build() {
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack filler(Material m) {
        return new ItemBuilder(m).name(" ").build();
    }
}
