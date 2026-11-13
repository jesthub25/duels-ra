package org.jestron.duelsira.duel;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jestron.duelsira.arena.Arena;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DuelInstance {

    public enum Phase { COUNTDOWN, FIGHT, ENDED }

    public enum Type { NORMAL, QUEUE }

    private final Type type;
    private final UUID p1, p2;
    private final Arena arena;
    private final String kitName;
    private final boolean itemsDrop;
    private final boolean strength;
    private final Map<UUID, String> snapshots = new HashMap<>(4);
    private Phase phase = Phase.COUNTDOWN;
    private long fightStart;
    private BukkitTask countdownTask;
    private BukkitTask endTask;

    DuelInstance(Type type, UUID p1, UUID p2, Arena arena, String kitName, boolean itemsDrop, boolean strength) {
        this.type = type;
        this.p1 = p1;
        this.p2 = p2;
        this.arena = arena;
        this.kitName = kitName;
        this.itemsDrop = kitName == null && itemsDrop;
        this.strength = strength;
    }

    public Type getType() { return type; }
    public UUID getP1() { return p1; }
    public UUID getP2() { return p2; }
    public Arena getArena() { return arena; }
    public String getKitName() { return kitName; }
    public boolean isKit() { return kitName != null; }
    /** Kendi envanteri + esyalar duser modu. */
    public boolean isItemsDrop() { return itemsDrop; }
    /** Duel boyunca Kuvvet I. */
    public boolean isStrength() { return strength; }
    public Phase getPhase() { return phase; }
    void setPhase(Phase phase) { this.phase = phase; }
    long getFightStart() { return fightStart; }
    void setFightStart(long t) { this.fightStart = t; }
    Map<UUID, String> getSnapshots() { return snapshots; }

    void setCountdownTask(BukkitTask t) { this.countdownTask = t; }
    void setEndTask(BukkitTask t) { this.endTask = t; }

    void cancelTasks() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
    }

    public boolean involves(UUID u) {
        return p1.equals(u) || p2.equals(u);
    }

    public UUID getOpponent(UUID u) {
        return p1.equals(u) ? p2 : p1;
    }

    public Player getOpponentPlayer(UUID u) {
        return Bukkit.getPlayer(getOpponent(u));
    }

}
