package dev.chestlock.listener;

import dev.chestlock.ChestData;
import dev.chestlock.ChestKey;
import dev.chestlock.ChestLockPlugin;
import dev.chestlock.MojangApi;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 共有ユーザーの追加処理（金床での名前入力 + 判定不能時の確認 GUI）。
 */
public final class ShareInputListener implements Listener {

    /** 名前入力用アイテムの表示名。このまま確定された場合はキャンセル扱い。 */
    private static final String INPUT_PLACEHOLDER = "\u30e6\u30fc\u30b6\u30fc\u540d\u3092\u5165\u529b";

    private static final class PendingShare {
        final ChestKey key;
        String targetName;
        UUID targetUuid;
        boolean confirming;

        PendingShare(ChestKey key) {
            this.key = key;
        }
    }

    /** 確認 GUI（はい / いいえ）を識別するための Holder。 */
    private static final class ConfirmHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final ChestLockPlugin plugin;
    private final Map<UUID, PendingShare> pending = new HashMap<>();

    public ShareInputListener(ChestLockPlugin plugin) {
        this.plugin = plugin;
    }

    /** ユーザー名入力画面（金床）を開く。 */
    public void openInput(Player player, ChestKey key) {
        pending.put(player.getUniqueId(), new PendingShare(key));
        org.bukkit.block.Block block = key.toBlock();
        InventoryView view = player.openAnvil(block == null ? player.getLocation() : block.getLocation(), true);
        if (view instanceof AnvilView anvilView) {
            ItemStack paper = new ItemStack(Material.PAPER);
            ItemMeta meta = paper.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(INPUT_PLACEHOLDER);
                paper.setItemMeta(meta);
            }
            anvilView.getTopInventory().setFirstItem(paper);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        Inventory top = view.getTopInventory();
        if (top.getHolder() instanceof ConfirmHolder) {
            if (event.getRawSlot() >= top.getSize()) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            PendingShare ps = pending.get(player.getUniqueId());
            if (ps == null) {
                return;
            }
            if (event.getRawSlot() == 3) {
                // はい
                player.closeInventory();
                addShared(player, ps);
            } else if (event.getRawSlot() == 5) {
                // いいえ
                player.closeInventory();
                pending.remove(player.getUniqueId());
                plugin.getMessages().send(player, "confirm-cancel");
                plugin.getSounds().play("basic", player, player.getLocation());
            }
            return;
        }
        if (view instanceof AnvilView anvilView) {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            PendingShare ps = pending.get(player.getUniqueId());
            if (ps == null || ps.confirming) {
                return;
            }
            // 金床内のアイテムの持ち出しを防ぐ
            event.setCancelled(true);
            if (event.getRawSlot() == 2) {
                // 結果スロットのクリック = 入力確定
                String text = anvilView.getRenameText();
                pending.remove(player.getUniqueId());
                player.closeInventory();
                handleName(player, ps, text);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ConfirmHolder || event.getView() instanceof AnvilView) {
            for (int slot : event.getRawSlots()) {
                if (slot < top.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        PendingShare ps = pending.get(player.getUniqueId());
        if (ps == null || ps.confirming) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (!(event.getView() instanceof AnvilView anvilView)) {
            return;
        }
        String text = anvilView.getRenameText();
        pending.remove(player.getUniqueId());
        handleName(player, ps, text);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    /** 入力された名前を処理する。 */
    private void handleName(Player player, PendingShare ps, String rawName) {
        String name = rawName == null ? "" : rawName.replaceAll("(?i)\u00A7.", "").trim();
        if (name.isEmpty() || name.equals(INPUT_PLACEHOLDER)) {
            // 入力なしで閉じられた場合はキャンセル扱い
            return;
        }
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            plugin.getMessages().send(player, "share-not-found", "player", name);
            plugin.getSounds().play("basic", player, player.getLocation());
            return;
        }
        ps.targetName = name;

        // 1. オンラインのプレイヤーなら即確定
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            ps.targetUuid = online.getUniqueId();
            addShared(player, ps);
            return;
        }

        // 2. Mojang API で存在確認（非同期）
        pending.put(player.getUniqueId(), ps);
        MojangApi.lookup(name).whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            PendingShare current = pending.get(player.getUniqueId());
            if (current != ps || !player.isOnline()) {
                return;
            }
            if (error == null && result != null && result.exists()) {
                ps.targetUuid = result.uuid();
                addShared(player, ps);
            } else if (error == null && result != null) {
                pending.remove(player.getUniqueId());
                plugin.getMessages().send(player, "share-not-found", "player", name);
                plugin.getSounds().play("basic", player, player.getLocation());
            } else {
                // 判定不能：追加するか確認する
                openConfirm(player, ps);
            }
        }));
    }

    /** 共有ユーザーとして追加する。 */
    private void addShared(Player player, PendingShare ps) {
        pending.remove(player.getUniqueId());
        ChestData data = plugin.getStore().getDirect(ps.key).orElse(null);
        if (data == null || data.getType() != ChestData.Type.PRIVATE || !data.isOwner(player)) {
            return;
        }
        if (data.isShared(ps.targetName, ps.targetUuid)) {
            plugin.getMessages().send(player, "share-already", "player", ps.targetName);
            plugin.getSounds().play("basic", player, player.getLocation());
            return;
        }
        data.addShared(ps.targetName, ps.targetUuid);
        plugin.getStore().save();
        plugin.getMessages().send(player, "share-added", "player", ps.targetName);
        plugin.getSounds().play("basic", player, player.getLocation());
    }

    /** 判定できなかった場合の確認 GUI（はい / いいえ）。 */
    private void openConfirm(Player player, PendingShare ps) {
        ps.confirming = true;
        String title = plugin.getMessages().format("confirm-title", "player", ps.targetName);
        ConfirmHolder holder = new ConfirmHolder();
        Inventory inventory = Bukkit.createInventory(holder, 9, title);
        holder.inventory = inventory;

        ItemStack yes = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta yesMeta = yes.getItemMeta();
        if (yesMeta != null) {
            yesMeta.setDisplayName(plugin.getMessages().get("confirm-yes"));
            yes.setItemMeta(yesMeta);
        }
        ItemStack no = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta noMeta = no.getItemMeta();
        if (noMeta != null) {
            noMeta.setDisplayName(plugin.getMessages().get("confirm-no"));
            no.setItemMeta(noMeta);
        }
        inventory.setItem(3, yes);
        inventory.setItem(5, no);
        player.openInventory(inventory);
    }
}
