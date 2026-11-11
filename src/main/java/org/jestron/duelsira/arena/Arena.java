package org.jestron.duelsira.arena;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.jestron.duelsira.storage.Database.StoredLocation;

import java.io.File;

public final class Arena {

    public enum State {
        /** Bos, kullanilabilir. */
        FREE,
        /** Duel sirasi eslesmesi icin ayrildi (oyuncular yanit veriyor). */
        RESERVED,
        /** Duel oynaniyor. */
        IN_USE,
        /** Schem geri yukleniyor. */
        RESETTING
    }

    private final String name;
    private final String worldName;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private final File schemFile;
    private final File dirtyFile;
    private StoredLocation spawn1, spawn2;
    private State state = State.FREE;
    private volatile Clipboard clipboard;

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
        return b.getX() >= minX && b.getX() <= maxX && b.getY() >= minY && b.getY() <= maxY
                && b.getZ() >= minZ && b.getZ() <= maxZ;
    }

    public BoundingBox getBoundingBox() {
        return new BoundingBox(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    public long getVolume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }
}
