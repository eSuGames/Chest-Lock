package dev.chestlock;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * config.yml の sounds セクションから効果音を再生する。
 * 効果音が聞こえるのは行動した本人のみ。
 * サウンド名は定数名（例: BLOCK_ANVIL_USE）またはサウンドキー（例: block.anvil.use）。
 * 空欄なら再生しない。
 */
public final class Sounds {

    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Sound> resolved = new HashMap<>();
    private Logger logger;

    /** ユーザー設定を優先して読み込む。起動時に全サウンド名を検証する。 */
    public void load(FileConfiguration config, FileConfiguration defaults, Logger logger) {
        this.logger = logger;
        names.clear();
        resolved.clear();
        loadSection(defaults);
        loadSection(config);
        for (Map.Entry<String, String> entry : names.entrySet()) {
            String name = entry.getValue();
            if (name.isEmpty()) {
                continue;
            }
            Sound sound = resolve(name);
            if (sound == null) {
                if (logger != null) {
                    logger.warning("サウンド '" + name + "' (" + entry.getKey() + ") が見つかりません。config.yml を確認してください。");
                }
            } else {
                resolved.put(entry.getKey(), sound);
            }
        }
    }

    private void loadSection(FileConfiguration source) {
        ConfigurationSection section = source.getConfigurationSection("sounds");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            // この設定ファイル自身に定義されたキーのみ上書きする（空欄で無効化も可能）
            String value = section.getString(key, null);
            if (value != null) {
                names.put(key, value);
            } else {
                names.putIfAbsent(key, "");
            }
        }
    }

    public void play(String key, Player player, Location location) {
        Sound sound = resolved.get(key);
        if (sound == null) {
            return;
        }
        player.playSound(location, sound, 1.0f, 1.0f);
    }

    /** サウンド名（定数名 / サウンドキー）から Sound を解決する。 */
    private Sound resolve(String name) {
        // 定数名（例: BLOCK_ANVIL_USE）
        try {
            Field field = Sound.class.getField(name.toUpperCase(Locale.ROOT));
            Object value = field.get(null);
            if (value instanceof Sound sound) {
                return sound;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        // サウンドキー（例: block.anvil.use）
        String key = name.toLowerCase(Locale.ROOT);
        if (key.indexOf('.') >= 0) {
            return Registry.SOUNDS.get(NamespacedKey.minecraft(key));
        }
        return null;
    }
}
