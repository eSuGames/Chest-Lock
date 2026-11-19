package dev.chestlock.listener;

import dev.chestlock.ChestData;
import dev.chestlock.ChestLockPlugin;
import dev.chestlock.ItemUtil;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * ホッパー・ホッパー付きトロッコの制限。
 * - 個人用チェスト：所有者と共有ユーザーのみホッパーの設置・使用が可能
 * - 公共用チェスト：ホッパーの使用は自由
 */
public final class HopperListener implements Listener {

    private final ChestLockPlugin plugin;

    public HopperListener(ChestLockPlugin plugin) {
        this.plugin = plugin;
    }

    /** インベントリが個人用チェストに属していればそのデータを返す。 */
    private ChestData lockedChestOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof DoubleChest doubleChest) {
            ChestData data = dataOfHolder(doubleChest.getLeftSide());
            return data != null ? data : dataOfHolder(doubleChest.getRightSide());
        }
        return dataOfHolder(holder);
    }

    private ChestData dataOfHolder(InventoryHolder holder) {
        if (holder instanceof org.bukkit.block.Chest chest) {
            ChestData data = plugin.getStore().get(chest.getBlock()).orElse(null);
            if (data != null && data.getType() == ChestData.Type.PRIVATE) {
                return data;
            }
        }
        return null;
    }

    /** 搬送元/先がホッパー系であれば設置者の UUID を返す。 */
    private UUID hopperOwnerOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof org.bukkit.block.Hopper hopper) {
            return plugin.getStore().getHopperOwner(hopper.getBlock());
        }
        if (holder instanceof HopperMinecart minecart) {
            return plugin.getStore().getMinecartOwner(minecart.getUniqueId());
        }
        return null;
    }

    /** ブロックが個人用チェストであればそのデータを返す。 */
    private ChestData lockedChestDataAt(Block block) {
        if (!ItemUtil.isChestBlock(block)) {
            return null;
        }
        ChestData data = plugin.getStore().get(block).orElse(null);
        return data != null && data.getType() == ChestData.Type.PRIVATE ? data : null;
    }

    /** ホッパーがアイテムをやり取りする相手の個人用チェスト。 */
    private ChestData touchedLockedChest(Block hopperBlock, org.bukkit.block.data.type.Hopper hopperData) {
        ChestData up = lockedChestDataAt(hopperBlock.getRelative(BlockFace.UP));
        if (up != null) {
            return up;
        }
        return lockedChestDataAt(hopperBlock.getRelative(hopperData.getFacing()));
    }

    /** ホッパーが触れる個人用チェストのブロック。 */
    private Block touchedLockedChestBlock(Block hopperBlock, org.bukkit.block.data.type.Hopper hopperData) {
        Block up = hopperBlock.getRelative(BlockFace.UP);
        if (lockedChestDataAt(up) != null) {
            return up;
        }
        Block front = hopperBlock.getRelative(hopperData.getFacing());
        return lockedChestDataAt(front) != null ? front : null;
    }

    /** 個人用チェストとのアイテム搬送は所有者・共有ユーザーのホッパーのみ許可する。 */
    @EventHandler(ignoreCancelled = true)
    public void onItemMove(InventoryMoveItemEvent event) {
        ChestData sourceData = lockedChestOf(event.getSource());
        ChestData destData = lockedChestOf(event.getDestination());
        if (sourceData == null && destData == null) {
            return;
        }
        if (sourceData != null && destData != null) {
            // 搬送は必ずホッパー系を経由するため、両方チェストになることはない
            return;
        }
        ChestData locked = sourceData != null ? sourceData : destData;
        Inventory other = sourceData != null ? event.getDestination() : event.getSource();
        UUID hopperOwner = hopperOwnerOf(other);
        if (!locked.canUse(hopperOwner)) {
            event.setCancelled(true);
        }
    }

    /** ホッパーの設置：個人用チェストに触れる位置は所有者と共有ユーザーのみ。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopperPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.HOPPER) {
            return;
        }
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Hopper hopperData)) {
            return;
        }
        Player player = event.getPlayer();
        ChestData touched = touchedLockedChest(block, hopperData);
        if (touched != null && !touched.canUse(player)) {
            event.setCancelled(true);
            plugin.getMessages().send(player, "hopper-not-owner");
        }
    }

    /** 設置が確定してから設置者を記録する（他プラグインによるキャンセル対策）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperPlaceConfirmed(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.HOPPER) {
            return;
        }
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Hopper hopperData)) {
            return;
        }
        Player player = event.getPlayer();
        Block touchedBlock = touchedLockedChestBlock(block, hopperData);
        if (touchedBlock != null) {
            ChestData touched = plugin.getStore().get(touchedBlock).orElse(null);
            if (touched != null && touched.backfillSharedUuid(player)) {
                plugin.getStore().update(touchedBlock, touched);
            }
        }
        plugin.getStore().setHopperOwner(block, player.getUniqueId());
    }

    /** ホッパー付きトロッコの設置：個人用チェストの直上・直下は所有者と共有ユーザーのみ。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMinecartPlace(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof HopperMinecart minecart)) {
            return;
        }
        Player player = event.getPlayer();
        Block touchedBlock = minecartTouchedChestBlock(minecart);
        ChestData touched = touchedBlock == null ? null : lockedChestDataAt(touchedBlock);
        if (touched != null && !touched.canUse(player)) {
            event.setCancelled(true);
            plugin.getMessages().send(player, "hopper-not-owner");
        }
    }

    /** 設置が確定してから設置者を記録する（他プラグインによるキャンセル対策）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinecartPlaceConfirmed(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof HopperMinecart minecart)) {
            return;
        }
        Player player = event.getPlayer();
        Block touchedBlock = minecartTouchedChestBlock(minecart);
        if (touchedBlock != null) {
            ChestData touched = plugin.getStore().get(touchedBlock).orElse(null);
            if (touched != null && touched.backfillSharedUuid(player)) {
                plugin.getStore().update(touchedBlock, touched);
            }
        }
        plugin.getStore().setMinecartOwner(minecart.getUniqueId(), player.getUniqueId());
    }

    private Block minecartTouchedChestBlock(HopperMinecart minecart) {
        Block at = minecart.getLocation().getBlock();
        if (lockedChestDataAt(at) != null) {
            return at;
        }
        Block down = at.getRelative(BlockFace.DOWN);
        return lockedChestDataAt(down) != null ? down : null;
    }

    /** ホッパー破壊が確定してから設置者記録を削除する。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.HOPPER) {
            plugin.getStore().removeHopper(event.getBlock());
        }
    }
}
