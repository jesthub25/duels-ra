package org.jestron.duelsira.arena;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.BoundingBox;
import org.jestron.duelsira.storage.Database.StoredLocation;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

public final class Arena {

    public enum State {
        /** Bos, kullanilabilir. */
        FREE,
        /** Duel sirasi eslesmesi icin ayrildi (oyuncular yanit veriyor). */
        RESERVED,
        /** Duel oynaniyor. */
        IN_USE,
        /** Kontrol ediliyor / schem geri yukleniyor. */
        RESETTING
    }

    /**
     * Schem'in arena koordinatlarina hizalanmis "olmasi gereken" hali.
     * palette: benzersiz BlockData'lar, indices: her blok icin palet indeksi.
     * Indeks sirasi: ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX)
     */
    static final class Expected {
        final BlockData[] palette;
        final char[] indices;

        Expected(BlockData[] palette, char[] indices) {
            this.palette = palette;
            this.indices = indices;
        }
    }

    private final String name;
    private final String worldName;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private final File schemFile;
    private final File dirtyFile;
    private StoredLocation spawn1, spawn2;
    private State state = State.FREE;
    private volatile Clipboard clipboard;
    private volatile Expected expected;

    // ---- akilli sifirlama durumu (ana thread)
    /** Olaylarla isaretlenen, NBT'si (sandik icerigi, tabela vb.) degismis olabilecek bolumler. */
    private final Set<Long> nbtDirty = new HashSet<>();
    /** Sifirlama sirasinda arenada blok degisti mi (akan sivi vb.) -> tekrar kontrol. */
    boolean changedDuringReset;
    /** Bir sonraki sifirlama tam yapistirma olsun. */
    boolean forceFull;
    /** Son tam yapistirmadan beri kac sifirlama yapildi. */
    int resetsSinceFull;
    /** Ayni sifirlamada kacinci kontrol turu. */
    int resetAttempt;

    Arena(String name, String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, File folder) {
        this.name = name;
        this.worldName = worldName;
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
        this.schemFile = new File(folder, name + ".schem");
        this.dirtyFile = new File(folder, name + ".dirty");
    }

    public String getName() {
        return name;
    }

    public String getWorldName() {
        return worldName;
    }

    public World getWorld() {
        return Bukkit.getWorld(worldName);
    }

    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }
    public int getSizeX() { return maxX - minX + 1; }
    public int getSizeY() { return maxY - minY + 1; }
    public int getSizeZ() { return maxZ - minZ + 1; }

    public File getSchemFile() {
        return schemFile;
    }

    File getDirtyFile() {
        return dirtyFile;
    }

    public Clipboard getClipboard() {
        return clipboard;
    }

    void setClipboard(Clipboard clipboard) {
        this.clipboard = clipboard;
    }

    Expected getExpected() {
        return expected;
    }

    void setExpected(Expected expected) {
        this.expected = expected;
    }

    public State getState() {
        return state;
    }

    void setState(State state) {
        this.state = state;
    }

    public boolean isFree() {
        return state == State.FREE;
    }

    void setSpawn1(StoredLocation l) {
        this.spawn1 = l;
    }

    void setSpawn2(StoredLocation l) {
        this.spawn2 = l;
    }

    public boolean hasSpawn1() {
        return spawn1 != null;
    }

    public boolean hasSpawn2() {
        return spawn2 != null;
    }

    public Location getSpawn1() {
        return spawn1 == null ? null : spawn1.toLocation();
    }

    public Location getSpawn2() {
        return spawn2 == null ? null : spawn2.toLocation();
    }

    /** Spawnlar ayarli, dunya yuklu ve schem mevcut mu. */
    public boolean isReady() {
        return getWorld() != null && getSpawn1() != null && getSpawn2() != null
                && (clipboard != null || schemFile.exists());
    }

    public boolean isInside(Location loc) {
        if (loc == null || loc.getWorld() == null || !loc.getWorld().getName().equals(worldName)) return false;
        double x = loc.getX(), y = loc.getY(), z = loc.getZ();
        return x >= minX && x < maxX + 1 && y >= minY && y < maxY + 1 && z >= minZ && z < maxZ + 1;
    }

    public boolean contains(Block b) {
        if (b == null || !b.getWorld().getName().equals(worldName)) return false;
        return contains(b.getX(), b.getY(), b.getZ());
    }

    boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public BoundingBox getBoundingBox() {
        return new BoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    public long getVolume() {
        return (long) getSizeX() * getSizeY() * getSizeZ();
    }

    // ------------------------------------------------------------------ bolumler (16x16x16)

    static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sy & 0xFFFFF) << 22) | (sz & 0x3FFFFF);
    }

    static int keyX(long k) {
        return (int) (k >> 42) << 10 >> 10;
    }

    static int keyY(long k) {
        return (int) ((k >> 22) & 0xFFFFF) << 12 >> 12;
    }

    static int keyZ(long k) {
        return (int) (k & 0x3FFFFF) << 10 >> 10;
    }

    /** Bolum sayisi (arena ile kesisen 16x16x16 bolumler). */
    int sectionCount() {
        return ((maxX >> 4) - (minX >> 4) + 1) * ((maxY >> 4) - (minY >> 4) + 1) * ((maxZ >> 4) - (minZ >> 4) + 1);
    }

    /** Blogun ve komsularinin bolumunu NBT-kirli olarak isaretler. */
    void markNbtDirty(int x, int y, int z) {
        for (int[] d : NEIGHBORS) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (contains(nx, ny, nz)) nbtDirty.add(sectionKey(nx >> 4, ny >> 4, nz >> 4));
        }
    }

    Set<Long> drainNbtDirty() {
        Set<Long> copy = new HashSet<>(nbtDirty);
        nbtDirty.clear();
        return copy;
    }

    private static final int[][] NEIGHBORS = {
            {0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
}
