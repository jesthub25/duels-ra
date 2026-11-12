package org.jestron.duelsira.listener;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.jestron.duelsira.DuelSira;
import org.jestron.duelsira.arena.ArenaManager;

/**
 * Akilli arena sifirlama icin olay takibi.
 * - NBT degisiklikleri (sandik icerigi, tabela, firin vb.) blok karsilastirmasinda gorunmedigi icin olaylarla isaretlenir.
 * - Sifirlama sirasinda arenada blok degisirse (akan sivi, dusen kum...) arena tekrar kontrol edilir.
 * Her olayda once O(1) filtre: arena yoksa / sifirlanan arena yoksa hemen cikilir.
 */
public final class ArenaListener implements Listener {

    private final ArenaManager arenas;

    public ArenaListener(DuelSira plugin) {
        this.arenas = plugin.getArenaManager();
    }

    // ------------------------------------------------------------------ NBT degisiklikleri

    private void markInventory(Inventory inv) {
        if (inv == null) return;
        Location l = inv.getLocation();
        if (l != null) arenas.markNbtChange(l);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        markInventory(e.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        markInventory(e.getSource());
        markInventory(e.getDestination());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent e) {
        markInventory(e.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        arenas.markNbtChange(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        arenas.markNbtChange(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCook(BlockCookEvent e) {
        arenas.markNbtChange(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent e) {
        arenas.markNbtChange(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent e) {
        arenas.markNbtChange(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || arenas.arenaAt(b) == null) return;
        // Sadece tile entity'ler (jukebox, kursu, kitaplik, saksi, fircalanabilir kum...)
        if (b.getState() instanceof TileState) arenas.markNbtChange(b);
    }

    // ------------------------------------------------------------------ sifirlama sirasinda degisiklik

    private void during(Block b) {
        if (arenas.hasResetting()) arenas.markChangeDuringReset(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (arenas.hasResetting()) {
            arenas.markChangeDuringReset(e.getBlock());
            arenas.markChangeDuringReset(e.getToBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        during(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        during(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        during(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        during(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        during(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        during(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        during(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        during(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!arenas.hasResetting()) return;
        for (Block b : e.blockList()) arenas.markChangeDuringReset(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!arenas.hasResetting()) return;
        arenas.markChangeDuringReset(e.getBlock());
        for (Block b : e.blockList()) arenas.markChangeDuringReset(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!arenas.hasResetting()) return;
        arenas.markChangeDuringReset(e.getBlock());
        BlockFace f = e.getDirection();
        for (Block b : e.getBlocks()) {
            arenas.markChangeDuringReset(b);
            arenas.markChangeDuringReset(b.getRelative(f));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!arenas.hasResetting()) return;
        arenas.markChangeDuringReset(e.getBlock());
        for (Block b : e.getBlocks()) arenas.markChangeDuringReset(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        during(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        during(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        during(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent e) {
        if (!arenas.hasResetting()) return;
        for (org.bukkit.block.BlockState s : e.getBlocks()) arenas.markChangeDuringReset(s.getBlock());
    }
}
