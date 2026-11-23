package dev.chestlock.listener;

import dev.chestlock.ChestData;
import dev.chestlock.ChestKey;
import dev.chestlock.ChestLockPlugin;
import dev.chestlock.ItemUtil;
import dev.chestlock.ThreatTracker;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

/**
 * チェストの設定・解除・破壊制限・増結を扱うリスナー。
 */
public final class ChestLockListener implements Listener {

    private final ChestLockPlugin plugin;
    private final ThreatTracker threatTracker;

    public ChestLockListener(ChestLockPlugin plugin) {
        this.plugin = plugin;
        this.threatTracker = new ThreatTracker(
                plugin.getConfig().getInt("penalty.attempt-window-minutes", 10) * 60_000L,
                plugin.getConfig().getInt("penalty.alert-cooldown-minutes", 5) * 60_000L,
                plugin.getConfig().getInt("penalty.public-alert-cooldown-minutes", 10) * 60_000L);
    }

    /** 手に持ったチェーンを 1 つ消費する（クリエイティブは除く）。 */
    private void consumeHeldItem(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
        }
    }

    /** OP かつクリエイティブなら設定の変更が可能。 */
    private boolean isAdmin(Player player) {
        return player.isOp() && player.getGameMode() == GameMode.CREATIVE;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !ItemUtil.isChestBlock(block)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Material material = item == null ? Material.AIR : item.getType();
        ChestData data = plugin.getStore().get(block).orElse(null);
        if (data != null && data.backfillSharedUuid(player)) {
            // UUID が判明した共有ユーザー情報を保存する
            plugin.getStore().update(block, data).whenComplete((_, resErr) -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (resErr != null) {
                        plugin.getLogger().log(Level.SEVERE, "共有ユーザー情報の保存に失敗しました", resErr);
                    }
                });
            });
        }
        if (player.isSneaking() && ItemUtil.isIronChain(material)) {
            // 鉄チェーン + シフト右クリック：公共用チェストに設定
            event.setCancelled(true);
            if (data != null && !isAdmin(player)) {
                plugin.getMessages().send(player, "already-set");
                plugin.getSounds().play("basic", player, block.getLocation());
                return;
            }
            consumeHeldItem(player);
            plugin.getStore().register(block, ChestData.publicChest(player)).whenComplete((_, resErr) -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (resErr == null) {
                        plugin.getMessages().send(player, "public-set");
                        plugin.getSounds().play("public", player, block.getLocation());
                    } else {
                        plugin.getLogger().log(Level.SEVERE, "チェストの保存に失敗しました", resErr);
                        plugin.getMessages().send(player, "save-lock-error");
                    }
                });
            });
            return;
        }

        // 銅チェーン + シフト右クリック：個人用チェストに設定
        if (player.isSneaking() && ItemUtil.isCopperChain(material)) {
            event.setCancelled(true);
            if (data != null && !isAdmin(player)) {
                plugin.getMessages().send(player, "already-set");
                plugin.getSounds().play("basic", player, block.getLocation());
                return;
            }
            consumeHeldItem(player);
            plugin.getStore().register(block, ChestData.privateChest(player)).whenComplete((_, resErr) -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (resErr == null) {
                        plugin.getMessages().send(player, "lock-set");
                        plugin.getSounds().play("lock", player, block.getLocation());
                    } else {
                        plugin.getLogger().log(Level.SEVERE, "チェストの保存に失敗しました", resErr);
                        plugin.getMessages().send(player, "save-lock-error");
                    }
                });
            });
            return;
        }

        // 銅チェーン + 右クリック：共有ユーザーの入力画面を開く（個人用チェストの所有者のみ）
        if (ItemUtil.isCopperChain(material) && !player.isSneaking()) {
            if (data != null && data.getType() == ChestData.Type.PRIVATE && data.isOwner(player)) {
                event.setCancelled(true);
                plugin.getShareInput().openInput(player, ChestKey.of(block));
                return;
            }
        }

        // 斧 + 右クリック：個人用設定の解除
        if (ItemUtil.isAxe(material)) {
            if (data == null) {
                return;
            }
            // 公共用チェストは解除不可（OP かつクリエイティブは変更可能）
            if (data.getType() == ChestData.Type.PUBLIC && !isAdmin(player)) {
                return;
            }
            event.setCancelled(true);
            if (!data.isOwner(player) && !isAdmin(player)) {
                plugin.getMessages().send(player, "unlock-not-owner");
                plugin.getSounds().play("unlock-denied", player, block.getLocation());
                return;
            }
            plugin.getStore().unregister(block);
            plugin.getMessages().send(player, "unlock-success");
            plugin.getSounds().play("unlock", player, block.getLocation());
            return;
        }

        // 通常の開閉：個人用チェストは所有者と共有ユーザーのみ
        if (data != null && data.getType() == ChestData.Type.PRIVATE && !data.canUse(player)) {
            event.setCancelled(true);
            plugin.getMessages().send(player, "locked-no-use");
            plugin.getSounds().play("use-denied", player, block.getLocation());
            handleThreat(player, canonicalKey(block), data, false, block);
        }
    }

    /** ラージチェストの左右を同じ試行として数えるための正規化キー。 */
    private ChestKey canonicalKey(Block block) {
        Block partner = ItemUtil.findPartnerHalf(block);
        if (partner == null) {
            return ChestKey.of(block);
        }
        ChestKey a = ChestKey.of(block);
        ChestKey b = ChestKey.of(partner);
        if (a.x() != b.x()) {
            return a.x() < b.x() ? a : b;
        }
        if (a.y() != b.y()) {
            return a.y() < b.y() ? a : b;
        }
        return a.z() <= b.z() ? a : b;
    }

    /** 試行の段階に応じた警告・ペナルティ処理。 */
    private void handleThreat(Player player, ChestKey key, ChestData data, boolean breaking, Block block) {
        ThreatTracker.Step step = threatTracker.record(player.getUniqueId(), key,
                data.getType() == ChestData.Type.PUBLIC);
        switch (step) {
            case STEP2 -> {
                plugin.getMessages().send(player, "attempt-warn2");
                plugin.getSounds().play("basic", player, block.getLocation());
            }
            case STEP3 -> {
                warnUsers(data, player);
                plugin.getMessages().send(player, "attempt-warn3-self");
                plugin.getSounds().play("basic", player, block.getLocation());
            }
            case STEP4 -> {
                plugin.getMessages().send(player, "attempt-warn4");
                plugin.getSounds().play("basic", player, block.getLocation());
            }
            case STEP5 -> {
                notifyPvpEnabled(data, player, breaking, block);
                plugin.getPvpRetaliation().open(player, data);
            }
            case NONE -> {
                // 何もしない
            }
        }
    }

    /** 5 分間の PvP ペナルティ開始を、所有者・共有ユーザー（破壊時は半径 15m のプレイヤーも）に通知。 */
    private void notifyPvpEnabled(ChestData data, Player offender, boolean breaking, Block block) {
        Set<Player> sent = new HashSet<>();
        Player owner = data.getOwnerUuid() == null ? null : Bukkit.getPlayer(data.getOwnerUuid());
        if (owner != null) {
            plugin.getMessages().send(owner, "pvp-enabled", "player", offender.getName());
            plugin.getSounds().play("warn", owner, owner.getLocation());
            sent.add(owner);
        }
        for (ChestData.SharedUser u : data.getShared()) {
            Player shared = null;
            if (u.uuid() != null) {
                shared = Bukkit.getPlayer(u.uuid());
            }
            if (shared == null && u.name() != null) {
                shared = Bukkit.getPlayerExact(u.name());
            }
            if (shared != null && sent.add(shared)) {
                plugin.getMessages().send(shared, "pvp-enabled", "player", offender.getName());
                plugin.getSounds().play("warn", shared, shared.getLocation());
            }
        }
        if (breaking) {
            for (Player near : block.getWorld().getPlayers()) {
                if (near.getLocation().distanceSquared(block.getLocation()) <= 15.0 * 15.0 && sent.add(near)) {
                    plugin.getMessages().send(near, "pvp-enabled", "player", offender.getName());
                    plugin.getSounds().play("warn", near, near.getLocation());
                }
            }
        }
    }

    /** 短時間に何度も開けようとした場合、所有者と共有ユーザーに警告を送る。 */
    private void warnUsers(ChestData data, Player offender) {
        Player owner = data.getOwnerUuid() == null ? null : Bukkit.getPlayer(data.getOwnerUuid());
        if (owner != null) {
            plugin.getMessages().send(owner, "warn-owner", "player", offender.getName());
            plugin.getSounds().play("warn", owner, owner.getLocation());
        }
        for (ChestData.SharedUser u : data.getShared()) {
            Player shared = null;
            if (u.uuid() != null) {
                shared = Bukkit.getPlayer(u.uuid());
            }
            if (shared == null && u.name() != null) {
                shared = Bukkit.getPlayerExact(u.name());
            }
            if (shared != null && !shared.equals(owner)) {
                plugin.getMessages().send(shared, "warn-shared", "player", offender.getName());
                plugin.getSounds().play("warn", shared, shared.getLocation());
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!ItemUtil.isChestBlock(block)) {
            return;
        }
        ChestData data = plugin.getStore().get(block).orElse(null);
        if (data == null) {
            return;
        }
        Player player = event.getPlayer();
        if (data.isOwner(player)) {
            // 解除処理は onBreakConfirmed（MONITOR）で破壊確定後に実施する
            return;
        }
        event.setCancelled(true);
        plugin.getSounds().play("break-denied", player, block.getLocation());
        if (data.getType() == ChestData.Type.PUBLIC) {
            plugin.getMessages().send(player, "public-no-break", "owner", data.getOwnerName());
        } else {
            plugin.getMessages().send(player, "private-no-break", "owner", data.getOwnerName());
        }
        handleThreat(player, canonicalKey(block), data, true, block);
    }

    /**
     * 破壊が最終的に許可された場合のみロックを解除する。
     * 他プラグイン（土地保護等）が後から破壊をキャンセルしたときに、
     * チェストが残ったまま設定だけ消えるのを防ぐ。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakConfirmed(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!ItemUtil.isChestBlock(block)) {
            return;
        }
        ChestData data = plugin.getStore().get(block).orElse(null);
        if (data == null || !data.isOwner(event.getPlayer())) {
            return;
        }
        plugin.getStore().unregister(block);
        plugin.getMessages().send(event.getPlayer(), "break-removed");
        plugin.getSounds().play("break", event.getPlayer(), block.getLocation());
    }

    /** 爆爆発（TNT・クリーパー等）による設定済みチェストの破壊を防ぐ。 */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protectFromExplosion(event.blockList());
    }

    /** 爆発（ベッド・リスポーンアンカー等）による設定済みチェストの破壊を防ぐ。 */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protectFromExplosion(event.blockList());
    }

    private void protectFromExplosion(List<Block> blocks) {
        blocks.removeIf(block -> ItemUtil.isChestBlock(block) && plugin.getStore().get(block).isPresent());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (!ItemUtil.isChestBlock(block)) {
            return;
        }
        if (!(block.getBlockData() instanceof Chest newChest)) {
            return;
        }
        // 設定済みチェストに増結される場合、増結できるのは所有者のみ
        Block neighbor = mergedNeighbor(block, newChest);
        if (neighbor == null) {
            return;
        }
        Optional<ChestData> existing = plugin.getStore().getDirect(neighbor);
        if (existing.isEmpty()) {
            return;
        }
        if (!existing.get().isOwner(event.getPlayer())) {
            event.setCancelled(true);
            plugin.getMessages().send(event.getPlayer(), "extend-not-owner");
        }
    }

    /** 増結が確定してから設定を引き継ぐ（他プラグインによるキャンセル対策）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceConfirmed(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (!ItemUtil.isChestBlock(block)) {
            return;
        }
        if (!(block.getBlockData() instanceof Chest newChest)) {
            return;
        }
        Block neighbor = mergedNeighbor(block, newChest);
        if (neighbor == null) {
            return;
        }
        Optional<ChestData> existing = plugin.getStore().getDirect(neighbor);
        if (existing.isEmpty() || !existing.get().isOwner(event.getPlayer())) {
            return;
        }
        plugin.getStore().set(block, existing.get());
        plugin.getMessages().send(event.getPlayer(), "large-inherit");
        plugin.getSounds().play("extend", event.getPlayer(), block.getLocation());
    }

    /** 増結合の相手になる隣接チェスト。実際に結合していない場合は null。 */
    private Block mergedNeighbor(Block block, Chest newChest) {
        for (Block neighbor : new Block[] {
                block.getRelative(org.bukkit.block.BlockFace.NORTH),
                block.getRelative(org.bukkit.block.BlockFace.SOUTH),
                block.getRelative(org.bukkit.block.BlockFace.EAST),
                block.getRelative(org.bukkit.block.BlockFace.WEST) }) {
            if (neighbor.getType() != block.getType()) {
                continue;
            }
            if (!(neighbor.getBlockData() instanceof Chest neighborChest)) {
                continue;
            }
            if (neighborChest.getFacing() != newChest.getFacing()) {
                continue;
            }
            boolean merged = newChest.getType() != Chest.Type.SINGLE
                    && neighborChest.getType() != Chest.Type.SINGLE;
            if (merged) {
                return neighbor;
            }
        }
        return null;
    }
}
