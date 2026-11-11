package org.jestron.duelsira.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jestron.duelsira.util.Msg;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * InventoryHolder tabanli menu. Baslik karsilastirmasi yerine holder kontrolu yapilir
 * (baska bir sandigin adini taklit ederek menu acmak mumkun degil).
 * Menudeki hicbir esya alinamaz / tasinamaz.
 */
public abstract class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    protected Menu(int rows, String title) {
        int size = Math.max(1, Math.min(6, rows)) * 9;
        this.inventory = Bukkit.createInventory(this, size, Msg.color(title));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected void set(int slot, ItemStack item, Consumer<Player> action) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        inventory.setItem(slot, item);
        if (action == null) actions.remove(slot);
        else actions.put(slot, action);
    }

    void handleClick(Player p, int rawSlot) {
        Consumer<Player> a = actions.get(rawSlot);
        if (a != null) a.accept(p);
    }

    /** Menu kapandiginda. */
    public void onClose(Player p) {
    }

    /** Duel gonderme akisinin parcasi mi (oturum takibi icin). */
    public boolean isSetupMenu() {
        return false;
    }

    public void open(Player p) {
        p.openInventory(inventory);
    }
}
