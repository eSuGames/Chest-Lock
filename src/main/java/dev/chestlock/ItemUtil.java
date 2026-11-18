package dev.chestlock;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Chest;

/**
 * アイテム・ブロックの判定ユーティリティ。
 * Material 名で判定するため 26.2 / 26.3 のどちらでも動作する。
 */
public final class ItemUtil {

    private static final BlockFace[] HORIZONTAL = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private ItemUtil() {
    }

    /** 鉄チェーン */
    public static boolean isIronChain(Material material) {
        String name = material.name();
        return name.equals("IRON_CHAIN") || name.equals("CHAIN");
    }

    /** 銅チェーン（錆び・ワックス有無を問わない） */
    public static boolean isCopperChain(Material material) {
        return material.name().contains("COPPER_CHAIN");
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
     */
    public static Block findPartnerHalf(Block block) {
        if (!(block.getBlockData() instanceof Chest chestData)) {
            return null;
        }
        if (chestData.getType() == Chest.Type.SINGLE) {
            return null;
        }
        for (BlockFace face : HORIZONTAL) {
            Block relative = block.getRelative(face);
            if (relative.getType() != block.getType()) {
                continue;
            }
            if (!(relative.getBlockData() instanceof Chest other)) {
                continue;
            }
            if (other.getType() != Chest.Type.SINGLE && other.getFacing() == chestData.getFacing()) {
                return relative;
            }
        }
        return null;
    }
}
