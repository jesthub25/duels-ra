package org.jestron.duelsira.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreeperPowerEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.queue.NpcManager;
import org.jestron.duelsira.queue.QueueManager;

/** Duel sirasi: bolge takibi + NPC korumasi. */
public final class QueueListener implements Listener {

    private final DuelSira plugin;
    private final QueueManager queue;
    private final NpcManager npcs;

    public QueueListener(DuelSira plugin) {
        this.plugin = plugin;
        this.queue = plugin.getQueueManager();
        this.npcs = plugin.getNpcManager();
    }

    // ------------------------------------------------------------------ Bolge takibi

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (queue.isEmpty()) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        // Sadece blok degisince sorgula (performans)
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) {
            return;
        }
        Player p = e.getPlayer();
        if (!queue.isTracked(p.getUniqueId())) return;
        queue.checkRegion(p, to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (queue.isEmpty()) return;
        Player p = e.getPlayer();
        if (!queue.isTracked(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) queue.checkRegion(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (queue.isEmpty()) return;
        Player p = e.getPlayer();
        if (queue.isTracked(p.getUniqueId())) queue.checkRegion(p);
    }

    // ------------------------------------------------------------------ NPC

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcInteract(PlayerInteractEntityEvent e) {
        Entity ent = e.getRightClicked();
        if (!npcs.isNpc(ent)) return;
        e.setCancelled(true); // ticaret menusu, binme, besleme vb. engellenir
        if (e.getHand() != EquipmentSlot.HAND) return; // sol el olayi iki kez tetiklemesin
        queue.handleNpcClick(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcInteractAt(PlayerInteractAtEntityEvent e) {
        if (npcs.isNpc(e.getRightClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcDamage(EntityDamageEvent e) {
        if (npcs.isNpc(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcCombust(EntityCombustEvent e) {
        if (npcs.isNpc(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcTransform(EntityTransformEvent e) {
        if (npcs.isNpc(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcTeleport(EntityTeleportEvent e) {
        if (npcs.isNpc(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcPower(CreeperPowerEvent e) {
        if (npcs.isNpc(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcLeash(PlayerLeashEntityEvent e) {
        if (npcs.isNpc(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcShear(PlayerShearEntityEvent e) {
        if (npcs.isNpc(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcBucket(PlayerBucketEntityEvent e) {
        if (npcs.isNpc(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onNpcVehicle(VehicleEnterEvent e) {
        if (npcs.isNpc(e.getEntered())) e.setCancelled(true);
    }

    @EventHandler
    public void onNpcDeath(EntityDeathEvent e) {
        if (npcs.isNpc(e.getEntity())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        if (!npcs.hasNpcIn(e.getChunk())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getChunk().isLoaded()) npcs.validateChunk(e.getChunk());
        });
    }
}
