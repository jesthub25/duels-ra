package org.jestron.duelsira.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.duel.DuelInstance;
import org.jestron.duelsira.duel.DuelManager;
import org.jestron.duelsira.util.Msg;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Duel sirasinda oyun kurallari. */
public final class DuelListener implements Listener {

    private final DuelSira plugin;
    private final DuelManager duels;

    public DuelListener(DuelSira plugin) {
        this.plugin = plugin;
        this.duels = plugin.getDuelManager();
    }

    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ Hasar

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!duels.hasActiveDuels()) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = attackerOf(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        DuelInstance dv = duels.getDuel(victim.getUniqueId());
        DuelInstance da = duels.getDuel(attacker.getUniqueId());
        if (dv == null && da == null) return;
        if (dv != null && dv == da && dv.getPhase() == DuelInstance.Phase.FIGHT) {
            // Rakipler birbirine her zaman vurabilir (arena bolgesinin pvp ayarindan bagimsiz)
            e.setCancelled(false);
        } else {
            // Duel disindan mudahale / duel'deki oyuncunun disariya vurmasi engellenir
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!duels.hasActiveDuels()) return;
        if (!(e.getEntity() instanceof Player p)) return;
        DuelInstance d = duels.getDuel(p.getUniqueId());
        if (d == null) return;
        if (d.getPhase() != DuelInstance.Phase.FIGHT) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        duels.handleDeath(e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        duels.handleRespawn(e);
    }

    // ------------------------------------------------------------------ Bloklar / kurallar

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!duels.hasActiveDuels()) return;
        Player p = e.getPlayer();
        DuelInstance d = duels.getDuel(p.getUniqueId());
        if (d == null) return;
        if (d.getPhase() != DuelInstance.Phase.FIGHT || !d.getArena().contains(e.getBlockPlaced())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!duels.hasActiveDuels()) return;
        DuelInstance d = duels.getDuel(e.getPlayer().getUniqueId());
        if (d == null) return;
        if (d.getPhase() != DuelInstance.Phase.FIGHT || !d.getArena().contains(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!duels.hasActiveDuels()) return;
        DuelInstance d = duels.getDuel(e.getPlayer().getUniqueId());
        if (d == null) return;
        if (d.getPhase() != DuelInstance.Phase.FIGHT || !d.getArena().contains(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    /** Kuvvet I'li duelde sut/totem vb. efekti silerse geri verilir. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!duels.hasActiveDuels() || !(e.getEntity() instanceof Player p)) return;
        if (e.getAction() != EntityPotionEffectEvent.Action.REMOVED && e.getAction() != EntityPotionEffectEvent.Action.CLEARED) return;
        if (e.getCause() == EntityPotionEffectEvent.Cause.PLUGIN || e.getCause() == EntityPotionEffectEvent.Cause.DEATH) return;
        if (!e.getModifiedType().equals(DuelManager.strengthType())) return;
        duels.handleEffectRemoved(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearl(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof EnderPearl pearl) || !duels.hasActiveDuels()) return;
        if (!(pearl.getShooter() instanceof Player p)) return;
        if (duels.getDuel(p.getUniqueId()) != null && plugin.getConfig().getBoolean("settings.block-ender-pearl", true)) {
            e.setCancelled(true);
            Msg.send(p, "pearl-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!duels.hasActiveDuels()) return;
        DuelInstance d = duels.getDuel(e.getPlayer().getUniqueId());
        if (d == null) return;
        PlayerTeleportEvent.TeleportCause c = e.getCause();
        if (c == PlayerTeleportEvent.TeleportCause.PLUGIN || c == PlayerTeleportEvent.TeleportCause.COMMAND
                || c == PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            return;
        }
        if (c == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && plugin.getConfig().getBoolean("settings.block-ender-pearl", true)) {
            e.setCancelled(true);
            return;
        }
        Location to = e.getTo();
        if (to == null || !d.getArena().isInside(to)) {
            e.setCancelled(true);
        }
    }

    /** Geri sayimda yerinden kipirdayamaz (sadece etrafa bakabilir). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!duels.hasActiveDuels()) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || (from.getX() == to.getX() && from.getZ() == to.getZ())) return;
        DuelInstance d = duels.getDuel(e.getPlayer().getUniqueId());
        if (d == null || d.getPhase() != DuelInstance.Phase.COUNTDOWN) return;
        Location back = from.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        e.setTo(back);
    }

    // ------------------------------------------------------------------ Komutlar

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!duels.hasActiveDuels()) return;
        Player p = e.getPlayer();
        if (!duels.isInDuel(p.getUniqueId()) || p.hasPermission("duel.admin.bypass")) return;
        String msg = e.getMessage();
        if (msg.startsWith("/")) msg = msg.substring(1);
        int sp = msg.indexOf(' ');
        String label = (sp == -1 ? msg : msg.substring(0, sp)).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon != -1) label = label.substring(colon + 1);
        if (allowedCommands().contains(label)) return;
        e.setCancelled(true);
        Msg.send(p, "command-blocked");
    }

    private Object allowedFor;
    private Set<String> allowedCache;

    /** Izinli komutlar; config yeniden yuklenene kadar onbellekte. */
    private Set<String> allowedCommands() {
        Object cfg = plugin.getConfig();
        if (allowedCache != null && allowedFor == cfg) return allowedCache;
        List<String> list = plugin.getConfig().getStringList("settings.allowed-commands");
        Set<String> set = new HashSet<>();
        for (String s : list) set.add(s.toLowerCase(Locale.ROOT).replace("/", ""));
        set.add("duel");
        set.add("d");
        set.add("duelsira");
        set.add("duelsıra");
        allowedFor = cfg;
        allowedCache = set;
        return set;
    }

    // ------------------------------------------------------------------ Giris / cikis

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.getQueueManager().handleQuit(p);
        duels.handleQuit(p);
        plugin.getGuiManager().clearPlayer(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> duels.handleJoin(p), 2L);
    }
}
