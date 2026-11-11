package org.jestron.duelsira.kit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.util.InventorySerializer;
import org.jestron.duelsira.util.Msg;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class KitManager {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    private final DuelSira plugin;
    private final File file;
    private YamlConfiguration config;
    private final Map<String, Kit> kits = new LinkedHashMap<>();

    public KitManager(DuelSira plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "duelkits.yml");
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource("duelkits.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        kits.clear();
        ConfigurationSection sec = config.getConfigurationSection("kits");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String contents = sec.getString(key + ".contents");
                if (contents == null) continue;
                String id = key.toLowerCase(Locale.ROOT);
                try {
                    ItemStack[] items = InventorySerializer.fromBase64(contents);
                    kits.put(id, new Kit(id, items));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Kit yuklenemedi: " + key, e);
                }
            }
        }
        plugin.getLogger().info(kits.size() + " kit yuklendi.");
    }

    private void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "duelkits.yml kaydedilemedi", e);
        }
    }

    public void createKit(Player player, String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            Msg.send(player, "kit-invalid-name");
            return;
        }
        if (kits.containsKey(name)) {
            Msg.send(player, "kit-exists", "kit", name);
            return;
        }
        ItemStack[] src = player.getInventory().getContents();
        ItemStack[] copy = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) copy[i] = src[i] == null ? null : src[i].clone();
        config.set("kits." + name + ".contents", InventorySerializer.toBase64(copy));
        save();
        kits.put(name, new Kit(name, copy));
        Msg.send(player, "kit-created", "kit", name);
    }

    public void deleteKit(Player player, String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        if (kits.remove(name) == null) {
            Msg.send(player, "kit-not-found", "kit", name);
            return;
        }
        config.set("kits." + name, null);
        save();
        Msg.send(player, "kit-deleted", "kit", name);
    }

    public Kit getKit(String name) {
        return name == null ? null : kits.get(name.toLowerCase(Locale.ROOT));
    }

    public List<String> getKitNames() {
        return Collections.unmodifiableList(new ArrayList<>(kits.keySet()));
    }

    public static final class Kit {
        private final String name;
        private final ItemStack[] contents;

        Kit(String name, ItemStack[] contents) {
            this.name = name;
            this.contents = contents;
        }

        public String getName() {
            return name;
        }

        public void apply(Player p) {
            p.getInventory().clear();
            ItemStack[] copy = new ItemStack[contents.length];
            for (int i = 0; i < contents.length; i++) copy[i] = contents[i] == null ? null : contents[i].clone();
            if (copy.length == p.getInventory().getSize()) {
                p.getInventory().setContents(copy);
            } else {
                // Farkli surumden gelen kit boyutu: ilk slotlari doldur
                for (int i = 0; i < Math.min(copy.length, p.getInventory().getSize()); i++) {
                    p.getInventory().setItem(i, copy[i]);
                }
            }
            p.updateInventory();
        }
    }
}
