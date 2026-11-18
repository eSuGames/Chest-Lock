package dev.chestlock;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;

/**
 * チェストの座標を表す不変キー。
 */
public record ChestKey(String world, int x, int y, int z) {

    public static ChestKey of(Block block) {
        return new ChestKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public Block toBlock() {
        var w = Bukkit.getWorld(world);
        return w == null ? null : w.getBlockAt(x, y, z);
    }

    /** data.yml 内のキー文字列。 */
    public String toKeyString() {
        return world + "," + x + "," + y + "," + z;
    }

    public static ChestKey fromKeyString(String s) {
        String[] parts = s.split(",", -1);
        if (parts.length != 4) {
            return null;
        }
        try {
            return new ChestKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return world + " (" + x + ", " + y + ", " + z + ")";
    }
}
