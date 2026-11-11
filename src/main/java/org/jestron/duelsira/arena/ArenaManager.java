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
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.storage.Database;
import org.jestron.duelsira.storage.Database.StoredLocation;
import org.jestron.duelsira.util.Msg;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Arena yonetimi. Arena olusturulurken bolge WorldEdit .schem olarak kaydedilir
 * ve her duel sonunda arena bu haline geri dondurulur.
 * Tum durum degisiklikleri ana thread'de yapilir.
 */
public final class ArenaManager {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    private final DuelSira plugin;
    private final File folder;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();

    public ArenaManager(DuelSira plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "arenas");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().severe("arenas klasoru olusturulamadi!");
        }
    }

    // ------------------------------------------------------------------ Yukleme

    public void load() {
        arenas.clear();
        for (Database.ArenaRow row : plugin.getDatabase().loadArenasBlocking()) {
            Arena a = new Arena(row.name, row.world, row.minX, row.minY, row.minZ, row.maxX, row.maxY, row.maxZ, folder);
            a.setSpawn1(row.spawn1);
            a.setSpawn2(row.spawn2);
            arenas.put(row.name, a);
        }
        plugin.getLogger().info(arenas.size() + " arena yuklendi.");
    }

    /** Sunucu tamamen acildiktan sonra cagrilir: schem'leri onbellege alir, kirli arenalari sifirlar. */
    public void postStartup() {
        for (Arena a : arenas.values()) {
            if (!a.getSchemFile().exists()) {
                plugin.getLogger().warning("Arena '" + a.getName() + "' icin schem bulunamadi: " + a.getSchemFile().getName()
                        + " (arenayi yeniden olusturun)");
                continue;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Clipboard c = readSchem(a.getSchemFile());
                if (c != null && a.getClipboard() == null) {
                    a.setClipboard(c);
                }
            });
        }
        // Sunucu duel ortasinda kapandiysa arena kirli kalmis olabilir
        int delay = 40;
        for (Arena a : new ArrayList<>(arenas.values())) {
            if (a.getDirtyFile().exists()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (a.getState() == Arena.State.FREE && arenas.get(a.getName()) == a) {
                        plugin.getLogger().info("Arena '" + a.getName() + "' onceki oturumdan kirli kalmis, sifirlaniyor.");
                        resetBlocks(a);
                    }
                }, delay);
                delay += 10;
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

    // ------------------------------------------------------------------ Yonetim

    public void createArena(Player player, String rawName) {
        String name = rawName.toLowerCase(java.util.Locale.ROOT);
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
        });
        Msg.send(player, "arena-created", "arena", name, "blocks", arena.getVolume());
    }

    public void deleteArena(Player player, String rawName) {
        String name = rawName.toLowerCase(java.util.Locale.ROOT);
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

    // ------------------------------------------------------------------ Sorgular

    public Arena getArena(String name) {
        return name == null ? null : arenas.get(name.toLowerCase(java.util.Locale.ROOT));
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

    public Arena getArenaAt(Location loc) {
        for (Arena a : arenas.values()) {
            if (a.isInside(loc)) return a;
        }
        return null;
    }

    // ------------------------------------------------------------------ Durum

    public void reserve(Arena a) {
        a.setState(Arena.State.RESERVED);
    }

    /** Rezervasyonu iptal eder (duel baslamadan). Arena bozulmadigi icin sifirlama gerekmez. */
    public void unreserve(Arena a) {
        if (a.getState() == Arena.State.RESERVED) {
            a.setState(Arena.State.FREE);
            plugin.getQueueManager().onArenaFreed();
        }
    }

    public void occupy(Arena a) {
        a.setState(Arena.State.IN_USE);
        try {
            if (!a.getDirtyFile().exists() && !a.getDirtyFile().createNewFile()) {
                plugin.getLogger().fine("dirty dosyasi olusturulamadi: " + a.getName());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("dirty dosyasi olusturulamadi: " + a.getName());
        }
    }

    /** Duel bitti: arenayi ilk haline dondurur ve bosa cikarir. */
    public void release(Arena a) {
        if (arenas.get(a.getName()) != a) return; // silinmis
        a.setState(Arena.State.RESETTING);
        resetBlocks(a);
        a.setState(Arena.State.FREE);
        plugin.getQueueManager().onArenaFreed();
    }

    /** Arena icindeki gecici varliklari temizler ve schem'i yapistirir. Ana thread. */
    void resetBlocks(Arena a) {
        World world = a.getWorld();
        if (world == null) {
            plugin.getLogger().warning("Arena '" + a.getName() + "' dunyasi yuklu degil, sifirlanamadi.");
            return;
        }
        // 1) Yere dusen esyalar, oklar, tekneler, mobs vs. temizle
        for (Entity e : world.getNearbyEntities(a.getBoundingBox())) {
            if (e instanceof Player || e instanceof Hanging || e instanceof ArmorStand) continue;
            if (plugin.getNpcManager().isNpc(e)) continue;
            e.remove();
        }
        // 2) Schem yapistir
        Clipboard clip = a.getClipboard();
        if (clip == null && a.getSchemFile().exists()) {
            clip = readSchem(a.getSchemFile());
            a.setClipboard(clip);
        }
        if (clip == null) {
            plugin.getLogger().severe("Arena '" + a.getName() + "' icin schem yok, arena sifirlanamadi!");
            return;
        }
        long start = System.nanoTime();
        BlockVector3 arenaMin = BlockVector3.at(a.getMinX(), a.getMinY(), a.getMinZ());
        BlockVector3 to = arenaMin.subtract(clip.getRegion().getMinimumPoint()).add(clip.getOrigin());
        try (EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            try {
                es.setSideEffectApplier(SideEffectSet.defaults().with(SideEffect.NEIGHBORS, SideEffect.State.OFF));
            } catch (Throwable ignored) {
                // FAWE vb. desteklemiyorsa varsayilanla devam
            }
            Operation op = new ClipboardHolder(clip).createPaste(es).to(to).ignoreAirBlocks(false).build();
            Operations.complete(op);
        } catch (WorldEditException e) {
            plugin.getLogger().log(Level.SEVERE, "Arena sifirlanamadi: " + a.getName(), e);
            return;
        }
        if (a.getDirtyFile().exists() && !a.getDirtyFile().delete()) {
            plugin.getLogger().fine("dirty dosyasi silinemedi: " + a.getName());
        }
        if (plugin.getConfig().getBoolean("settings.debug", false)) {
            plugin.getLogger().info("Arena '" + a.getName() + "' sifirlandi (" + (System.nanoTime() - start) / 1_000_000 + " ms)");
        }
    }

    /** Plugin kapanirken: kullanimdaki arenalari aninda sifirla. */
    public void resetAllInUse() {
        for (Arena a : arenas.values()) {
            if (a.getState() == Arena.State.IN_USE || a.getState() == Arena.State.RESETTING) {
                resetBlocks(a);
            }
            a.setState(Arena.State.FREE);
        }
    }
}
