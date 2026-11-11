package org.jestron.duelsira.storage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jestron.duelsira.DuelSira;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * SQLite erisimi. Tum sorgular tek bir arka plan thread'inde calisir
 * (ana thread hic bloklanmaz, SQLite baglantisi tek thread'den kullanilir).
 */
public final class Database {

    private final DuelSira plugin;
    private final ExecutorService executor;
    private Connection connection;

    public Database(DuelSira plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DuelSira-DB");
            t.setDaemon(true);
            return t;
        });
    }

    /** Baglanir ve tablolari olusturur (enable sirasinda, bekleyerek). */
    public boolean init() {
        Future<Boolean> f = executor.submit(() -> {
            try {
                File folder = plugin.getDataFolder();
                if (!folder.exists() && !folder.mkdirs()) {
                    plugin.getLogger().severe("Veri klasoru olusturulamadi!");
                    return false;
                }
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + new File(folder, "dueldata.db").getAbsolutePath());
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=NORMAL");
                    st.execute("CREATE TABLE IF NOT EXISTS arenas (" +
                            "name TEXT PRIMARY KEY NOT NULL," +
                            "world TEXT NOT NULL," +
                            "min_x INTEGER NOT NULL, min_y INTEGER NOT NULL, min_z INTEGER NOT NULL," +
                            "max_x INTEGER NOT NULL, max_y INTEGER NOT NULL, max_z INTEGER NOT NULL," +
                            "spawn1_world TEXT, spawn1_x REAL, spawn1_y REAL, spawn1_z REAL, spawn1_yaw REAL, spawn1_pitch REAL," +
                            "spawn2_world TEXT, spawn2_x REAL, spawn2_y REAL, spawn2_z REAL, spawn2_yaw REAL, spawn2_pitch REAL)");
                    st.execute("CREATE TABLE IF NOT EXISTS duel_history (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "timestamp DATETIME DEFAULT (datetime('now','localtime'))," +
                            "duel_type TEXT NOT NULL DEFAULT 'NORMAL'," +
                            "player1_uuid TEXT NOT NULL," +
                            "player2_uuid TEXT NOT NULL," +
                            "winner_uuid TEXT," +
                            "loser_uuid TEXT," +
                            "arena_name TEXT NOT NULL," +
                            "kit_name TEXT," +
                            "settings_json TEXT," +
                            "player1_inventory TEXT," +
                            "player2_inventory TEXT," +
                            "duration_seconds INTEGER DEFAULT 0," +
                            "is_items_drop INTEGER DEFAULT 0)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_history_id ON duel_history(id DESC)");
                }
                return true;
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "SQLite baslatilamadi!", e);
                return false;
            }
        });
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "SQLite baslatma zaman asimi/hatasi", e);
            return false;
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
    }

    private void async(SqlTask task) {
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            if (connection == null) return;
            try {
                task.run(connection);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Veritabani hatasi", e);
            }
        });
    }

    private <T> void asyncQuery(SqlQuery<T> query, Consumer<T> mainThreadCallback, T fallback) {
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            T result = fallback;
            if (connection != null) {
                try {
                    result = query.run(connection);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Veritabani okuma hatasi", e);
                }
            }
            final T r = result;
            if (plugin.isEnabled()) {
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> mainThreadCallback.accept(r));
                } catch (RuntimeException ignored) {
                    // plugin tam bu anda kapandi
                }
            }
        });
    }

    // ------------------------------------------------------------------ Arenalar

    public static final class ArenaRow {
        public final String name, world;
        public final int minX, minY, minZ, maxX, maxY, maxZ;
        public final StoredLocation spawn1, spawn2;

        ArenaRow(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                 StoredLocation spawn1, StoredLocation spawn2) {
            this.name = name;
            this.world = world;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.spawn1 = spawn1;
            this.spawn2 = spawn2;
        }
    }

    /** Dunya yuklenmemis olsa bile saklanabilen konum. */
    public static final class StoredLocation {
        public final String world;
        public final double x, y, z;
        public final float yaw, pitch;

        public StoredLocation(String world, double x, double y, double z, float yaw, float pitch) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public static StoredLocation of(Location l) {
            return new StoredLocation(l.getWorld() == null ? "" : l.getWorld().getName(),
                    l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        }

        public Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    public List<ArenaRow> loadArenasBlocking() {
        Future<List<ArenaRow>> f = executor.submit(() -> {
            List<ArenaRow> list = new ArrayList<>();
            if (connection == null) return list;
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM arenas")) {
                while (rs.next()) {
                    list.add(new ArenaRow(rs.getString("name"), rs.getString("world"),
                            rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"),
                            rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z"),
                            readLoc(rs, "spawn1"), readLoc(rs, "spawn2")));
                }
            }
            return list;
        });
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Arenalar okunamadi", e);
            return new ArrayList<>();
        }
    }

    private static StoredLocation readLoc(ResultSet rs, String p) throws SQLException {
        String w = rs.getString(p + "_world");
        if (w == null) return null;
        return new StoredLocation(w, rs.getDouble(p + "_x"), rs.getDouble(p + "_y"), rs.getDouble(p + "_z"),
                rs.getFloat(p + "_yaw"), rs.getFloat(p + "_pitch"));
    }

    public void insertArena(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO arenas(name, world, min_x, min_y, min_z, max_x, max_y, max_z) VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setString(1, name);
                ps.setString(2, world);
                ps.setInt(3, minX);
                ps.setInt(4, minY);
                ps.setInt(5, minZ);
                ps.setInt(6, maxX);
                ps.setInt(7, maxY);
                ps.setInt(8, maxZ);
                ps.executeUpdate();
            }
        });
    }

    public void deleteArena(String name) {
        async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM arenas WHERE name = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
        });
    }

    public void updateSpawn(String name, int which, StoredLocation l) {
        String p = which == 1 ? "spawn1" : "spawn2";
        async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE arenas SET " + p + "_world=?, " + p + "_x=?, " + p + "_y=?, "
                    + p + "_z=?, " + p + "_yaw=?, " + p + "_pitch=? WHERE name=?")) {
                ps.setString(1, l.world);
                ps.setDouble(2, l.x);
                ps.setDouble(3, l.y);
                ps.setDouble(4, l.z);
                ps.setFloat(5, l.yaw);
                ps.setFloat(6, l.pitch);
                ps.setString(7, name);
                ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ Gecmis

    public static final class DuelRecord {
        public final int id;
        public final String timestamp, type, player1, player2, winner, loser, arena, kit, settingsJson, p1Inv, p2Inv;
        public final int duration;
        public final boolean itemsDrop;

        public DuelRecord(int id, String timestamp, String type, String player1, String player2, String winner,
                          String loser, String arena, String kit, String settingsJson, String p1Inv, String p2Inv,
                          int duration, boolean itemsDrop) {
            this.id = id;
            this.timestamp = timestamp;
            this.type = type;
            this.player1 = player1;
            this.player2 = player2;
            this.winner = winner;
            this.loser = loser;
            this.arena = arena;
            this.kit = kit;
            this.settingsJson = settingsJson;
            this.p1Inv = p1Inv;
            this.p2Inv = p2Inv;
            this.duration = duration;
            this.itemsDrop = itemsDrop;
        }
    }

    public void saveHistory(DuelRecord r) {
        async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO duel_history(duel_type, player1_uuid, player2_uuid, " +
                    "winner_uuid, loser_uuid, arena_name, kit_name, settings_json, player1_inventory, player2_inventory, " +
                    "duration_seconds, is_items_drop) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, r.type);
                ps.setString(2, r.player1);
                ps.setString(3, r.player2);
                ps.setString(4, r.winner);
                ps.setString(5, r.loser);
                ps.setString(6, r.arena);
                ps.setString(7, r.kit);
                ps.setString(8, r.settingsJson);
                ps.setString(9, r.p1Inv);
                ps.setString(10, r.p2Inv);
                ps.setInt(11, r.duration);
                ps.setInt(12, r.itemsDrop ? 1 : 0);
                ps.executeUpdate();
            }
        });
    }

    public static final class HistoryPage {
        public final List<DuelRecord> records;
        public final int total;

        HistoryPage(List<DuelRecord> records, int total) {
            this.records = records;
            this.total = total;
        }
    }

    public void loadHistoryPage(int limit, int offset, Consumer<HistoryPage> callback) {
        asyncQuery(c -> {
            int total = 0;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM duel_history")) {
                if (rs.next()) total = rs.getInt(1);
            }
            List<DuelRecord> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM duel_history ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setInt(1, limit);
                ps.setInt(2, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(readRecord(rs));
                }
            }
            return new HistoryPage(list, total);
        }, callback, new HistoryPage(new ArrayList<>(), 0));
    }

    public void loadRecord(int id, Consumer<DuelRecord> callback) {
        asyncQuery(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM duel_history WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? readRecord(rs) : null;
                }
            }
        }, callback, null);
    }

    private static DuelRecord readRecord(ResultSet rs) throws SQLException {
        return new DuelRecord(rs.getInt("id"), rs.getString("timestamp"), rs.getString("duel_type"),
                rs.getString("player1_uuid"), rs.getString("player2_uuid"), rs.getString("winner_uuid"),
                rs.getString("loser_uuid"), rs.getString("arena_name"), rs.getString("kit_name"),
                rs.getString("settings_json"), rs.getString("player1_inventory"), rs.getString("player2_inventory"),
                rs.getInt("duration_seconds"), rs.getInt("is_items_drop") != 0);
    }

    @FunctionalInterface
    private interface SqlTask {
        void run(Connection c) throws Exception;
    }

    @FunctionalInterface
    private interface SqlQuery<T> {
        T run(Connection c) throws Exception;
    }
}
