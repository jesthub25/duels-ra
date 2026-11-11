package org.jestron.duelsira.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.arena.Arena;
import org.jestron.duelsira.duel.DuelManager;
import org.jestron.duelsira.duel.DuelSettings;
import org.jestron.duelsira.storage.Database;
import org.jestron.duelsira.util.InventorySerializer;
import org.jestron.duelsira.util.ItemBuilder;
import org.jestron.duelsira.util.Msg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Duel gonderme menuleri + duel gecmisi menuleri. */
public final class GuiManager {

    private static final int HISTORY_PER_PAGE = 45;

    private final DuelSira plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Integer> historyPage = new HashMap<>();

    public GuiManager(DuelSira plugin) {
        this.plugin = plugin;
    }

    private static final class Session {
        final UUID target;
        String kit;
        String arena;
        boolean itemsDrop = true;
        final DuelSettings settings;

        Session(UUID target, DuelSettings settings) {
            this.target = target;
            this.settings = settings;
        }
    }

    public void clearSession(UUID u) {
        sessions.remove(u);
    }

    public void clearPlayer(UUID u) {
        sessions.remove(u);
        historyPage.remove(u);
    }

    // ================================================================== Duel gonderme

    public void openSend(Player challenger, Player target) {
        Session s = sessions.get(challenger.getUniqueId());
        if (s == null || !s.target.equals(target.getUniqueId())) {
            s = new Session(target.getUniqueId(), new DuelSettings(plugin.getConfig()));
            sessions.put(challenger.getUniqueId(), s);
        }
        new SendMenu(s, target.getName()).open(challenger);
    }

    private abstract class SetupMenu extends Menu {
        SetupMenu(int rows, String title) {
            super(rows, title);
        }

        @Override
        public boolean isSetupMenu() {
            return true;
        }

        @Override
        public void onClose(Player p) {
            UUID u = p.getUniqueId();
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!sessions.containsKey(u)) return;
                if (p.isOnline()) {
                    InventoryHolder h = p.getOpenInventory().getTopInventory().getHolder();
                    if (h instanceof Menu m && m.isSetupMenu()) return; // baska kurulum menusune gecti
                }
                sessions.remove(u);
                if (p.isOnline()) Msg.send(p, "duel-send-cancelled");
            });
        }
    }

    private Player targetOf(Player p, Session s) {
        Player t = Bukkit.getPlayer(s.target);
        if (t == null) {
            sessions.remove(p.getUniqueId());
            p.closeInventory();
            Msg.send(p, "player-not-found");
        }
        return t;
    }

    private final class SendMenu extends SetupMenu {
        SendMenu(Session s, String targetName) {
            super(3, Msg.raw("gui-send-title", "player", targetName));
            ItemStack send = new ItemBuilder(Material.LIME_STAINED_GLASS_PANE).name(Msg.raw("gui-send"))
                    .lore(Msg.raw("gui-send-lore")).build();
            for (int i : new int[]{0, 1, 9, 10, 18, 19}) set(i, send, this::send);
            ItemStack cancel = new ItemBuilder(Material.RED_STAINED_GLASS_PANE).name(Msg.raw("gui-cancel"))
                    .lore(Msg.raw("gui-cancel-lore")).build();
            for (int i : new int[]{7, 8, 16, 17, 25, 26}) set(i, cancel, p -> {
                sessions.remove(p.getUniqueId());
                p.closeInventory();
                Msg.send(p, "duel-send-cancelled");
            });

            set(4, new ItemBuilder(Material.BOOK)
                    .name(s.arena == null ? Msg.raw("gui-arena-random") : Msg.raw("gui-arena-selected", "arena", s.arena))
                    .lore(Msg.raw("gui-click-change")).build(), p -> new ArenaMenu(s).open(p));

            set(13, new ItemBuilder(Material.CHEST)
                    .name(s.kit == null ? Msg.raw("gui-kit-own") : Msg.raw("gui-kit-selected", "kit", s.kit))
                    .lore(Msg.raw("gui-click-change")).build(), p -> new KitMenu(s).open(p));

            if (s.kit != null) {
                set(22, new ItemBuilder(Material.GRAY_CONCRETE).name(Msg.raw("gui-drop-kit")).build());
            } else if (s.itemsDrop) {
                set(22, new ItemBuilder(Material.LIME_CONCRETE).name(Msg.raw("gui-drop-on"))
                        .lore(Msg.raw("gui-drop-on-lore")).build(), p -> {
                    s.itemsDrop = false;
                    reopen(p, s);
                });
            } else {
                set(22, new ItemBuilder(Material.RED_CONCRETE).name(Msg.raw("gui-drop-off"))
                        .lore(Msg.raw("gui-drop-off-lore")).build(), p -> {
                    s.itemsDrop = true;
                    reopen(p, s);
                });
            }

            if (s.kit == null) {
                DuelSettings r = s.settings;
                List<String> lore = new ArrayList<>();
                lore.add(Msg.raw("gui-rules-lore"));
                lore.add("");
                lore.add(Msg.raw("rule-line", "rule", Msg.raw("rule-ladder"), "state", DuelManager.onOff(r.isAllowLadder())));
                lore.add(Msg.raw("rule-line", "rule", Msg.raw("rule-slab"), "state", DuelManager.onOff(r.isAllowSlab())));
                lore.add(Msg.raw("rule-line", "rule", Msg.raw("rule-button"), "state", DuelManager.onOff(r.isAllowButton())));
                lore.add(Msg.raw("rule-line", "rule", Msg.raw("rule-cobweb"), "state", DuelManager.onOff(r.isAllowCobweb())));
                lore.add(Msg.raw("rule-line", "rule", Msg.raw("rule-elytra"), "state", DuelManager.onOff(r.isAllowElytra())));
                set(12, new ItemBuilder(Material.COMPARATOR).name(Msg.raw("gui-rules")).lore(lore).build(),
                        p -> new RulesMenu(s).open(p));
            }
        }

        private void send(Player p) {
            Session s = sessions.remove(p.getUniqueId());
            p.closeInventory();
            if (s == null) return;
            Player t = Bukkit.getPlayer(s.target);
            if (t == null) {
                Msg.send(p, "player-not-found");
                return;
            }
            plugin.getDuelManager().sendRequest(p, t, s.kit, s.arena, s.itemsDrop, s.kit == null ? s.settings : null);
        }
    }

    private void reopen(Player p, Session s) {
        Player t = targetOf(p, s);
        if (t != null) new SendMenu(s, t.getName()).open(p);
    }

    private final class KitMenu extends SetupMenu {
        KitMenu(Session s) {
            super(rowsFor(plugin.getKitManager().getKitNames().size() + 1), Msg.raw("gui-kit-title"));
            set(0, new ItemBuilder(Material.BARRIER).name(Msg.raw("gui-kit-own-option")).build(), p -> {
                s.kit = null;
                reopen(p, s);
            });
            int slot = 1;
            for (String kit : plugin.getKitManager().getKitNames()) {
                if (slot >= 54) break;
                set(slot++, new ItemBuilder(Material.IRON_SWORD).name(Msg.raw("gui-kit-option", "kit", kit))
                        .lore(Msg.raw("gui-click-select")).build(), p -> {
                    s.kit = kit;
                    reopen(p, s);
                });
            }
        }
    }

    private final class ArenaMenu extends SetupMenu {
        ArenaMenu(Session s) {
            super(rowsFor(plugin.getArenaManager().getArenas().size() + 1), Msg.raw("gui-arena-title"));
            set(0, new ItemBuilder(Material.ENDER_EYE).name(Msg.raw("gui-arena-random-option")).build(), p -> {
                s.arena = null;
                reopen(p, s);
            });
            int slot = 1;
            for (Arena a : plugin.getArenaManager().getArenas()) {
                if (slot >= 54) break;
                if (!a.isReady()) continue;
                boolean free = a.isFree();
                String name = a.getName();
                set(slot++, new ItemBuilder(free ? Material.LIME_WOOL : Material.RED_WOOL)
                        .name(Msg.raw("gui-arena-option", "arena", name))
                        .lore(free ? Msg.raw("gui-arena-free") : Msg.raw("gui-arena-busy")).build(), p -> {
                    Arena now = plugin.getArenaManager().getArena(name);
                    if (now == null || !now.isFree()) {
                        Msg.send(p, "arena-busy", "arena", name);
                        return;
                    }
                    s.arena = name;
                    reopen(p, s);
                });
            }
        }
    }

    private final class RulesMenu extends SetupMenu {
        RulesMenu(Session s) {
            super(3, Msg.raw("gui-rules-title"));
            DuelSettings r = s.settings;
            ItemStack info = new ItemBuilder(Material.YELLOW_STAINED_GLASS_PANE).name(Msg.raw("gui-rules"))
                    .lore(Msg.raw("gui-rules-info")).build();
            for (int i = 0; i < 9; i++) set(i, info);
            set(10, toggle(Material.LADDER, "rule-ladder", r.isAllowLadder()), p -> { r.toggleLadder(); new RulesMenu(s).open(p); });
            set(11, toggle(Material.STONE_SLAB, "rule-slab", r.isAllowSlab()), p -> { r.toggleSlab(); new RulesMenu(s).open(p); });
            set(12, toggle(Material.STONE_BUTTON, "rule-button", r.isAllowButton()), p -> { r.toggleButton(); new RulesMenu(s).open(p); });
            set(13, toggle(Material.COBWEB, "rule-cobweb", r.isAllowCobweb()), p -> { r.toggleCobweb(); new RulesMenu(s).open(p); });
            set(14, toggle(Material.ELYTRA, "rule-elytra", r.isAllowElytra()), p -> { r.toggleElytra(); new RulesMenu(s).open(p); });
            set(22, new ItemBuilder(Material.ARROW).name(Msg.raw("gui-back")).build(), p -> reopen(p, s));
        }

        private ItemStack toggle(Material m, String ruleKey, boolean on) {
            return new ItemBuilder(on ? m : Material.BARRIER)
                    .name((on ? "&a&l" : "&c&l") + Msg.raw(ruleKey))
                    .lore(on ? Msg.raw("gui-rule-on") : Msg.raw("gui-rule-off")).build();
        }
    }

    private static int rowsFor(int items) {
        return Math.max(3, Math.min(6, (items + 8) / 9));
    }

    // ================================================================== Duel gecmisi

    public void openHistory(Player p, int page) {
        int safePage = Math.max(0, page);
        plugin.getDatabase().loadHistoryPage(HISTORY_PER_PAGE, safePage * HISTORY_PER_PAGE, result -> {
            if (!p.isOnline()) return;
            int totalPages = Math.max(1, (result.total + HISTORY_PER_PAGE - 1) / HISTORY_PER_PAGE);
            if (safePage >= totalPages && result.total > 0) {
                openHistory(p, totalPages - 1);
                return;
            }
            historyPage.put(p.getUniqueId(), safePage);
            new HistoryMenu(safePage, totalPages, result).open(p);
        });
    }

    private static String nameOf(String uuid) {
        if (uuid == null) return Msg.raw("none");
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
            return op.getName() == null ? Msg.raw("unknown") : op.getName();
        } catch (IllegalArgumentException e) {
            return Msg.raw("unknown");
        }
    }

    private final class HistoryMenu extends Menu {
        HistoryMenu(int page, int totalPages, Database.HistoryPage result) {
            super(6, Msg.raw("gui-history-title", "page", page + 1, "pages", totalPages));
            int slot = 0;
            for (Database.DuelRecord r : result.records) {
                if (slot >= HISTORY_PER_PAGE) break;
                List<String> lore = new ArrayList<>();
                lore.add(Msg.raw("history-date", "date", r.timestamp));
                lore.add(Msg.raw("history-type", "type", "QUEUE".equals(r.type) ? Msg.raw("type-queue") : Msg.raw("type-normal")));
                lore.add("");
                lore.add(Msg.raw("history-winner", "player", nameOf(r.winner)));
                lore.add(Msg.raw("history-loser", "player", nameOf(r.loser)));
                lore.add("");
                lore.add(Msg.raw("history-players", "p1", nameOf(r.player1), "p2", nameOf(r.player2)));
                lore.add(Msg.raw("history-arena", "arena", r.arena));
                lore.add(Msg.raw("history-kit", "kit", r.kit == null ? Msg.raw("own-inventory") : r.kit));
                lore.add(Msg.raw("history-mode", "mode", r.itemsDrop ? Msg.raw("mode-drop") : Msg.raw("mode-keep")));
                lore.add(Msg.raw("history-duration", "seconds", r.duration));
                lore.add("");
                lore.add(Msg.raw("gui-click-detail"));
                int id = r.id;
                set(slot++, new ItemBuilder(Material.PAPER).name(Msg.raw("history-item", "id", id)).lore(lore).build(),
                        p -> openDetail(p, id));
            }
            if (page > 0) {
                set(48, new ItemBuilder(Material.ARROW).name(Msg.raw("gui-prev")).build(), p -> openHistory(p, page - 1));
            }
            set(49, new ItemBuilder(Material.BOOK).name(Msg.raw("gui-page-info", "page", page + 1, "pages", totalPages))
                    .lore(Msg.raw("gui-total", "total", result.total)).build());
            if (page < totalPages - 1) {
                set(50, new ItemBuilder(Material.ARROW).name(Msg.raw("gui-next")).build(), p -> openHistory(p, page + 1));
            }
            set(52, new ItemBuilder(Material.BARRIER).name(Msg.raw("gui-close")).build(), Player::closeInventory);
        }
    }

    private void openDetail(Player p, int id) {
        plugin.getDatabase().loadRecord(id, r -> {
            if (!p.isOnline()) return;
            if (r == null) {
                Msg.send(p, "history-not-found");
                return;
            }
            new DetailMenu(r).open(p);
        });
    }

    private final class DetailMenu extends Menu {
        DetailMenu(Database.DuelRecord r) {
            super(3, Msg.raw("gui-detail-title", "id", r.id));
            set(11, head(r.player1, r.id), p -> openRecordedInventory(p, r, true));
            set(13, new ItemBuilder(Material.PAPER).name(Msg.raw("gui-detail-info")).lore(
                    Msg.raw("history-date", "date", r.timestamp),
                    Msg.raw("history-arena", "arena", r.arena),
                    Msg.raw("history-kit", "kit", r.kit == null ? Msg.raw("own-inventory") : r.kit),
                    Msg.raw("history-duration", "seconds", r.duration),
                    "",
                    Msg.raw("gui-detail-hint")).build());
            set(15, head(r.player2, r.id), p -> openRecordedInventory(p, r, false));
            set(18, new ItemBuilder(Material.ARROW).name(Msg.raw("gui-back")).build(),
                    p -> openHistory(p, historyPage.getOrDefault(p.getUniqueId(), 0)));
        }

        private ItemStack head(String uuid, int id) {
            OfflinePlayer op = null;
            try {
                op = Bukkit.getOfflinePlayer(UUID.fromString(uuid));
            } catch (IllegalArgumentException ignored) {
            }
            return new ItemBuilder(Material.PLAYER_HEAD).name("&e" + nameOf(uuid)).skull(op)
                    .lore(Msg.raw("gui-detail-head"), Msg.raw("history-item", "id", id)).build();
        }
    }

    private void openRecordedInventory(Player p, Database.DuelRecord r, boolean first) {
        String data = first ? r.p1Inv : r.p2Inv;
        String name = nameOf(first ? r.player1 : r.player2);
        ItemStack[] contents;
        try {
            contents = InventorySerializer.fromBase64(data);
        } catch (Exception e) {
            contents = new ItemStack[0];
            Msg.send(p, "history-inventory-error");
        }
        InventoryViewMenu.openRecorded(p, name, contents, viewer -> new DetailMenu(r).open(viewer));
    }
}
