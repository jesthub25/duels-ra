package org.jestron.duelsira.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.queue.NpcManager;
import org.jestron.duelsira.util.Msg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** /duelsira (alias /duelsıra) */
public final class DuelSiraCommand implements TabExecutor {

    private final DuelSira plugin;

    public DuelSiraCommand(DuelSira plugin) {
        this.plugin = plugin;
    }

    private static String norm(String s) {
        // Turkce karakterleri ASCII'ye indir: hayır -> hayir, ayrıl -> ayril, kaldır -> kaldir
        return s.toLowerCase(Locale.ROOT).replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c').replace('İ', 'i');
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Bu komut sadece oyuncular icindir.");
            return true;
        }
        if (args.length == 0) {
            for (String line : plugin.getConfig().getStringList("messages.help-queue")) p.sendMessage(Msg.color(line));
            if (p.hasPermission("duelsira.admin")) {
                for (String line : plugin.getConfig().getStringList("messages.help-queue-admin")) p.sendMessage(Msg.color(line));
            }
            return true;
        }
        switch (norm(args[0])) {
            case "evet", "kabul" -> plugin.getQueueManager().respond(p, true);
            case "hayir", "reddet" -> plugin.getQueueManager().respond(p, false);
            case "envanter" -> plugin.getQueueManager().openOpponentInventory(p);
            case "ayril", "cik" -> plugin.getQueueManager().leave(p);
            case "ekle" -> {
                if (!admin(p)) return true;
                if (args.length < 2) {
                    Msg.send(p, "usage", "usage", "/duelsira ekle <mob>");
                    return true;
                }
                EntityType type = NpcManager.parseType(args[1]);
                if (type == null) {
                    Msg.send(p, "npc-invalid-type", "type", args[1]);
                    return true;
                }
                plugin.getNpcManager().create(p, type);
            }
            case "kaldir" -> {
                if (!admin(p)) return true;
                if (args.length >= 2) {
                    if (plugin.getNpcManager().remove(args[1])) Msg.send(p, "npc-removed", "id", args[1]);
                    else Msg.send(p, "npc-not-found", "id", args[1]);
                } else {
                    plugin.getNpcManager().removeNearest(p);
                }
            }
            case "liste" -> {
                if (!admin(p)) return true;
                List<NpcManager.NpcData> list = plugin.getNpcManager().list();
                Msg.send(p, "npc-list-header", "count", list.size());
                for (NpcManager.NpcData d : list) {
                    Msg.send(p, "npc-list-line", "id", d.getId(), "type", d.getType().getKey().getKey(), "world", d.getWorld(),
                            "x", (int) Math.floor(d.getX()), "y", (int) Math.floor(d.getY()), "z", (int) Math.floor(d.getZ()));
                }
            }
            default -> Msg.send(p, "usage", "usage", "/duelsira <ekle|kaldır|liste|ayrıl|evet|hayır|envanter>");
        }
        return true;
    }

    private boolean admin(Player p) {
        if (p.hasPermission("duelsira.admin")) return true;
        Msg.send(p, "no-permission");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        boolean admin = p.hasPermission("duelsira.admin");
        if (args.length == 1) {
            // Siraya girmek icin komut YOK: sadece NPC'ye sag tik.
            List<String> opts = new ArrayList<>(Arrays.asList("evet", "hayır", "envanter", "ayrıl"));
            if (admin) opts.addAll(Arrays.asList("ekle", "kaldır", "liste"));
            String typed = norm(args[0]);
            for (String o : opts) {
                if (norm(o).startsWith(typed)) out.add(o);
            }
        } else if (args.length == 2 && admin) {
            String s = norm(args[0]);
            if (s.equals("ekle")) {
                StringUtil.copyPartialMatches(args[1], NpcManager.getMobNames(), out);
            } else if (s.equals("kaldir")) {
                // Eklenmis tum duel sirasi NPC'leri
                List<String> ids = new ArrayList<>();
                for (NpcManager.NpcData d : plugin.getNpcManager().list()) ids.add(d.getId());
                StringUtil.copyPartialMatches(args[1], ids, out);
            }
        }
        Collections.sort(out);
        return out;
    }
}
