package org.jestron.duelsira.duel;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.arena.Arena;
import org.jestron.duelsira.kit.KitManager;
import org.jestron.duelsira.storage.Database;
import org.jestron.duelsira.util.InventorySerializer;
import org.jestron.duelsira.util.Msg;
import org.jestron.duelsira.util.PlayerState;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Duel yasam dongusu (normal duel + duel sirasi duellolari).
 * Tum metotlar ana thread'den cagrilir.
 */
public final class DuelManager {

    private final DuelSira plugin;
    private final Map<UUID, DuelInstance> byPlayer = new HashMap<>();
    private final Set<DuelInstance> duels = new HashSet<>();
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Set<UUID> pendingReturn = new HashSet<>();
    private final Map<UUID, DuelRequest> requestsByTarget = new HashMap<>();
    private final Map<UUID, DuelRequest> requestsByChallenger = new HashMap<>();
    private final Set<UUID> requestsDisabled = new HashSet<>();
    private final File stateFolder;
    private BukkitTask borderTask;

    public DuelManager(DuelSira plugin) {
        this.plugin = plugin;
        this.stateFolder = new File(plugin.getDataFolder(), "states");
    }

    public void startTasks() {
        long interval = Math.max(1L, plugin.getConfig().getLong("settings.border-check-interval", 10L));
        borderTask = Bukkit.getScheduler().runTaskTimer(plugin, this::borderTick, interval, interval);
    }

    // ================================================================== Sorgular

    public boolean isInDuel(UUID u) {
        return byPlayer.containsKey(u);
    }

    public DuelInstance getDuel(UUID u) {
        return byPlayer.get(u);
    }

    public boolean hasActiveDuels() {
        return !byPlayer.isEmpty();
    }

    public Collection<DuelInstance> getDuels() {
        return Collections.unmodifiableSet(duels);
    }

    public boolean isRequestsEnabled(UUID u) {
        return !requestsDisabled.contains(u);
    }

    public boolean toggleRequests(UUID u) {
        if (!requestsDisabled.remove(u)) {
            requestsDisabled.add(u);
            return false;
        }
        return true;
    }

    private boolean atCapacity() {
        int max = plugin.getConfig().getInt("settings.max-concurrent-duels", 50);
        return max > 0 && duels.size() >= max;
    }

    // ================================================================== Normal duel istekleri

    private static final class DuelRequest {
        final UUID challenger, target;
        final String kit, arena;
        final boolean itemsDrop;
        final DuelSettings settings;
        BukkitTask timeout;

        DuelRequest(UUID challenger, UUID target, String kit, String arena, boolean itemsDrop, DuelSettings settings) {
            this.challenger = challenger;
            this.target = target;
            this.kit = kit;
            this.arena = arena;
            this.itemsDrop = itemsDrop;
            this.settings = settings;
        }
    }

    /** Duel gonderme oncesi kontroller (GUI acilmadan once de kullanilir). */
    public boolean canChallenge(Player challenger, Player target) {
        if (target == null || !target.isOnline()) {
            Msg.send(challenger, "player-not-found");
            return false;
        }
        if (target.getUniqueId().equals(challenger.getUniqueId())) {
            Msg.send(challenger, "duel-self");
            return false;
        }
        if (!plugin.getArenaManager().hasReadyArena()) {
            Msg.send(challenger, "no-arena");
            return false;
        }
        if (isInDuel(challenger.getUniqueId()) || plugin.getQueueManager().isInMatch(challenger.getUniqueId())) {
            Msg.send(challenger, "you-are-busy");
            return false;
        }
        if (isInDuel(target.getUniqueId()) || plugin.getQueueManager().isInMatch(target.getUniqueId())) {
            Msg.send(challenger, "target-busy", "player", target.getName());
            return false;
        }
        if (!isRequestsEnabled(target.getUniqueId())) {
            Msg.send(challenger, "requests-disabled", "player", target.getName());
            return false;
        }
        return true;
    }

    public void sendRequest(Player challenger, Player target, String kit, String arena, boolean itemsDrop, DuelSettings settings) {
        if (!canChallenge(challenger, target)) return;
        DuelRequest existing = requestsByTarget.get(target.getUniqueId());
        if (existing != null && !existing.challenger.equals(challenger.getUniqueId())) {
            Msg.send(challenger, "target-has-request", "player", target.getName());
            return;
        }
        if (kit != null && plugin.getKitManager().getKit(kit) == null) {
            Msg.send(challenger, "kit-not-found", "kit", kit);
            return;
        }
        // Onceki gonderdigi istek varsa iptal
        cancelRequestOf(challenger.getUniqueId(), false);

        DuelRequest req = new DuelRequest(challenger.getUniqueId(), target.getUniqueId(), kit, arena,
                kit == null && itemsDrop, kit == null ? settings : null);
        requestsByTarget.put(target.getUniqueId(), req);
        requestsByChallenger.put(challenger.getUniqueId(), req);

        int timeout = Math.max(5, plugin.getConfig().getInt("settings.request-timeout", 30));
        req.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (requestsByTarget.get(req.target) == req) {
                removeRequest(req);
                Msg.send(Bukkit.getPlayer(req.challenger), "request-expired-sender");
                Msg.send(Bukkit.getPlayer(req.target), "request-expired-target");
            }
        }, timeout * 20L);

        Msg.send(challenger, "request-sent", "player", target.getName());

        String kitText = kit == null ? Msg.raw("own-inventory") : Msg.raw("kit-label", "kit", kit);
        String dropText = req.itemsDrop ? Msg.raw("mode-drop") : Msg.raw("mode-keep");
        String arenaText = arena == null ? Msg.raw("arena-random") : arena;
        Msg.send(target, "request-received", "player", challenger.getName());
        Msg.send(target, "request-info", "kit", kitText, "mode", dropText, "arena", arenaText);
        if (req.settings != null) {
            Msg.send(target, "request-rules",
                    "ladder", onOff(req.settings.isAllowLadder()), "slab", onOff(req.settings.isAllowSlab()),
                    "button", onOff(req.settings.isAllowButton()), "cobweb", onOff(req.settings.isAllowCobweb()),
                    "elytra", onOff(req.settings.isAllowElytra()));
        }
        target.spigot().sendMessage(buttons(
                button(Msg.raw("button-accept"), "/duel kabul " + challenger.getName(), Msg.raw("button-accept-hover")),
                button(Msg.raw("button-deny"), "/duel reddet " + challenger.getName(), Msg.raw("button-deny-hover"))));
        Msg.send(target, "request-timeout-info", "seconds", timeout);
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
    }

    public static String onOff(boolean b) {
        return b ? Msg.raw("word-on") : Msg.raw("word-off");
    }

    public static TextComponent button(String text, String command, String hover) {
        TextComponent c = new TextComponent(TextComponent.fromLegacyText(text));
        c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(hover))));
        return c;
    }

    public static BaseComponent[] buttons(TextComponent... buttons) {
        List<BaseComponent> list = new ArrayList<>();
        list.add(new TextComponent("   "));
        for (int i = 0; i < buttons.length; i++) {
            if (i > 0) list.add(new TextComponent("   "));
            list.add(buttons[i]);
        }
        return list.toArray(new BaseComponent[0]);
    }

    private void removeRequest(DuelRequest req) {
        if (req.timeout != null) req.timeout.cancel();
        requestsByTarget.remove(req.target, req);
        requestsByChallenger.remove(req.challenger, req);
    }

    /** Oyuncunun gonderdigi ve aldigi tum istekleri iptal eder. */
    public void cancelRequestsInvolving(UUID u) {
        cancelRequestOf(u, true);
        DuelRequest incoming = requestsByTarget.get(u);
        if (incoming != null) removeRequest(incoming);
    }

    private void cancelRequestOf(UUID challenger, boolean notify) {
        DuelRequest out = requestsByChallenger.get(challenger);
        if (out != null) {
            removeRequest(out);
            if (notify) Msg.send(Bukkit.getPlayer(out.target), "request-cancelled");
        }
    }

    public void acceptRequest(Player target, String challengerName) {
        DuelRequest req = requestsByTarget.get(target.getUniqueId());
        Player challenger = challengerName == null ? null : Bukkit.getPlayerExact(challengerName);
        if (req == null || challenger == null || !req.challenger.equals(challenger.getUniqueId())) {
            Msg.send(target, "no-request");
            return;
        }
        removeRequest(req);
        if (isInDuel(target.getUniqueId()) || plugin.getQueueManager().isInMatch(target.getUniqueId())) {
            Msg.send(target, "you-are-busy");
            return;
        }
        if (isInDuel(challenger.getUniqueId()) || plugin.getQueueManager().isInMatch(challenger.getUniqueId())) {
            Msg.send(target, "target-busy", "player", challenger.getName());
            return;
        }
        if (atCapacity()) {
            Msg.send(target, "server-full");
            Msg.send(challenger, "server-full");
            return;
        }
        Arena arena = null;
        if (req.arena != null) {
            Arena a = plugin.getArenaManager().getArena(req.arena);
            if (a != null && a.isFree() && a.isReady()) {
                arena = a;
            } else {
                Msg.send(target, "arena-unavailable-random");
                Msg.send(challenger, "arena-unavailable-random");
            }
        }
        if (arena == null) arena = plugin.getArenaManager().findRandomFree();
        if (arena == null) {
            Msg.send(target, "arenas-full");
            Msg.send(challenger, "arenas-full");
            return;
        }
        if (req.kit != null && plugin.getKitManager().getKit(req.kit) == null) {
            Msg.send(target, "kit-not-found", "kit", req.kit);
            Msg.send(challenger, "kit-not-found", "kit", req.kit);
            return;
        }
        startDuel(challenger, target, arena, req.kit, req.itemsDrop, req.settings, DuelInstance.Type.NORMAL);
    }

    public void denyRequest(Player target, String challengerName) {
        DuelRequest req = requestsByTarget.get(target.getUniqueId());
        Player challenger = challengerName == null ? null : Bukkit.getPlayerExact(challengerName);
        if (req == null || (challenger != null && !req.challenger.equals(challenger.getUniqueId()))) {
            Msg.send(target, "no-request");
            return;
        }
        removeRequest(req);
        Msg.send(target, "request-denied-target");
        Msg.send(Bukkit.getPlayer(req.challenger), "request-denied-sender", "player", target.getName());
    }

    public List<String> getIncomingRequesterNames(UUID target) {
        List<String> list = new ArrayList<>(1);
        DuelRequest r = requestsByTarget.get(target);
        if (r != null) {
            Player p = Bukkit.getPlayer(r.challenger);
            if (p != null) list.add(p.getName());
        }
        return list;
    }

    // ================================================================== Duel baslatma

    /**
     * Duel baslatir. Arena FREE veya RESERVED olmali.
     * @return basarili mi
     */
    public boolean startDuel(Player a, Player b, Arena arena, String kitName, boolean itemsDrop,
                             DuelSettings settings, DuelInstance.Type type) {
        if (a == null || b == null || !a.isOnline() || !b.isOnline() || a.isDead() || b.isDead()) {
            return false;
        }
        if (arena.getState() != Arena.State.FREE && arena.getState() != Arena.State.RESERVED) {
            return false;
        }
        Location s1 = arena.getSpawn1();
        Location s2 = arena.getSpawn2();
        if (s1 == null || s2 == null) {
            Msg.send(a, "arena-not-ready");
            Msg.send(b, "arena-not-ready");
            return false;
        }
        KitManager.Kit kit = null;
        if (kitName != null) {
            kit = plugin.getKitManager().getKit(kitName);
            if (kit == null) {
                Msg.send(a, "kit-not-found", "kit", kitName);
                Msg.send(b, "kit-not-found", "kit", kitName);
                return false;
            }
        }

        plugin.getArenaManager().occupy(arena);
        DuelInstance duel = new DuelInstance(type, a.getUniqueId(), b.getUniqueId(), arena, kitName, itemsDrop, settings);

        for (Player p : new Player[]{a, b}) {
            plugin.getQueueManager().removeBecauseDuelStarted(p);
            cancelRequestsInvolving(p.getUniqueId());
            plugin.getGuiManager().clearSession(p.getUniqueId());
            p.closeInventory();
            PlayerState st = PlayerState.capture(p, kit != null);
            states.put(p.getUniqueId(), st);
            if (st.hasInventory()) persistState(p.getUniqueId(), st);
            if (p.isInsideVehicle()) p.leaveVehicle();
            prepare(p);
            if (kit != null) kit.apply(p);
        }

        boolean ok1 = a.teleport(s1);
        boolean ok2 = b.teleport(s2);
        if (!ok1 || !ok2) {
            plugin.getLogger().warning("Duel baslatilamadi: teleport baska bir plugin tarafindan engellendi.");
            for (Player p : new Player[]{a, b}) {
                restoreAndReturn(p);
                Msg.send(p, "duel-start-failed");
            }
            plugin.getArenaManager().release(arena);
            return false;
        }

        byPlayer.put(a.getUniqueId(), duel);
        byPlayer.put(b.getUniqueId(), duel);
        duels.add(duel);
        startCountdown(duel, a, b);
        return true;
    }

    private void prepare(Player p) {
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        p.setFlying(false);
        p.setAllowFlight(false);
        heal(p);
    }

    private static void heal(Player p) {
        AttributeInstance max = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        p.setHealth(max == null ? 20.0 : max.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setExhaustion(0f);
    }

    private void startCountdown(DuelInstance duel, Player a, Player b) {
        int seconds = Math.max(0, plugin.getConfig().getInt("settings.countdown", 5));
        String nameA = a.getName(), nameB = b.getName();
        Msg.send(a, "duel-starting", "player", nameB, "arena", duel.getArena().getName());
        Msg.send(b, "duel-starting", "player", nameA, "arena", duel.getArena().getName());
        duel.setCountdownTask(new BukkitRunnable() {
            int left = seconds;

            @Override
            public void run() {
                if (duel.getPhase() != DuelInstance.Phase.COUNTDOWN) {
                    cancel();
                    return;
                }
                Player pa = Bukkit.getPlayer(duel.getP1());
                Player pb = Bukkit.getPlayer(duel.getP2());
                if (left > 0) {
                    for (Player p : new Player[]{pa, pb}) {
                        if (p == null) continue;
                        p.sendTitle(Msg.raw("countdown-title", "seconds", left), Msg.raw("countdown-subtitle"), 0, 25, 5);
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
                    }
                    left--;
                    return;
                }
                duel.setPhase(DuelInstance.Phase.FIGHT);
                duel.setFightStart(System.currentTimeMillis());
                for (Player p : new Player[]{pa, pb}) {
                    if (p == null) continue;
                    p.sendTitle(Msg.raw("fight-title"), "", 0, 30, 10);
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                }
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 20L));
    }

    // ================================================================== Olaylar

    /** PlayerDeathEvent - listener'dan cagrilir. */
    public void handleDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        DuelInstance duel = byPlayer.get(dead.getUniqueId());
        if (duel == null) return;

        boolean keep = !duel.isItemsDrop() || duel.getPhase() != DuelInstance.Phase.FIGHT;
        if (duel.getPhase() == DuelInstance.Phase.FIGHT) {
            duel.getSnapshots().put(dead.getUniqueId(), snapshot(dead));
        }
        if (keep) {
            e.setKeepInventory(true);
            e.setKeepLevel(true);
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
        detach(dead.getUniqueId());
        pendingReturn.add(dead.getUniqueId());
        scheduleAutoRespawn(dead);

        switch (duel.getPhase()) {
            case COUNTDOWN -> cancelDuel(duel, "duel-cancelled", null);
            case FIGHT -> finish(duel, duel.getOpponentPlayer(dead.getUniqueId()), dead);
            case ENDED -> releaseIfEmpty(duel);
        }
    }

    private void scheduleAutoRespawn(Player p) {
        if (!plugin.getConfig().getBoolean("settings.auto-respawn", true)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && p.isDead()) p.spigot().respawn();
        }, 2L);
    }

    /** PlayerRespawnEvent - duelden donen oyuncuyu lobiye alir. */
    public void handleRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        boolean pending = pendingReturn.remove(u);
        boolean persisted = !states.containsKey(u) && stateFile(u).exists();
        if (!pending && !persisted) return;
        Location lobby = plugin.getLobbyLocation();
        if (lobby != null) e.setRespawnLocation(lobby);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || p.isDead()) return;
            restoreAndReturn(p);
        }, 1L);
    }

    /** Oyuncu sunucudan cikti. */
    public void handleQuit(Player p) {
        UUID u = p.getUniqueId();
        cancelRequestsInvolving(u);
        DuelInstance duel = byPlayer.get(u);
        if (duel == null) return;

        switch (duel.getPhase()) {
            case COUNTDOWN -> cancelDuel(duel, "duel-cancelled-quit", u);
            case FIGHT -> {
                duel.getSnapshots().put(u, snapshot(p));
                if (duel.isItemsDrop() && !p.isDead()) {
                    // Kacan oyuncu esyalarini arenaya birakir
                    Location loc = p.getLocation();
                    for (ItemStack it : p.getInventory().getContents()) {
                        if (it != null && !it.getType().isAir()) {
                            p.getWorld().dropItemNaturally(loc, it.clone());
                        }
                    }
                    p.getInventory().clear();
                }
                detach(u);
                restoreAndReturn(p, false);
                Player winner = duel.getOpponentPlayer(u);
                Msg.send(winner, "opponent-quit", "player", p.getName());
                finish(duel, winner, p);
            }
            case ENDED -> {
                detach(u);
                restoreAndReturn(p, false);
                releaseIfEmpty(duel);
            }
        }
    }

    /** Oyuncu sunucuya girdi (1 tick sonra cagrilir). */
    public void handleJoin(Player p) {
        if (!p.isOnline() || p.isDead() || isInDuel(p.getUniqueId())) return;
        UUID u = p.getUniqueId();
        if (pendingReturn.remove(u) || stateFile(u).exists() || states.containsKey(u)) {
            restoreAndReturn(p);
            return;
        }
        if (plugin.getArenaManager().getArenaAt(p.getLocation()) != null) {
            sendToLobby(p);
        }
    }

    // ================================================================== Bitis

    private void finish(DuelInstance duel, Player winner, Player loser) {
        if (duel.getPhase() == DuelInstance.Phase.ENDED) return;
        duel.setPhase(DuelInstance.Phase.ENDED);

        if (winner != null && winner.isOnline()) {
            duel.getSnapshots().put(winner.getUniqueId(), snapshot(winner));
        }
        saveHistory(duel, winner, loser);

        if (loser != null && loser.isOnline()) {
            loser.sendTitle(Msg.raw("lose-title"), Msg.raw("lose-subtitle", "player", winner == null ? "?" : winner.getName()), 5, 50, 10);
            Msg.send(loser, "duel-lost", "player", winner == null ? "?" : winner.getName());
        }
        if (winner == null || !winner.isOnline() || !byPlayer.containsKey(winner.getUniqueId())) {
            releaseIfEmpty(duel);
            return;
        }
        winner.sendTitle(Msg.raw("win-title"), Msg.raw("win-subtitle", "player", loser == null ? "?" : loser.getName()), 5, 50, 10);
        winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        Msg.send(winner, "duel-won", "player", loser == null ? "?" : loser.getName());
        heal(winner);
        winner.setFireTicks(0);

        int delay = duel.isItemsDrop()
                ? Math.max(0, plugin.getConfig().getInt("settings.loot-time", 15))
                : Math.max(0, plugin.getConfig().getInt("settings.end-delay", 3));
        if (duel.isItemsDrop() && delay > 0) {
            Msg.send(winner, "loot-time", "seconds", delay);
        }
        UUID wu = winner.getUniqueId();
        duel.setEndTask(new BukkitRunnable() {
            int left = delay;

            @Override
            public void run() {
                Player w = Bukkit.getPlayer(wu);
                if (w == null || byPlayer.get(wu) != duel) {
                    cancel();
                    releaseIfEmpty(duel);
                    return;
                }
                if (left <= 0) {
                    cancel();
                    detach(wu);
                    restoreAndReturn(w);
                    releaseIfEmpty(duel);
                    return;
                }
                if (duel.isItemsDrop()) {
                    Msg.actionBar(w, Msg.raw("loot-actionbar", "seconds", left));
                }
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L));
    }

    /** Duel baslamadan iptal (geri sayimda cikis/olum). Kazanan yok. */
    private void cancelDuel(DuelInstance duel, String msgKey, UUID quitter) {
        duel.setPhase(DuelInstance.Phase.ENDED);
        duel.cancelTasks();
        for (UUID u : new UUID[]{duel.getP1(), duel.getP2()}) {
            if (byPlayer.get(u) != duel) continue;
            detach(u);
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                boolean quitting = u.equals(quitter);
                if (!quitting) Msg.send(p, msgKey);
                if (!p.isDead()) restoreAndReturn(p, !quitting);
                else pendingReturn.add(u);
            }
        }
        releaseIfEmpty(duel);
    }

    private void detach(UUID u) {
        byPlayer.remove(u);
    }

    private void releaseIfEmpty(DuelInstance duel) {
        if (byPlayer.get(duel.getP1()) == duel || byPlayer.get(duel.getP2()) == duel) return;
        if (!duels.remove(duel)) return;
        duel.cancelTasks();
        plugin.getArenaManager().release(duel.getArena());
    }

    private void saveHistory(DuelInstance duel, Player winner, Player loser) {
        int duration = duel.getFightStart() == 0 ? 0 : (int) ((System.currentTimeMillis() - duel.getFightStart()) / 1000L);
        plugin.getDatabase().saveHistory(new Database.DuelRecord(0, null, duel.getType().name(),
                duel.getP1().toString(), duel.getP2().toString(),
                winner == null ? null : winner.getUniqueId().toString(),
                loser == null ? null : loser.getUniqueId().toString(),
                duel.getArena().getName(), duel.getKitName(),
                duel.getSettings() == null ? null : duel.getSettings().toJson(),
                duel.getSnapshots().get(duel.getP1()), duel.getSnapshots().get(duel.getP2()),
                duration, duel.isItemsDrop()));
    }

    private String snapshot(Player p) {
        try {
            return InventorySerializer.toBase64(p.getInventory().getContents());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Envanter kaydedilemedi: " + p.getName(), ex);
            return null;
        }
    }

    // ================================================================== Geri donus

    private void restoreAndReturn(Player p) {
        restoreAndReturn(p, true);
    }

    /** Kaydedilen durumu geri yukler, iyilestirir ve lobiye yollar. */
    private void restoreAndReturn(Player p, boolean runHubCommand) {
        UUID u = p.getUniqueId();
        PlayerState st = states.remove(u);
        File f = stateFile(u);
        if (st == null && f.exists()) {
            try {
                st = PlayerState.load(f);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE, "Kayitli envanter okunamadi: " + p.getName(), ex);
            }
        }
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setFireTicks(0);
        p.setFallDistance(0f);
        if (!p.isDead()) heal(p);
        if (st != null) st.restore(p);
        if (f.exists() && !f.delete()) {
            plugin.getLogger().warning("Durum dosyasi silinemedi: " + f.getName());
        }
        sendToLobby(p, runHubCommand);
    }

    public void sendToLobby(Player p) {
        sendToLobby(p, true);
    }

    /**
     * Lobiye gonderir: lobby ayarliysa oraya isinlar, hub-command ayarliysa calistirir.
     * Ikisi de yoksa / ise yaramazsa ana dunya spawn'ina isinlar.
     */
    public void sendToLobby(Player p, boolean runHubCommand) {
        String cmd = plugin.getConfig().getString("settings.hub-command", "");
        Location lobby = plugin.getLobbyLocation();
        boolean enabled = plugin.isEnabled();
        boolean hasCmd = runHubCommand && enabled && cmd != null && !cmd.isBlank();
        if (lobby != null) {
            p.teleport(lobby);
        } else if (!hasCmd) {
            p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
        if (hasCmd) {
            p.performCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
        }
        if (!enabled) return;
        // Hala arenadaysa (komut yok/calismadi) yedek konuma
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !p.isDead() && !isInDuel(p.getUniqueId())
                    && plugin.getArenaManager().getArenaAt(p.getLocation()) != null) {
                Location fallback = lobby != null ? lobby : Bukkit.getWorlds().get(0).getSpawnLocation();
                p.teleport(fallback);
            }
        }, 5L);
    }

    private File stateFile(UUID u) {
        return new File(stateFolder, u + ".yml");
    }

    private void persistState(UUID u, PlayerState st) {
        try {
            st.save(stateFile(u));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Envanter yedegi yazilamadi: " + u, ex);
        }
    }

    // ================================================================== Sinir kontrolu

    private void borderTick() {
        if (byPlayer.isEmpty()) return;
        for (Map.Entry<UUID, DuelInstance> en : byPlayer.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || p.isDead()) continue;
            DuelInstance d = en.getValue();
            if (!d.getArena().isInside(p.getLocation())) {
                Location spawn = d.getP1().equals(p.getUniqueId()) ? d.getArena().getSpawn1() : d.getArena().getSpawn2();
                if (spawn != null) {
                    p.teleport(spawn);
                    Msg.send(p, "border-warning");
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                }
            }
        }
    }

    // ================================================================== Kapanis

    public void shutdown() {
        if (borderTask != null) borderTask.cancel();
        for (DuelRequest r : new ArrayList<>(requestsByTarget.values())) removeRequest(r);
        for (DuelInstance d : new ArrayList<>(duels)) {
            d.setPhase(DuelInstance.Phase.ENDED);
            d.cancelTasks();
        }
        for (Iterator<Map.Entry<UUID, DuelInstance>> it = byPlayer.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, DuelInstance> en = it.next();
            it.remove();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null && !p.isDead()) {
                Msg.send(p, "duel-cancelled-reload");
                restoreAndReturn(p);
            }
        }
        duels.clear();
        // Olu oyuncularin kit envanteri states/ klasorunde kaliyor -> girislerinde geri yuklenir
        for (Map.Entry<UUID, PlayerState> en : states.entrySet()) {
            if (en.getValue().hasInventory()) persistState(en.getKey(), en.getValue());
        }
    }
}
