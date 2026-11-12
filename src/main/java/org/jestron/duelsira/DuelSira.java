package org.jestron.duelsira;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jestron.duelsira.arena.ArenaManager;
import org.jestron.duelsira.command.DuelCommand;
import org.jestron.duelsira.command.DuelSiraCommand;
import org.jestron.duelsira.duel.DuelManager;
import org.jestron.duelsira.gui.GuiManager;
import org.jestron.duelsira.gui.MenuListener;
import org.jestron.duelsira.hook.WorldGuardHook;
import org.jestron.duelsira.kit.KitManager;
import org.jestron.duelsira.listener.ArenaListener;
import org.jestron.duelsira.listener.DuelListener;
import org.jestron.duelsira.listener.QueueListener;
import org.jestron.duelsira.queue.NpcManager;
import org.jestron.duelsira.queue.QueueManager;
import org.jestron.duelsira.storage.Database;

import java.util.logging.Level;

public final class DuelSira extends JavaPlugin {

    private static DuelSira instance;

    private Database database;
    private ArenaManager arenaManager;
    private KitManager kitManager;
    private DuelManager duelManager;
    private QueueManager queueManager;
    private NpcManager npcManager;
    private GuiManager guiManager;
    private boolean started;

    public static DuelSira get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        PluginManager pm = getServer().getPluginManager();
        if (pm.getPlugin("WorldEdit") == null && pm.getPlugin("FastAsyncWorldEdit") == null) {
            getLogger().severe("WorldEdit bulunamadi! DuelSira devre disi.");
            pm.disablePlugin(this);
            return;
        }
        if (pm.getPlugin("WorldGuard") == null) {
            getLogger().severe("WorldGuard bulunamadi! DuelSira devre disi.");
            pm.disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        database = new Database(this);
        if (!database.init()) {
            getLogger().severe("Veritabani acilamadi! DuelSira devre disi.");
            pm.disablePlugin(this);
            return;
        }

        try {
            arenaManager = new ArenaManager(this);
            arenaManager.load();
            kitManager = new KitManager(this);
            npcManager = new NpcManager(this);
            npcManager.load();
            guiManager = new GuiManager(this);
            queueManager = new QueueManager(this, new WorldGuardHook());
            duelManager = new DuelManager(this);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "DuelSira baslatilamadi!", t);
            pm.disablePlugin(this);
            return;
        }

        pm.registerEvents(new MenuListener(this), this);
        pm.registerEvents(new DuelListener(this), this);
        pm.registerEvents(new QueueListener(this), this);
        pm.registerEvents(new ArenaListener(this), this);

        register("duel", new DuelCommand(this));
        register("duelsira", new DuelSiraCommand(this));

        arenaManager.startTasks();
        duelManager.startTasks();
        queueManager.startTasks();
        npcManager.startTasks();

        // Sunucu tamamen acilinca (ilk tick): schem onbellegi, kirli arena sifirlama, NPC kontrolu
        Bukkit.getScheduler().runTask(this, () -> {
            arenaManager.postStartup();
            npcManager.validateAll();
        });
        started = true;
        getLogger().info("DuelSira v" + getDescription().getVersion() + " aktif.");
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("Komut plugin.yml'de yok: " + name);
            return;
        }
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        if (started) {
            safe(() -> queueManager.shutdown());
            safe(() -> duelManager.shutdown());
            safe(() -> arenaManager.resetAllInUse());
            safe(() -> npcManager.shutdown());
        }
        if (database != null) database.shutdown();
        started = false;
        instance = null;
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "Kapanis hatasi", t);
        }
    }

    /** /duel reload */
    public void reloadAll() {
        reloadConfig();
        kitManager.reload();
        npcManager.validateAll();
    }

    public Location getLobbyLocation() {
        ConfigurationSection s = getConfig().getConfigurationSection("lobby");
        if (s == null) return null;
        World w = Bukkit.getWorld(s.getString("world", ""));
        if (w == null) return null;
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    public void setLobbyLocation(Location l) {
        getConfig().set("lobby.world", l.getWorld() == null ? null : l.getWorld().getName());
        getConfig().set("lobby.x", l.getX());
        getConfig().set("lobby.y", l.getY());
        getConfig().set("lobby.z", l.getZ());
        getConfig().set("lobby.yaw", (double) l.getYaw());
        getConfig().set("lobby.pitch", (double) l.getPitch());
        saveConfig();
    }

    public Database getDatabase() { return database; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public KitManager getKitManager() { return kitManager; }
    public DuelManager getDuelManager() { return duelManager; }
    public QueueManager getQueueManager() { return queueManager; }
    public NpcManager getNpcManager() { return npcManager; }
    public GuiManager getGuiManager() { return guiManager; }
}
