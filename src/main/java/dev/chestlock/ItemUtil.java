package dev.chestlock;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.type.Chest;
import org.bukkit.inventory.InventoryHolder;

/**
 * アイテム・ブロックの判定ユーティリティ。
 * Material 名で判定するため 26.2 / 26.3 のどちらでも動作する。
 */
public final class ItemUtil {

    private ItemUtil() {
    }

    /** 鉄チェーン */
    public static boolean isIronChain(Material material) {
        String name = material.name();
        return name.equals("IRON_CHAIN") || name.equals("CHAIN");
    }

    /** 銅チェーン（錆び・ワックス有無を問わない） */
    public static boolean isCopperChain(Material material) {
        return name(material).contains("COPPER_CHAIN");
    }

    private static String name(Material material) {
        return material.name();
    }

    /** 斧（素材不問） */
    public static boolean isAxe(Material material) {
        return material.name().endsWith("_AXE");
    }

    /** 通常チェスト / トラップチェスト */
    public static boolean isChest(Material material) {
        String name = material.name();
        return name.equals("CHEST") || name.equals("TRAPPED_CHEST");
    }

    /** 通常チェスト / トラップチェストのブロックか */
    public static boolean isChestBlock(Block block) {
        return isChest(block.getType());
    }

    /**
     * 同一のラージチェストを構成するもう片方の半分を返す。
     * ラージチェストでない場合は null。
     *
     * サーバーが管理する実体（DoubleChest）から特定するため、
     * 隣接する別のラージチェストと取り違えることはない。
     */
    public static Block findPartnerHalf(Block block) {
        if (!(block.getBlockData() instanceof Chest chestData)) {
            return null;
        }
        if (chestData.getType() == Chest.Type.SINGLE) {
            return null;
        }
        if (!(block.getState() instanceof Container container)) {
            return null;
        }
        InventoryHolder holder = container.getInventory().getHolder();
        if (holder instanceof DoubleChest doubleChest) {
            Block left = sideBlock(doubleChest.getLeftSide());
            if (left != null && !sameBlock(left, block)) {
                return left;
            }
            Block right = sideBlock(doubleChest.getRightSide());
            if (right != null && !sameBlock(right, block)) {
                return right;
            }
        }
        return null;
    }

    private static Block sideBlock(InventoryHolder side) {
        if (side instanceof BlockState state) {
            return state.getBlock();
        }
        return null;
    }

    private static boolean sameBlock(Block a, Block b) {
        return a.getWorld().equals(b.getWorld())
                && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }
}
