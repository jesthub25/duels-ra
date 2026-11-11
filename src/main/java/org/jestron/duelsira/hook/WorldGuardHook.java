package org.jestron.duelsira.hook;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** WorldGuard PvP bayragi sorgusu. */
public final class WorldGuardHook {

    /**
     * Konumda PvP kapali mi (WorldGuard pvp flag = deny).
     * Sorgu WorldGuard'in onbellekli RegionQuery'si ile yapilir (hizli).
     */
    public boolean isPvpDenied(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(player);
        StateFlag.State state = query.queryState(BukkitAdapter.adapt(loc), lp, Flags.PVP);
        return state == StateFlag.State.DENY;
    }

    public boolean isPvpDenied(Player player) {
        return isPvpDenied(player, player.getLocation());
    }
}
