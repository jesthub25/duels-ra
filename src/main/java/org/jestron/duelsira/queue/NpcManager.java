package org.jestron.duelsira.queue;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.util.Msg;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Duel sirasi NPC'leri: AI'si kapali, hareketsiz, olumsuz moblar.
 * Konumlari npcs.yml'de tutulur; silinir/oldurulurse otomatik yeniden olusturulur.
 */
public final class NpcManager {

    public static final class NpcData {
        final String id;
        final EntityType type;
        final String world;
        final double x, y, z;
        final float yaw;
        UUID entity;

        NpcData(String id, EntityType type, String world, double x, double y, double z, float yaw, UUID entity) {
            this.id = id;
            this.type = type;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.entity = entity;
        }

        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, 0f);
        }

        public String getId() { return id; }
        public EntityType getType() { return type; }
        public String getWorld() { return world; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getZ() { return z; }
    }

    private static final List<String> MOB_NAMES;

    static {
        List<String> names = new ArrayList<>();
        for (EntityType t : EntityType.values()) {
            if (isAllowedType(t)) names.add(t.getKey().getKey());
        }
        Collections.sort(names);
        MOB_NAMES = Collections.unmodifiableList(names);
    }

    private final DuelSira plugin;
    private final NamespacedKey key;
    private final File file;
    private final Map<String, NpcData> npcs = new LinkedHashMap<>();
    private BukkitTask validateTask;

    public NpcManager(DuelSira plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "queue_npc");
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
    }

    @SuppressWarnings("deprecation")
    private static boolean isAllowedType(EntityType t) {
        if (t == null || t == EntityType.UNKNOWN || !t.isSpawnable() || !t.isAlive()) return false;
        Class<? extends Entity> c = t.getEntityClass();
        return c != null && Mob.class.isAssignableFrom(c);
    }

    public static List<String> getMobNames() {
        return MOB_NAMES;
    }

    public static EntityType parseType(String s) {
        if (s == null) return null;
        String in = s.toLowerCase(Locale.ROOT);
        if (in.startsWith("minecraft:")) in = in.substring(10);
        for (EntityType t : EntityType.values()) {
            if (!isAllowedType(t)) continue;
            if (t.getKey().getKey().equals(in) || t.name().equalsIgnoreCase(in)) return t;
        }
        return null;
    }

    // ================================================================== Kalicilik

    public void load() {
        npcs.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("npcs");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection n = sec.getConfigurationSection(id);
            if (n == null) continue;
            EntityType type = parseType(n.getString("type"));
            if (type == null) {
                plugin.getLogger().warning("NPC '" + id + "' gecersiz tur: " + n.getString("type"));
                continue;
            }
            UUID ent = null;
            try {
                String es = n.getString("entity");
                if (es != null) ent = UUID.fromString(es);
            } catch (IllegalArgumentException ignored) {
            }
            npcs.put(id, new NpcData(id, type, n.getString("world", "world"), n.getDouble("x"), n.getDouble("y"),
                    n.getDouble("z"), (float) n.getDouble("yaw"), ent));
        }
        plugin.getLogger().info(npcs.size() + " duel sirasi NPC'si yuklendi.");
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (NpcData d : npcs.values()) {
            String p = "npcs." + d.id + ".";
            y.set(p + "type", d.type.getKey().getKey());
            y.set(p + "world", d.world);
            y.set(p + "x", d.x);
            y.set(p + "y", d.y);
            y.set(p + "z", d.z);
            y.set(p + "yaw", (double) d.yaw);
            y.set(p + "entity", d.entity == null ? null : d.entity.toString());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "npcs.yml kaydedilemedi", e);
        }
    }

    public void startTasks() {
        validateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::validateAll, 40L, 100L);
    }

    public void shutdown() {
        if (validateTask != null) validateTask.cancel();
        save();
    }

    // ================================================================== Islemler

    public boolean isNpc(Entity e) {
        return e != null && e.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    private String npcId(Entity e) {
        return e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public void create(Player p, EntityType type) {
        Location pl = p.getLocation();
        Location loc = new Location(pl.getWorld(), pl.getBlockX() + 0.5, pl.getY(), pl.getBlockZ() + 0.5, pl.getYaw(), 0f);
        int n = 1;
        while (npcs.containsKey("npc" + n)) n++;
        String id = "npc" + n;
        NpcData d = new NpcData(id, type, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), null);
        Entity e = spawn(d, loc);
        if (e == null) {
            Msg.send(p, "npc-spawn-failed");
            return;
        }
        npcs.put(id, d);
        save();
        Msg.send(p, "npc-created", "id", id, "type", type.getKey().getKey());
    }

    private Entity spawn(NpcData d, Location loc) {
        Class<? extends Entity> cls = d.type.getEntityClass();
        if (cls == null || loc.getWorld() == null) return null;
        try {
            Entity e = loc.getWorld().spawn(loc, cls, ent -> configure(ent, d.id, loc));
            d.entity = e.getUniqueId();
            return e;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "NPC olusturulamadi: " + d.id, ex);
            return null;
        }
    }

    private void configure(Entity ent, String id, Location loc) {
        ent.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        ent.setPersistent(true);
        ent.setSilent(true);
        ent.setInvulnerable(true);
        ent.setGravity(false);
        ent.setFireTicks(0);
        ent.setCustomName(Msg.color(plugin.getConfig().getString("npc.name", "&6&lDUEL SIRASI")));
        ent.setCustomNameVisible(true);
        ent.setRotation(loc.getYaw(), 0f);
        if (ent instanceof LivingEntity le) {
            le.setAI(false);
            le.setRemoveWhenFarAway(false);
            le.setCollidable(false);
            le.setCanPickupItems(false);
        }
    }

    /** Oyuncuya en yakin NPC'yi (5 blok) kaldirir. */
    public void removeNearest(Player p) {
        Entity best = null;
        double bestDist = 25.0;
        for (Entity e : p.getNearbyEntities(5, 5, 5)) {
            if (!isNpc(e)) continue;
            double d = e.getLocation().distanceSquared(p.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        if (best == null) {
            Msg.send(p, "npc-none-near");
            return;
        }
        String id = npcId(best);
        best.remove();
        if (id != null) npcs.remove(id);
        save();
        Msg.send(p, "npc-removed", "id", id == null ? "?" : id);
    }

    public boolean remove(String id) {
        NpcData d = npcs.remove(id);
        if (d == null) return false;
        if (d.entity != null) {
            Entity e = Bukkit.getEntity(d.entity);
            if (e != null) e.remove();
        }
        save();
        return true;
    }

    public List<NpcData> list() {
        return new ArrayList<>(npcs.values());
    }

    // ================================================================== Dogrulama

    /** Yuklu chunk'lardaki NPC'leri kontrol eder: yoksa olusturur, kaydiysa yerine koyar. */
    public void validateAll() {
        boolean changed = false;
        for (NpcData d : npcs.values()) {
            changed |= validate(d);
        }
        if (changed) save();
    }

    public boolean hasNpcIn(Chunk chunk) {
        String wn = chunk.getWorld().getName();
        for (NpcData d : npcs.values()) {
            if (d.world.equals(wn) && ((int) Math.floor(d.x)) >> 4 == chunk.getX()
                    && ((int) Math.floor(d.z)) >> 4 == chunk.getZ()) {
                return true;
            }
        }
        return false;
    }

    public void validateChunk(Chunk chunk) {
        boolean changed = false;
        for (NpcData d : npcs.values()) {
            if (!d.world.equals(chunk.getWorld().getName())) continue;
            if (((int) Math.floor(d.x)) >> 4 != chunk.getX() || ((int) Math.floor(d.z)) >> 4 != chunk.getZ()) continue;
            changed |= validate(d);
        }
        if (changed) save();
    }

    private boolean validate(NpcData d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null) return false;
        int cx = ((int) Math.floor(d.x)) >> 4;
        int cz = ((int) Math.floor(d.z)) >> 4;
        if (!w.isChunkLoaded(cx, cz)) return false;
        Chunk chunk = w.getChunkAt(cx, cz);
        if (!chunk.isEntitiesLoaded()) return false;
        Location target = d.location();
        if (target == null) return false;

        Entity current = d.entity == null ? null : Bukkit.getEntity(d.entity);
        if (current != null && (!current.isValid() || !isNpc(current))) current = null;

        boolean changed = false;
        // Ayni id'li kopyalari temizle / kaybolan entity'yi bul
        for (Entity e : chunk.getEntities()) {
            if (!isNpc(e) || !d.id.equals(npcId(e))) continue;
            if (current == null && e.isValid()) {
                current = e;
                d.entity = e.getUniqueId();
                changed = true;
            } else if (e != current) {
                e.remove();
            }
        }
        if (current == null) {
            Entity e = spawn(d, target);
            return e != null || changed;
        }
        if (current.getType() != d.type) {
            current.remove();
            spawn(d, target);
            return true;
        }
        Location l = current.getLocation();
        if (!l.getWorld().equals(w) || l.distanceSquared(target) > 0.0025 || Math.abs(l.getYaw() - d.yaw) > 0.5f) {
            current.teleport(target);
            current.setRotation(d.yaw, 0f);
        }
        if (current.getFireTicks() > 0) current.setFireTicks(0);
        String name = Msg.color(plugin.getConfig().getString("npc.name", "&6&lDUEL SIRASI"));
        if (!name.equals(current.getCustomName())) current.setCustomName(name);
        return changed;
    }
}
