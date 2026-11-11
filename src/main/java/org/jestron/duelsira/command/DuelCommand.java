package org.jestron.duelsira.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.util.Msg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class DuelCommand implements TabExecutor {

    private final DuelSira plugin;

    public DuelCommand(DuelSira plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("duel.admin")) {
                Msg.send(sender, "no-permission");
                return true;
            }
            plugin.reloadAll();
            Msg.send(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Bu komut sadece oyuncular icindir.");
            return true;
        }
        if (args.length == 0) {
            help(p);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "kabul" -> {
                if (args.length < 2) {
                    Msg.send(p, "usage", "usage", "/duel kabul <oyuncu>");
                    return true;
                }
                plugin.getDuelManager().acceptRequest(p, args[1]);
            }
            case "reddet" -> plugin.getDuelManager().denyRequest(p, args.length < 2 ? null : args[1]);
            case "toggle" -> {
                if (!p.hasPermission("duel.toggle")) {
                    Msg.send(p, "no-permission");
                    return true;
                }
                boolean on = plugin.getDuelManager().toggleRequests(p.getUniqueId());
                Msg.send(p, on ? "toggle-on" : "toggle-off");
            }
            case "olustur" -> {
                if (admin(p) && arg(p, args, 2, "/duel olustur <isim>")) plugin.getArenaManager().createArena(p, args[1]);
            }
            case "sil" -> {
                if (admin(p) && arg(p, args, 2, "/duel sil <isim>")) plugin.getArenaManager().deleteArena(p, args[1]);
            }
            case "arena" -> {
                if (!admin(p) || !arg(p, args, 3, "/duel arena <isim> <setspawn1|setspawn2>")) return true;
                if (args[2].equalsIgnoreCase("setspawn1")) plugin.getArenaManager().setSpawn(p, args[1], 1);
                else if (args[2].equalsIgnoreCase("setspawn2")) plugin.getArenaManager().setSpawn(p, args[1], 2);
                else Msg.send(p, "usage", "usage", "/duel arena <isim> <setspawn1|setspawn2>");
            }
            case "arenalar" -> {
                if (!admin(p)) return true;
                Msg.send(p, "arena-list-header");
                for (var a : plugin.getArenaManager().getArenas()) {
                    Msg.send(p, "arena-list-line", "arena", a.getName(), "state", Msg.raw("state-" + a.getState().name().toLowerCase(Locale.ROOT)),
                            "ready", a.isReady() ? Msg.raw("word-yes") : Msg.raw("word-no"));
                }
            }
            case "kit" -> {
                if (!admin(p) || !arg(p, args, 3, "/duel kit <olustur|sil> <isim>")) return true;
                if (args[1].equalsIgnoreCase("olustur")) plugin.getKitManager().createKit(p, args[2]);
                else if (args[1].equalsIgnoreCase("sil")) plugin.getKitManager().deleteKit(p, args[2]);
                else Msg.send(p, "usage", "usage", "/duel kit <olustur|sil> <isim>");
            }
            case "gui", "gecmis" -> {
                if (admin(p)) plugin.getGuiManager().openHistory(p, 0);
            }
            case "setlobby" -> {
                if (!admin(p)) return true;
                plugin.setLobbyLocation(p.getLocation());
                Msg.send(p, "lobby-set");
            }
            default -> {
                if (!p.hasPermission("duel.use")) {
                    Msg.send(p, "no-permission");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (plugin.getDuelManager().canChallenge(p, target)) {
                    plugin.getGuiManager().openSend(p, target);
                }
            }
        }
        return true;
    }

    private boolean admin(Player p) {
        if (p.hasPermission("duel.admin")) return true;
        Msg.send(p, "no-permission");
        return false;
    }

    private boolean arg(Player p, String[] args, int n, String usage) {
        if (args.length >= n) return true;
        Msg.send(p, "usage", "usage", usage);
        return false;
    }

    private void help(Player p) {
        for (String line : plugin.getConfig().getStringList("messages.help-duel")) p.sendMessage(Msg.color(line));
        if (p.hasPermission("duel.admin")) {
            for (String line : plugin.getConfig().getStringList("messages.help-duel-admin")) p.sendMessage(Msg.color(line));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        boolean admin = p.hasPermission("duel.admin");
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(Arrays.asList("kabul", "reddet", "toggle"));
            if (admin) opts.addAll(Arrays.asList("olustur", "sil", "arena", "arenalar", "kit", "gui", "setlobby", "reload"));
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(p) && p.canSee(o)) opts.add(o.getName());
            }
            StringUtil.copyPartialMatches(args[0], opts, out);
        } else if (args.length == 2) {
            String s = args[0].toLowerCase(Locale.ROOT);
            if (admin && (s.equals("sil") || s.equals("arena"))) {
                StringUtil.copyPartialMatches(args[1], plugin.getArenaManager().getArenaNames(), out);
            } else if (admin && s.equals("kit")) {
                StringUtil.copyPartialMatches(args[1], Arrays.asList("olustur", "sil"), out);
            } else if (s.equals("kabul") || s.equals("reddet")) {
                StringUtil.copyPartialMatches(args[1], plugin.getDuelManager().getIncomingRequesterNames(p.getUniqueId()), out);
            }
        } else if (args.length == 3 && admin) {
            String s = args[0].toLowerCase(Locale.ROOT);
            if (s.equals("arena")) {
                StringUtil.copyPartialMatches(args[2], Arrays.asList("setspawn1", "setspawn2"), out);
            } else if (s.equals("kit") && args[1].equalsIgnoreCase("sil")) {
                StringUtil.copyPartialMatches(args[2], plugin.getKitManager().getKitNames(), out);
            }
        }
        Collections.sort(out);
        return out;
    }
}
