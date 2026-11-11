package org.jestron.duelsira.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jestron.duelsira.DuelSira;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MenuListener implements Listener {

    private static final long CLICK_DEBOUNCE_MS = 150L;

    private final DuelSira plugin;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public MenuListener(DuelSira plugin) {
        this.plugin = plugin;
    }

    private static Menu menuOf(Inventory top) {
        if (top == null) return null;
        InventoryHolder h = top.getHolder();
        return h instanceof Menu m ? m : null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        Menu menu = menuOf(top);
        if (menu == null) return;
        // Menu acikken HICBIR tiklama (shift, sayi tusu, cift tik toplama, alt envanter) islenmez
        e.setCancelled(true);
        HumanEntity who = e.getWhoClicked();
        if (!(who instanceof Player p)) return;
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= top.getSize()) return;
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < CLICK_DEBOUNCE_MS) return;
        lastClick.put(p.getUniqueId(), now);
        // Envanter islemleri tiklama olayinin icinde yapilmaz, bir sonraki tick'e ertelenir
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (menuOf(p.getOpenInventory().getTopInventory()) != menu) return;
            menu.handleClick(p, raw);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (menuOf(e.getView().getTopInventory()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Menu menu = menuOf(e.getView().getTopInventory());
        if (menu != null && e.getPlayer() instanceof Player p) {
            menu.onClose(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastClick.remove(e.getPlayer().getUniqueId());
    }
}
