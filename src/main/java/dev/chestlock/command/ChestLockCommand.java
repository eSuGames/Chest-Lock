package dev.chestlock.command;

import dev.chestlock.ChestData;
import dev.chestlock.ChestKey;
import dev.chestlock.ChestLockPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * /chestlock コマンド。
 * /chestlock version
 * /chestlock list
 * /chestlock unlock x y z [world]
 * /chestlock status x y z [world]
 */
public final class ChestLockCommand implements CommandExecutor, TabCompleter {

    private final ChestLockPlugin plugin;

    public ChestLockCommand(ChestLockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            reply(sender, "command-usage", "label", label);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "version" -> {
                reply(sender, "command-version", "version", plugin.getPluginMeta().getVersion());
                return true;
            }
            case "list" -> {
                if (!sender.hasPermission("chestlock.command.list")) {
                    reply(sender, "command-no-permission");
                    return true;
                }
                sendList(sender);
                return true;
            }
            case "unlock", "status" -> {
                return handleCoordinateCommand(sender, label, args);
            }
            default -> {
                reply(sender, "command-usage", "label", label);
                return true;
            }
        }
    }

    private boolean handleCoordinateCommand(CommandSender sender, String label, String[] args) {
        String sub = args[0].toLowerCase();
        if (!sender.hasPermission("chestlock.command." + sub)) {
            reply(sender, "command-no-permission");
            return true;
        }
        if (args.length < 4) {
            reply(sender, "command-usage", "label", label);
            return true;
        }
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(args[1]);
            y = Integer.parseInt(args[2]);
            z = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            reply(sender, "command-usage", "label", label);
            return true;
        }
        World world;
        if (args.length >= 5) {
            world = Bukkit.getWorld(args[4]);
            if (world == null) {
                reply(sender, "command-world-not-found", "world", args[4]);
                return true;
            }
        } else if (sender instanceof Player player) {
            world = player.getWorld();
        } else {
            world = Bukkit.getWorlds().get(0);
        }
        ChestKey key = new ChestKey(world.getName(), x, y, z);

        if (sub.equals("status")) {
            Optional<ChestData> data = plugin.getStore().getDirect(key);
            String status = data.map(d -> d.getType() == ChestData.Type.PUBLIC ? "公共" : "ロック").orElse("未設定");
            reply(sender, "command-status",
                    "world", world.getName(), "x", String.valueOf(x), "y", String.valueOf(y),
                    "z", String.valueOf(z), "status", status);
            return true;
        }

        // unlock
        Optional<ChestData> data = plugin.getStore().getDirect(key);
        if (data.isEmpty()) {
            reply(sender, "command-unlock-none",
                    "world", world.getName(), "x", String.valueOf(x), "y", String.valueOf(y), "z", String.valueOf(z));
            return true;
        }
        plugin.getStore().unregister(key);
        reply(sender, "command-unlock-success",
                "world", world.getName(), "x", String.valueOf(x), "y", String.valueOf(y), "z", String.valueOf(z));
        return true;
    }

    private void sendList(CommandSender sender) {
        Map<ChestKey, ChestData> all = plugin.getStore().all();
        List<Map.Entry<ChestKey, ChestData>> entries = new ArrayList<>(all.entrySet());
        entries.sort((a, b) -> {
            ChestKey ka = a.getKey();
            ChestKey kb = b.getKey();
            int c = ka.world().compareTo(kb.world());
            if (c != 0) return c;
            c = Integer.compare(ka.x(), kb.x());
            if (c != 0) return c;
            c = Integer.compare(ka.y(), kb.y());
            return Integer.compare(ka.z(), kb.z());
        });
        reply(sender, "command-list-header", "count", String.valueOf(entries.size()));
        for (Map.Entry<ChestKey, ChestData> entry : entries) {
            ChestKey key = entry.getKey();
            ChestData data = entry.getValue();
            reply(sender, "command-list-entry",
                    "world", key.world(),
                    "x", String.valueOf(key.x()),
                    "y", String.valueOf(key.y()),
                    "z", String.valueOf(key.z()),
                    "type", data.getType() == ChestData.Type.PUBLIC ? "公共" : "ロック",
                    "owner", data.getOwnerName());
        }
    }

    /** チャット表示と基本音（行動したプレイヤーのみ）。 */
    private void reply(CommandSender sender, String key, String... replacements) {
        plugin.getMessages().send(sender, key, replacements);
        if (sender instanceof Player player) {
            plugin.getSounds().play("basic", player, player.getLocation());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("version", "list", "unlock", "status"), args[0]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(prefix.toLowerCase())) {
                result.add(option);
            }
        }
        return result;
    }
}
