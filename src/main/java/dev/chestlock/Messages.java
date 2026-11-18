package dev.chestlock;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * config.yml の messages セクションからチャットメッセージを読み込む。
 * プレースホルダは {name} 形式。ユーザー設定を優先し、無いキーは初期値を使う。
 */
public final class Messages {

    private final Map<String, String> values = new LinkedHashMap<>();

    /** ユーザー設定を優先して読み込む。 */
    public void load(FileConfiguration config, FileConfiguration defaults) {
        values.clear();
        loadSection(defaults);
        loadSection(config);
    }

    private void loadSection(FileConfiguration source) {
        ConfigurationSection section = source.getConfigurationSection("messages");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            // この設定ファイル自身に定義されたキーのみ上書きする
            String value = section.getString(key, null);
            if (value != null) {
                values.put(key, value);
            } else {
                values.putIfAbsent(key, "");
            }
        }
    }

    public String get(String key) {
        return values.getOrDefault(key, key);
    }

    public String format(String key, String... replacements) {
        String message = get(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            message = message.replace("{" + replacements[i] + "}", replacements[i + 1]);
        }
        return message;
    }

    public void send(CommandSender to, String key, String... replacements) {
        String message = format(key, replacements);
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        to.sendMessage(message);
    }
}
