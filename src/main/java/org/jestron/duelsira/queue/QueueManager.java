package org.jestron.duelsira.queue;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.arena.Arena;
import org.jestron.duelsira.duel.DuelInstance;
import org.jestron.duelsira.duel.DuelManager;
import org.jestron.duelsira.gui.InventoryViewMenu;
import org.jestron.duelsira.hook.WorldGuardHook;
import org.jestron.duelsira.util.Msg;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Duel sirasi.
 * - Siraya girmek/kalmak icin WorldGuard pvp=deny bolgede olmak gerekir.
 * - Eslesen iki oyuncuya sohbette [EVET] [HAYIR] [ENVANTERI GOR] sorulur.
 * - Ikisi de kabul ederse rastgele bos arenada kuralsiz duel baslar.
 * - Reddeden siradan cikar; karsi taraf siranin basina geri alinir.
 * - Reddeden, reddettigi oyuncuyla en az bir kez baska biriyle eslesmeden tekrar eslesmez.
 * - Sure icinde yanit vermeyen taraf(lar) siradan cikarilir.
 * Tum metotlar ana thread'den cagrilir.
 */
public final class QueueManager {

    public static final class Match {
        final UUID a, b;
        final Arena arena;
        final Set<UUID> accepted = new HashSet<>(2);
        BukkitTask timeout;
        boolean closed;

        Match(UUID a, UUID b, Arena arena) {
            this.a = a;
            this.b = b;
            this.arena = arena;
        }

        public UUID other(UUID u) {
            return a.equals(u) ? b : a;
        }

        public boolean isClosed() {
            return closed;
        }
    }

    private final DuelSira plugin;
    private final WorldGuardHook worldGuard;
    private final LinkedList<UUID> queue = new LinkedList<>();
    private final Set<UUID> queued = new HashSet<>();
    private final Map<UUID, Match> matches = new HashMap<>();
    /** oyuncu -> reddettigi (ya da yanit vermedigi) oyuncu */
    private final Map<UUID, UUID> declineBlock = new HashMap<>();
    private final Map<UUID, Long> clickCooldown = new HashMap<>();
    private BukkitTask tickTask;
    private boolean matchScheduled;
    private int tickCounter;

    public QueueManager(DuelSira plugin, WorldGuardHook worldGuard) {
        this.plugin = plugin;
        this.worldGuard = worldGuard;
    }

    public void startTasks() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    // ================================================================== Sorgular

    public boolean isQueued(UUID u) {
        return queued.contains(u);
    }

    public boolean isInMatch(UUID u) {
        return matches.containsKey(u);
    }

    /** Hareket dinleyicisinin hizli filtresi. */
    public boolean isTracked(UUID u) {
        return queued.contains(u) || matches.containsKey(u);
    }

    public boolean isEmpty() {
        return queued.isEmpty() && matches.isEmpty();
    }

    public int size() {
        return queue.size();
    }

    // ================================================================== Giris / cikis

    /** NPC'ye sag tik: sirada degilse girer, siradaysa cikar. */
    public void handleNpcClick(Player p) {
        long now = System.currentTimeMillis();
        long cd = Math.max(0L, plugin.getConfig().getLong("queue.click-cooldown-ms", 1000L));
        Long last = clickCooldown.get(p.getUniqueId());
        if (last != null && now - last < cd) return;
        clickCooldown.put(p.getUniqueId(), now);

        UUID u = p.getUniqueId();
        if (matches.containsKey(u)) {
            Msg.send(p, "queue-respond-first");
            return;
        }
        if (queued.contains(u)) {
            leave(p);
            return;
        }
        join(p);
    }

    public void join(Player p) {
        UUID u = p.getUniqueId();
        if (!p.hasPermission("duelsira.use")) {
            Msg.send(p, "no-permission");
            return;
        }
        if (queued.contains(u)) {
            Msg.send(p, "queue-already");
            return;
        }
        if (matches.containsKey(u)) {
            Msg.send(p, "queue-respond-first");
            return;
        }
        DuelManager dm = plugin.getDuelManager();
        if (dm.isInDuel(u)) {
            Msg.send(p, "you-are-busy");
            return;
        }
        if (!worldGuard.isPvpDenied(p)) {
            Msg.send(p, "queue-need-safe-region");
            return;
        }
        if (!plugin.getArenaManager().hasReadyArena()) {
            Msg.send(p, "queue-no-arena");
            return;
        }
        queue.addLast(u);
        queued.add(u);
        Msg.send(p, "queue-joined", "size", queue.size());
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
        if (!plugin.getArenaManager().hasFreeArena()) {
            Msg.send(p, "queue-arenas-full");
        }
        tryMatch();
    }

    public void leave(Player p) {
        UUID u = p.getUniqueId();
        Match m = matches.get(u);
        if (m != null) {
            Msg.send(p, "queue-left");
            cancelMatchByLeaver(m, u);
            return;
        }
        if (removeFromQueue(u)) {
            Msg.send(p, "queue-left");
        } else {
            Msg.send(p, "queue-not-in");
        }
    }

    private boolean removeFromQueue(UUID u) {
        if (!queued.remove(u)) return false;
        queue.remove(u);
        return true;
    }

    /** Normal duel basladiginda: siradan/eslesmeden sessizce cikar. */
    public void removeBecauseDuelStarted(Player p) {
        UUID u = p.getUniqueId();
        Match m = matches.get(u);
        if (m != null) {
            cancelMatchByLeaver(m, u);
            return;
        }
        if (removeFromQueue(u)) {
            Msg.send(p, "queue-left-duel");
        }
    }

    public void handleQuit(Player p) {
        UUID u = p.getUniqueId();
        clickCooldown.remove(u);
        Match m = matches.get(u);
        if (m != null) {
            cancelMatchByLeaver(m, u);
        }
        removeFromQueue(u);
    }

    /** Oyuncu hareket etti / isinlandi: pvp acik bolgeye girdiyse siradan at. */
    public void checkRegion(Player p) {
        checkRegion(p, p.getLocation());
    }

    public void checkRegion(Player p, org.bukkit.Location loc) {
        UUID u = p.getUniqueId();
        if (!isTracked(u)) return;
        if (worldGuard.isPvpDenied(p, loc)) return;
        Match m = matches.get(u);
        if (m != null) {
            Msg.send(p, "queue-left-pvp");
            cancelMatchByLeaver(m, u);
            return;
        }
        if (removeFromQueue(u)) {
            Msg.send(p, "queue-left-pvp");
        }
    }

    // ================================================================== Eslesme

    public void onArenaFreed() {
        if (matchScheduled || !plugin.isEnabled()) return;
        matchScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            matchScheduled = false;
            tryMatch();
        });
    }

    private boolean compatible(UUID x, UUID y) {
        return !y.equals(declineBlock.get(x)) && !x.equals(declineBlock.get(y));
    }

    public void tryMatch() {
        while (queue.size() >= 2) {
            // gecersiz kayitlari temizle
            for (Iterator<UUID> it = queue.iterator(); it.hasNext(); ) {
                UUID u = it.next();
                Player p = Bukkit.getPlayer(u);
                if (p == null || plugin.getDuelManager().isInDuel(u)) {
                    it.remove();
                    queued.remove(u);
                }
            }
            if (queue.size() < 2) return;

            UUID first = null, second = null;
            outer:
            for (int i = 0; i < queue.size(); i++) {
                UUID x = queue.get(i);
                for (ListIterator<UUID> it = queue.listIterator(i + 1); it.hasNext(); ) {
                    UUID y = it.next();
                    if (compatible(x, y)) {
                        first = x;
                        second = y;
                        break outer;
                    }
                }
            }
            if (first == null) return;

            Arena arena = plugin.getArenaManager().findRandomFree();
            if (arena == null) return; // arenalar dolu: periyodik action bar uyarir

            removeFromQueue(first);
            removeFromQueue(second);
            propose(first, second, arena);
        }
    }

    private void propose(UUID ua, UUID ub, Arena arena) {
        Player pa = Bukkit.getPlayer(ua);
        Player pb = Bukkit.getPlayer(ub);
        if (pa == null || pb == null) return;

        plugin.getArenaManager().reserve(arena);
        // Baska biriyle eslesti -> eski red engeli kalkar
        declineBlock.remove(ua);
        declineBlock.remove(ub);

        Match m = new Match(ua, ub, arena);
        matches.put(ua, m);
        matches.put(ub, m);

        int seconds = Math.max(5, plugin.getConfig().getInt("queue.response-time", 15));
        m.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> onTimeout(m), seconds * 20L);

        sendProposal(pa, pb, seconds);
        sendProposal(pb, pa, seconds);
    }

    private void sendProposal(Player to, Player opponent, int seconds) {
        Msg.send(to, "queue-matched", "player", opponent.getName());
        to.spigot().sendMessage(DuelManager.buttons(
                DuelManager.button(Msg.raw("button-yes"), "/duelsira evet", Msg.raw("button-yes-hover")),
                DuelManager.button(Msg.raw("button-no"), "/duelsira hayir", Msg.raw("button-no-hover")),
                DuelManager.button(Msg.raw("button-inventory"), "/duelsira envanter",
                        Msg.raw("button-inventory-hover", "player", opponent.getName()))));
        Msg.send(to, "queue-respond-time", "seconds", seconds);
        to.playSound(to.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    public void respond(Player p, boolean accept) {
        UUID u = p.getUniqueId();
        Match m = matches.get(u);
        if (m == null || m.closed) {
            Msg.send(p, "queue-no-match");
            return;
        }
        Player other = Bukkit.getPlayer(m.other(u));
        if (!accept) {
            closeMatch(m);
            declineBlock.put(u, m.other(u));
            Msg.send(p, "queue-you-declined");
            if (other != null) {
                Msg.send(other, "queue-opponent-declined", "player", p.getName());
                requeueFront(other);
            }
            plugin.getArenaManager().unreserve(m.arena);
            tryMatch();
            return;
        }
        if (!m.accepted.add(u)) {
            Msg.send(p, "queue-already-accepted");
            return;
        }
        Msg.send(p, "queue-you-accepted");
        if (other != null) Msg.send(other, "queue-opponent-accepted", "player", p.getName());
        if (m.accepted.size() == 2) startMatch(m);
    }

    private void onTimeout(Match m) {
        if (m.closed) return;
        closeMatch(m);
        for (UUID u : new UUID[]{m.a, m.b}) {
            Player p = Bukkit.getPlayer(u);
            if (m.accepted.contains(u)) {
                if (p != null) {
                    Msg.send(p, "queue-opponent-timeout");
                    requeueFront(p);
                }
            } else {
                declineBlock.put(u, m.other(u));
                if (p != null) Msg.send(p, "queue-timeout-removed");
            }
        }
        plugin.getArenaManager().unreserve(m.arena);
        tryMatch();
    }

    /** Eslesmedeki bir oyuncu cikti / pvp bolgesine girdi / baska duel'e girdi. */
    private void cancelMatchByLeaver(Match m, UUID leaver) {
        if (m.closed) return;
        closeMatch(m);
        Player other = Bukkit.getPlayer(m.other(leaver));
        if (other != null) {
            Msg.send(other, "queue-opponent-left");
            requeueFront(other);
        }
        plugin.getArenaManager().unreserve(m.arena);
        tryMatch();
    }

    private void startMatch(Match m) {
        closeMatch(m);
        Player pa = Bukkit.getPlayer(m.a);
        Player pb = Bukkit.getPlayer(m.b);
        boolean started = false;
        if (pa != null && pb != null && m.arena.getState() == Arena.State.RESERVED) {
            started = plugin.getDuelManager().startDuel(pa, pb, m.arena, null,
                    plugin.getConfig().getBoolean("queue.items-drop", true), null, DuelInstance.Type.QUEUE);
        }
        if (!started) {
            if (m.arena.getState() == Arena.State.RESERVED) {
                plugin.getArenaManager().unreserve(m.arena);
            }
            for (Player p : new Player[]{pa, pb}) {
                if (p == null) continue;
                Msg.send(p, "queue-start-failed");
                requeueFront(p);
            }
            tryMatch();
        }
    }

    private void closeMatch(Match m) {
        m.closed = true;
        if (m.timeout != null) {
            m.timeout.cancel();
            m.timeout = null;
        }
        matches.remove(m.a, m);
        matches.remove(m.b, m);
        // Acik envanter goruntuleme menulerini kapat
        for (UUID u : new UUID[]{m.a, m.b}) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
            if (h instanceof InventoryViewMenu v && v.isLiveView()) {
                p.closeInventory();
            }
        }
    }

    private void requeueFront(Player p) {
        UUID u = p.getUniqueId();
        if (!p.isOnline() || queued.contains(u) || matches.containsKey(u) || plugin.getDuelManager().isInDuel(u)) return;
        if (!worldGuard.isPvpDenied(p)) {
            Msg.send(p, "queue-left-pvp");
            return;
        }
        queue.addFirst(u);
        queued.add(u);
        Msg.send(p, "queue-requeued");
    }

    // ================================================================== Envanter goruntuleme

    public void openOpponentInventory(Player p) {
        Match m = matches.get(p.getUniqueId());
        if (m == null || m.closed) {
            Msg.send(p, "queue-no-match");
            return;
        }
        Player other = Bukkit.getPlayer(m.other(p.getUniqueId()));
        if (other == null) {
            Msg.send(p, "player-not-found");
            return;
        }
        InventoryViewMenu.openLive(p, other);
    }

    // ================================================================== Periyodik

    private void tick() {
        if (queued.isEmpty() && matches.isEmpty()) return;
        tickCounter++;
        // Bolge kontrolu (hareket dinleyicisinin kacirdigi durumlar, bayrak degisiklikleri)
        for (UUID u : new HashSet<>(queued)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) {
                removeFromQueue(u);
                continue;
            }
            checkRegion(p);
        }
        for (UUID u : new HashSet<>(matches.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) checkRegion(p);
        }
        tryMatch();
        if (tickCounter % 2 != 0) return;
        boolean free = plugin.getArenaManager().hasFreeArena();
        int pos = 0;
        int size = queue.size();
        for (UUID u : queue) {
            pos++;
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            if (!free) {
                Msg.actionBar(p, Msg.raw("queue-actionbar-full", "size", size));
            } else {
                Msg.actionBar(p, Msg.raw("queue-actionbar", "position", pos, "size", size));
            }
        }
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        for (Match m : new HashSet<>(matches.values())) {
            m.closed = true;
            if (m.timeout != null) m.timeout.cancel();
        }
        matches.clear();
        queue.clear();
        queued.clear();
    }
}
