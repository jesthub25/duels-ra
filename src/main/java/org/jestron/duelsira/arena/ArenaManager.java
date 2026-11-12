package org.jestron.duelsira.arena;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.storage.Database;
import org.jestron.duelsira.storage.Database.StoredLocation;
import org.jestron.duelsira.util.Msg;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Arena yonetimi.
 * <p>
 * Arena olusturulurken bolge WorldEdit .schem olarak kaydedilir. Her duel sonunda <b>akilli sifirlama</b> yapilir:
 * <ol>
 *   <li>Arena chunk'larinin anlik goruntusu (ChunkSnapshot) alinir (ana thread, cok hizli).</li>
 *   <li>Goruntu, schem'in onbellekteki "olmasi gereken" haliyle <b>arka plan thread'inde</b> karsilastirilir.</li>
 *   <li>Hicbir blok degismediyse <b>hicbir sey yapistirilmaz</b>.</li>
 *   <li>Degisiklik varsa sadece degisen 16x16x16 bolumler yapistirilir; cok fazlaysa tek seferde tam yapistirma.</li>
 *   <li>Sandik/tabela gibi NBT degisiklikleri olaylarla takip edilir; sifirlama sirasinda akan sivi vb. olursa tekrar kontrol edilir.</li>
 *   <li>Yapistirmalar tick'lere yayilir (ayni anda biten duellolar lag yapmaz), guvenlik icin her N sifirlamada bir tam yapistirma.</li>
 * </ol>
 * Tum durum degisiklikleri ana thread'de yapilir.
 */
public final class ArenaManager {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final long MAX_SMART_VOLUME = 64L * 1024 * 1024; // ~128 MB indeks ust siniri

    private final DuelSira plugin;
    private final File folder;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    /** chunk -> o chunk'a degen arenalar (O(1) konum sorgusu) */
    private final Map<Long, List<Arena>> chunkIndex = new HashMap<>();

    private final ArrayDeque<Arena> pendingChecks = new ArrayDeque<>();
    private final ArrayDeque<PasteJob> pendingPastes = new ArrayDeque<>();
    private int resettingCount;
    private BukkitTask resetTask;

    public ArenaManager(DuelSira plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "arenas");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().severe("arenas klasoru olusturulamadi!");
        }
    }

    // ================================================================== Yukleme

    public void load() {
        arenas.clear();
        for (Database.ArenaRow row : plugin.getDatabase().loadArenasBlocking()) {
            Arena a = new Arena(row.name, row.world, row.minX, row.minY, row.minZ, row.maxX, row.maxY, row.maxZ, folder);
            a.setSpawn1(row.spawn1);
            a.setSpawn2(row.spawn2);
            arenas.put(row.name, a);
        }
        rebuildIndex();
        plugin.getLogger().info(arenas.size() + " arena yuklendi.");
    }

    public void startTasks() {
        resetTask = Bukkit.getScheduler().runTaskTimer(plugin, this::resetTick, 1L, 1L);
    }

    /** Sunucu tamamen acildiktan sonra: schem'leri onbellege al, kirli arenalari sifirla. */
    public void postStartup() {
        for (Arena a : arenas.values()) {
            if (!a.getSchemFile().exists()) {
                plugin.getLogger().warning("Arena '" + a.getName() + "' icin schem bulunamadi: " + a.getSchemFile().getName()
                        + " (arenayi yeniden olusturun)");
                continue;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Clipboard c = readSchem(a.getSchemFile());
                if (c == null) return;
                if (a.getClipboard() == null) a.setClipboard(c);
                buildExpected(a, a.getClipboard());
            });
        }
        // Sunucu duel ortasinda kapandiysa arena kirli kalmis olabilir -> tam sifirlama
        for (Arena a : arenas.values()) {
            if (a.getDirtyFile().exists() && a.getState() == Arena.State.FREE) {
                plugin.getLogger().info("Arena '" + a.getName() + "' onceki oturumdan kirli kalmis, sifirlaniyor.");
                a.forceFull = true;
                beginReset(a);
            }
        }
    }

    private Clipboard readSchem(File file) {
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) {
            plugin.getLogger().severe("Schem formati taninmadi: " + file.getName());
            return null;
        }
        try (FileInputStream in = new FileInputStream(file); ClipboardReader reader = format.getReader(in)) {
            return reader.read();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Schem okunamadi: " + file.getName(), e);
            return null;
        }
    }

    /**
     * Schem'den karsilastirma tablosu olusturur (arka plan thread'i).
     * Palet BlockData'ya ana thread'de cevrilir.
     */
    private void buildExpected(Arena a, Clipboard clip) {
        if (clip == null) return;
        BlockVector3 cmin = clip.getRegion().getMinimumPoint();
        BlockVector3 cdim = clip.getRegion().getMaximumPoint().subtract(cmin).add(1, 1, 1);
        int sx = a.getSizeX(), sy = a.getSizeY(), sz = a.getSizeZ();
        if (cdim.getBlockX() != sx || cdim.getBlockY() != sy || cdim.getBlockZ() != sz) {
            plugin.getLogger().warning("Arena '" + a.getName() + "' schem boyutu arena ile uyusmuyor; akilli sifirlama kapali, tam yapistirma kullanilacak.");
            return;
        }
        long volume = (long) sx * sy * sz;
        if (volume > MAX_SMART_VOLUME) {
            plugin.getLogger().warning("Arena '" + a.getName() + "' cok buyuk; akilli sifirlama kapali.");
            return;
        }
        Map<com.sk89q.worldedit.world.block.BlockState, Integer> ids = new HashMap<>();
        List<com.sk89q.worldedit.world.block.BlockState> states = new ArrayList<>();
        char[] idx = new char[(int) volume];
        int i = 0;
        int bx = cmin.getBlockX(), by = cmin.getBlockY(), bz = cmin.getBlockZ();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    com.sk89q.worldedit.world.block.BlockState s = clip.getBlock(BlockVector3.at(bx + x, by + y, bz + z));
                    Integer id = ids.get(s);
                    if (id == null) {
                        id = states.size();
                        if (id > Character.MAX_VALUE) {
                            plugin.getLogger().warning("Arena '" + a.getName() + "' cok fazla farkli blok iceriyor; akilli sifirlama kapali.");
                            return;
                        }
                        ids.put(s, id);
                        states.add(s);
                    }
                    idx[i++] = (char) id.intValue();
                }
            }
        }
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (a.getClipboard() != clip) return; // bu arada degisti
            BlockData[] palette = new BlockData[states.size()];
            try {
                for (int p = 0; p < palette.length; p++) palette[p] = BukkitAdapter.adapt(states.get(p));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Arena '" + a.getName() + "' blok paleti cevrilemedi; tam yapistirma kullanilacak.", t);
                return;
            }
            a.setExpected(new Arena.Expected(palette, idx));
            debug("Arena '" + a.getName() + "' akilli sifirlama hazir (" + palette.length + " farkli blok, " + volume + " blok).");
        });
    }

    // ================================================================== Yonetim

    public void createArena(Player player, String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            Msg.send(player, "arena-invalid-name");
            return;
        }
        if (arenas.containsKey(name)) {
            Msg.send(player, "arena-exists", "arena", name);
            return;
        }
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(player.getWorld());
        Region selection;
        try {
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
            selection = session.getSelection(weWorld);
        } catch (IncompleteRegionException e) {
            Msg.send(player, "arena-no-selection");
            return;
        }
        if (selection == null) {
            Msg.send(player, "arena-no-selection");
            return;
        }
        BlockVector3 min = selection.getMinimumPoint();
        BlockVector3 max = selection.getMaximumPoint();
        CuboidRegion region = new CuboidRegion(weWorld, min, max);

        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(min);
        try (EditSession es = WorldEdit.getInstance().newEditSession(weWorld)) {
            ForwardExtentCopy copy = new ForwardExtentCopy(es, region, clipboard, min);
            copy.setCopyingEntities(false);
            Operations.complete(copy);
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.SEVERE, "Arena kopyalanamadi: " + name, e);
            Msg.send(player, "arena-create-error");
            return;
        }

        Arena arena = new Arena(name, player.getWorld().getName(), min.getBlockX(), min.getBlockY(), min.getBlockZ(),
                max.getBlockX(), max.getBlockY(), max.getBlockZ(), folder);
        arena.setClipboard(clipboard);
        arenas.put(name, arena);
        rebuildIndex();
        plugin.getDatabase().insertArena(name, arena.getWorldName(), arena.getMinX(), arena.getMinY(), arena.getMinZ(),
                arena.getMaxX(), arena.getMaxY(), arena.getMaxZ());

        File file = arena.getSchemFile();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (FileOutputStream out = new FileOutputStream(file);
                 ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(out)) {
                writer.write(clipboard);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Arena schem kaydedilemedi: " + name, e);
            }
            buildExpected(arena, clipboard);
        });
        Msg.send(player, "arena-created", "arena", name, "blocks", arena.getVolume());
    }

    public void deleteArena(Player player, String rawName) {
        String name = rawName.toLowerCase(Locale.ROOT);
        Arena arena = arenas.get(name);
        if (arena == null) {
            Msg.send(player, "arena-not-found", "arena", name);
            return;
        }
        if (arena.getState() != Arena.State.FREE) {
            Msg.send(player, "arena-busy", "arena", name);
            return;
        }
        arenas.remove(name);
        rebuildIndex();
        plugin.getDatabase().deleteArena(name);
        if (arena.getSchemFile().exists() && !arena.getSchemFile().delete()) {
            plugin.getLogger().warning("Schem silinemedi: " + arena.getSchemFile());
        }
        if (arena.getDirtyFile().exists() && !arena.getDirtyFile().delete()) {
            plugin.getLogger().warning("Dirty dosyasi silinemedi: " + arena.getDirtyFile());
        }
        Msg.send(player, "arena-deleted", "arena", name);
    }

    public void setSpawn(Player player, String rawName, int which) {
        Arena arena = getArena(rawName);
        if (arena == null) {
            Msg.send(player, "arena-not-found", "arena", rawName);
            return;
        }
        Location loc = player.getLocation();
        if (!arena.isInside(loc)) {
            Msg.send(player, "arena-spawn-outside");
            return;
        }
        StoredLocation sl = StoredLocation.of(loc);
        if (which == 1) arena.setSpawn1(sl);
        else arena.setSpawn2(sl);
        plugin.getDatabase().updateSpawn(arena.getName(), which, sl);
        Msg.send(player, "arena-spawn-set", "arena", arena.getName(), "spawn", which);
    }

    /** /duel arena <isim> sifirla : bos arenayi zorla tam sifirlar. */
    public void forceReset(Player player, String rawName) {
        Arena arena = getArena(rawName);
        if (arena == null) {
            Msg.send(player, "arena-not-found", "arena", rawName);
            return;
        }
        if (arena.getState() != Arena.State.FREE) {
            Msg.send(player, "arena-busy", "arena", arena.getName());
            return;
        }
        arena.forceFull = true;
        beginReset(arena);
        Msg.send(player, "arena-reset-queued", "arena", arena.getName());
    }

    // ================================================================== Sorgular

    public Arena getArena(String name) {
        return name == null ? null : arenas.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Arena> getArenas() {
        return Collections.unmodifiableCollection(arenas.values());
    }

    public List<String> getArenaNames() {
        return new ArrayList<>(arenas.keySet());
    }

    public boolean hasReadyArena() {
        for (Arena a : arenas.values()) if (a.isReady()) return true;
        return false;
    }

    public boolean hasFreeArena() {
        for (Arena a : arenas.values()) if (a.isFree() && a.isReady()) return true;
        return false;
    }

    /** Rastgele bos ve hazir arena, yoksa null. */
    public Arena findRandomFree() {
        List<Arena> free = new ArrayList<>();
        for (Arena a : arenas.values()) {
            if (a.isFree() && a.isReady()) free.add(a);
        }
        if (free.isEmpty()) return null;
        return free.get(ThreadLocalRandom.current().nextInt(free.size()));
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private void rebuildIndex() {
        chunkIndex.clear();
        for (Arena a : arenas.values()) {
            for (int cx = a.getMinX() >> 4; cx <= a.getMaxX() >> 4; cx++) {
                for (int cz = a.getMinZ() >> 4; cz <= a.getMaxZ() >> 4; cz++) {
                    chunkIndex.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>(1)).add(a);
                }
            }
        }
    }

    /** O(1): blok koordinatindaki arena. */
    public Arena arenaAt(World w, int x, int y, int z) {
        if (w == null || chunkIndex.isEmpty()) return null;
        List<Arena> list = chunkIndex.get(chunkKey(x >> 4, z >> 4));
        if (list == null) return null;
        String wn = w.getName();
        for (Arena a : list) {
            if (a.getWorldName().equals(wn) && a.contains(x, y, z)) return a;
        }
        return null;
    }

    public Arena arenaAt(Block b) {
        return b == null ? null : arenaAt(b.getWorld(), b.getX(), b.getY(), b.getZ());
    }

    public Arena getArenaAt(Location loc) {
        if (loc == null) return null;
        return arenaAt(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    // ================================================================== Durum

    private void changeState(Arena a, Arena.State s) {
        if (a.getState() == s) return;
        if (a.getState() == Arena.State.RESETTING) resettingCount--;
        if (s == Arena.State.RESETTING) resettingCount++;
        a.setState(s);
    }

    /** Herhangi bir arena sifirlaniyor mu (dinleyicilerin hizli filtresi). */
    public boolean hasResetting() {
        return resettingCount > 0;
    }

    public void reserve(Arena a) {
        changeState(a, Arena.State.RESERVED);
    }

    /** Rezervasyonu iptal eder (duel baslamadan). Arena bozulmadigi icin sifirlama gerekmez. */
    public void unreserve(Arena a) {
        if (a.getState() == Arena.State.RESERVED) {
            changeState(a, Arena.State.FREE);
            plugin.getQueueManager().onArenaFreed();
        }
    }

    public void occupy(Arena a) {
        changeState(a, Arena.State.IN_USE);
        if (a.getDirtyFile().exists()) return;
        try {
            if (!a.getDirtyFile().createNewFile()) {
                plugin.getLogger().fine("dirty dosyasi olusturulamadi: " + a.getName());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("dirty dosyasi olusturulamadi: " + a.getName());
        }
    }

    /** Duel bitti: arena akilli sifirlama kuyruguna girer, bitince bosa cikar. */
    public void release(Arena a) {
        if (arenas.get(a.getName()) != a) return; // silinmis
        beginReset(a);
    }

    private void beginReset(Arena a) {
        changeState(a, Arena.State.RESETTING);
        a.resetAttempt = 0;
        if (!pendingChecks.contains(a)) pendingChecks.add(a);
    }

    // ------------------------------------------------------------------ olay takibi (ArenaListener)

    /** Sandik/tabela vb. NBT degisikligi olabilecek blok. */
    public void markNbtChange(Block b) {
        Arena a = arenaAt(b);
        if (a != null) a.markNbtDirty(b.getX(), b.getY(), b.getZ());
    }

    public void markNbtChange(Location l) {
        if (l == null || l.getWorld() == null) return;
        Arena a = arenaAt(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        if (a != null) a.markNbtDirty(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    /** Sifirlanan arenada blok degisti (akan sivi, dusen blok, patlama...). */
    public void markChangeDuringReset(Block b) {
        if (resettingCount == 0 || b == null) return;
        Arena a = arenaAt(b);
        if (a != null && a.getState() == Arena.State.RESETTING) a.changedDuringReset = true;
    }

    // ------------------------------------------------------------------ sifirlama hatti

    private static final class PasteJob {
        final Arena arena;
        final Set<Long> sections; // null = tam yapistirma

        PasteJob(Arena arena, Set<Long> sections) {
            this.arena = arena;
            this.sections = sections;
        }
    }

    private void resetTick() {
        if (pendingChecks.isEmpty() && pendingPastes.isEmpty()) return;
        int budget = Math.max(1, plugin.getConfig().getInt("arena-reset.max-operations-per-tick", 1));
        while (budget > 0 && !pendingPastes.isEmpty()) {
            runPaste(pendingPastes.poll());
            budget--;
        }
        while (budget > 0 && !pendingChecks.isEmpty()) {
            startCheck(pendingChecks.poll());
            budget--;
        }
    }

    private void startCheck(Arena a) {
        if (arenas.get(a.getName()) != a || a.getState() != Arena.State.RESETTING) return;
        World world = a.getWorld();
        if (world == null) {
            plugin.getLogger().warning("Arena '" + a.getName() + "' dunyasi yuklu degil, sifirlanamadi.");
            finish(a, false);
            return;
        }
        removeEntities(a, world);
        Clipboard clip = ensureClipboard(a);
        if (clip == null) {
            plugin.getLogger().severe("Arena '" + a.getName() + "' icin schem yok, arena sifirlanamadi!");
            finish(a, false);
            return;
        }
        boolean smart = plugin.getConfig().getBoolean("arena-reset.smart", true);
        int fullEvery = plugin.getConfig().getInt("arena-reset.full-reset-every", 50);
        Arena.Expected exp = a.getExpected();
        if (a.forceFull || !smart || exp == null || (fullEvery > 0 && a.resetsSinceFull >= fullEvery)) {
            pendingPastes.add(new PasteJob(a, null));
            return;
        }
        // 1) Anlik goruntu (ana thread)
        long t0 = System.nanoTime();
        a.changedDuringReset = false;
        Map<Long, ChunkSnapshot> snaps = new HashMap<>();
        for (int cx = a.getMinX() >> 4; cx <= a.getMaxX() >> 4; cx++) {
            for (int cz = a.getMinZ() >> 4; cz <= a.getMaxZ() >> 4; cz++) {
                Chunk c = world.getChunkAt(cx, cz);
                snaps.put(chunkKey(cx, cz), c.getChunkSnapshot(false, false, false));
            }
        }
        long snapNs = System.nanoTime() - t0;
        // 2) Karsilastirma (arka plan)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long t1 = System.nanoTime();
            Set<Long> dirty;
            try {
                dirty = diff(a, exp, snaps);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Arena '" + a.getName() + "' karsilastirilamadi, tam yapistirma yapilacak.", t);
                dirty = null;
            }
            long diffNs = System.nanoTime() - t1;
            final Set<Long> result = dirty;
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> onDiffDone(a, result, snapNs, diffNs));
        });
    }

    /** Degisen bolumleri bulur. Bir bolumde ilk farkta o bolum biter (erken cikis). */
    private static Set<Long> diff(Arena a, Arena.Expected exp, Map<Long, ChunkSnapshot> snaps) {
        Set<Long> dirty = new HashSet<>();
        int minX = a.getMinX(), minY = a.getMinY(), minZ = a.getMinZ();
        int maxX = a.getMaxX(), maxY = a.getMaxY(), maxZ = a.getMaxZ();
        int sizeX = a.getSizeX(), sizeZ = a.getSizeZ();
        BlockData[] pal = exp.palette;
        char[] idx = exp.indices;
        for (int scx = minX >> 4; scx <= maxX >> 4; scx++) {
            for (int scz = minZ >> 4; scz <= maxZ >> 4; scz++) {
                ChunkSnapshot snap = snaps.get(chunkKey(scx, scz));
                int x0 = Math.max(minX, scx << 4), x1 = Math.min(maxX, (scx << 4) + 15);
                int z0 = Math.max(minZ, scz << 4), z1 = Math.min(maxZ, (scz << 4) + 15);
                for (int scy = minY >> 4; scy <= maxY >> 4; scy++) {
                    long key = Arena.sectionKey(scx, scy, scz);
                    if (snap == null) {
                        dirty.add(key);
                        continue;
                    }
                    int y0 = Math.max(minY, scy << 4), y1 = Math.min(maxY, (scy << 4) + 15);
                    section:
                    for (int y = y0; y <= y1; y++) {
                        int rowY = (y - minY) * sizeZ;
                        for (int z = z0; z <= z1; z++) {
                            int base = (rowY + (z - minZ)) * sizeX - minX;
                            for (int x = x0; x <= x1; x++) {
                                if (!pal[idx[base + x]].equals(snap.getBlockData(x & 15, y, z & 15))) {
                                    dirty.add(key);
                                    break section;
                                }
                            }
                        }
                    }
                }
            }
        }
        return dirty;
    }

    private void onDiffDone(Arena a, Set<Long> dirty, long snapNs, long diffNs) {
        if (arenas.get(a.getName()) != a || a.getState() != Arena.State.RESETTING) return;
        if (dirty == null) {
            pendingPastes.add(new PasteJob(a, null));
            return;
        }
        dirty.addAll(a.drainNbtDirty());
        if (dirty.isEmpty()) {
            if (a.changedDuringReset && a.resetAttempt < 3) {
                a.resetAttempt++;
                pendingChecks.add(a);
                return;
            }
            debug("Arena '" + a.getName() + "': degisiklik yok, yapistirma atlandi (goruntu " + ms(snapNs)
                    + " ms ana thread, karsilastirma " + ms(diffNs) + " ms arka plan)");
            finish(a, false);
            return;
        }
        double threshold = plugin.getConfig().getDouble("arena-reset.full-threshold", 0.6);
        int total = Math.max(1, a.sectionCount());
        if (dirty.size() >= threshold * total) {
            pendingPastes.add(new PasteJob(a, null));
        } else {
            pendingPastes.add(new PasteJob(a, dirty));
        }
    }

    private void runPaste(PasteJob job) {
        Arena a = job.arena;
        if (arenas.get(a.getName()) != a || a.getState() != Arena.State.RESETTING) return;
        World world = a.getWorld();
        Clipboard clip = a.getClipboard();
        if (world == null || clip == null) {
            finish(a, false);
            return;
        }
        a.changedDuringReset = false;
        long t0 = System.nanoTime();
        boolean ok = job.sections == null ? pasteFull(a, world, clip) : pasteSections(a, world, clip, job.sections);
        long took = System.nanoTime() - t0;
        if (!ok) {
            finish(a, false);
            return;
        }
        if (job.sections == null) {
            a.drainNbtDirty();
            a.resetsSinceFull = 0;
            a.forceFull = false;
            debug("Arena '" + a.getName() + "': tam yapistirma (" + ms(took) + " ms)");
        } else {
            debug("Arena '" + a.getName() + "': " + job.sections.size() + "/" + a.sectionCount()
                    + " bolum yeniden yuklendi (" + ms(took) + " ms)");
        }
        // Yapistirma sirasinda/sonrasinda akan sivi vb. -> tekrar kontrol
        if (a.changedDuringReset && a.resetAttempt < 3) {
            a.resetAttempt++;
            pendingChecks.add(a);
            return;
        }
        finish(a, job.sections == null);
    }

    private void finish(Arena a, boolean wasFull) {
        if (!wasFull) a.resetsSinceFull++;
        if (a.getDirtyFile().exists() && !a.getDirtyFile().delete()) {
            plugin.getLogger().fine("dirty dosyasi silinemedi: " + a.getName());
        }
        changeState(a, Arena.State.FREE);
        plugin.getQueueManager().onArenaFreed();
    }

    private Clipboard ensureClipboard(Arena a) {
        Clipboard clip = a.getClipboard();
        if (clip == null && a.getSchemFile().exists()) {
            clip = readSchem(a.getSchemFile());
            if (clip != null) {
                a.setClipboard(clip);
                Clipboard c = clip;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> buildExpected(a, c));
            }
        }
        return clip;
    }

    /** Yere dusen esyalar, oklar, tekneler, mobs vs. (oyuncu, cerceve, zirh standi, NPC haric). */
    private void removeEntities(Arena a, World world) {
        for (Entity e : world.getNearbyEntities(a.getBoundingBox())) {
            if (e instanceof Player || e instanceof Hanging || e instanceof ArmorStand) continue;
            if (plugin.getNpcManager().isNpc(e)) continue;
            e.remove();
        }
    }

    private EditSession newSession(World world) {
        EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world));
        try {
            es.setSideEffectApplier(SideEffectSet.defaults().with(SideEffect.NEIGHBORS, SideEffect.State.OFF));
        } catch (Throwable ignored) {
            // FAWE vb. desteklemiyorsa varsayilanla devam
        }
        return es;
    }

    private boolean pasteFull(Arena a, World world, Clipboard clip) {
        BlockVector3 arenaMin = BlockVector3.at(a.getMinX(), a.getMinY(), a.getMinZ());
        BlockVector3 to = arenaMin.subtract(clip.getRegion().getMinimumPoint()).add(clip.getOrigin());
        try (EditSession es = newSession(world)) {
            Operation op = new ClipboardHolder(clip).createPaste(es).to(to).ignoreAirBlocks(false).build();
            Operations.complete(op);
            return true;
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.SEVERE, "Arena sifirlanamadi: " + a.getName(), e);
            return false;
        }
    }

    private boolean pasteSections(Arena a, World world, Clipboard clip, Set<Long> sections) {
        BlockVector3 cmin = clip.getRegion().getMinimumPoint();
        int dx = cmin.getBlockX() - a.getMinX();
        int dy = cmin.getBlockY() - a.getMinY();
        int dz = cmin.getBlockZ() - a.getMinZ();
        try (EditSession es = newSession(world)) {
            for (long key : sections) {
                int sx = Arena.keyX(key), sy = Arena.keyY(key), sz = Arena.keyZ(key);
                int x0 = Math.max(a.getMinX(), sx << 4), x1 = Math.min(a.getMaxX(), (sx << 4) + 15);
                int y0 = Math.max(a.getMinY(), sy << 4), y1 = Math.min(a.getMaxY(), (sy << 4) + 15);
                int z0 = Math.max(a.getMinZ(), sz << 4), z1 = Math.min(a.getMaxZ(), (sz << 4) + 15);
                if (x0 > x1 || y0 > y1 || z0 > z1) continue;
                BlockVector3 from = BlockVector3.at(x0 + dx, y0 + dy, z0 + dz);
                BlockVector3 fromMax = BlockVector3.at(x1 + dx, y1 + dy, z1 + dz);
                ForwardExtentCopy copy = new ForwardExtentCopy(clip, new CuboidRegion(from, fromMax), from, es,
                        BlockVector3.at(x0, y0, z0));
                copy.setCopyingEntities(false);
                Operations.complete(copy);
            }
            return true;
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.SEVERE, "Arena bolumleri sifirlanamadi: " + a.getName(), e);
            return false;
        }
    }

    /** Plugin kapanirken: kullanimdaki / sirada bekleyen arenalari aninda tam sifirla. */
    public void resetAllInUse() {
        if (resetTask != null) resetTask.cancel();
        pendingChecks.clear();
        pendingPastes.clear();
        for (Arena a : arenas.values()) {
            if (a.getState() == Arena.State.IN_USE || a.getState() == Arena.State.RESETTING) {
                World w = a.getWorld();
                Clipboard clip = a.getClipboard();
                if (clip == null && a.getSchemFile().exists()) clip = readSchem(a.getSchemFile());
                if (w != null && clip != null) {
                    removeEntities(a, w);
                    if (pasteFull(a, w, clip) && a.getDirtyFile().exists() && !a.getDirtyFile().delete()) {
                        plugin.getLogger().fine("dirty dosyasi silinemedi: " + a.getName());
                    }
                }
            }
            changeState(a, Arena.State.FREE);
        }
    }

    private static long ms(long ns) {
        return ns / 1_000_000L;
    }

    private void debug(String s) {
        if (plugin.getConfig().getBoolean("settings.debug", false)) plugin.getLogger().info(s);
    }
}
