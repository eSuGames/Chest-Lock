package dev.chestlock.storage;

import dev.chestlock.ChestData;
import dev.chestlock.ChestKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * data.yml による永続化。
 */
public final class YamlStorage implements StorageBackend {

    private final File file;
    private final Logger logger;

    public YamlStorage(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    @Override
    public LoadedData load() {
        Map<ChestKey, ChestData> chests = new LinkedHashMap<>();
        Map<String, UUID> hoppers = new LinkedHashMap<>();
        Map<UUID, UUID> minecarts = new LinkedHashMap<>();
        if (!file.exists()) {
            return new LoadedData(chests, hoppers, minecarts);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("chests");
        if (section != null) {
            for (String keyString : section.getKeys(false)) {
                ChestKey key = ChestKey.fromKeyString(keyString);
                ConfigurationSection entry = section.getConfigurationSection(keyString);
                if (key == null || entry == null) {
                    continue;
                }
                ChestData.Type type;
                try {
                    type = ChestData.Type.valueOf(entry.getString("type", "PRIVATE"));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                ChestData data = new ChestData(type, entry.getString("owner", "unknown"), parseUuid(entry.getString("owner-uuid")));
                for (Map<?, ?> raw : entry.getMapList("shared")) {
                    Object name = raw.get("name");
                    Object uuid = raw.get("uuid");
                    data.addShared(name == null ? null : name.toString(), uuid == null ? null : parseUuid(uuid.toString()));
                }
                chests.put(key, data);
            }
        }
        ConfigurationSection hopperSection = yaml.getConfigurationSection("hoppers");
        if (hopperSection != null) {
            for (String key : hopperSection.getKeys(false)) {
                UUID owner = parseUuid(hopperSection.getString(key));
                if (owner != null) {
                    hoppers.put(key, owner);
                }
            }
        }
        ConfigurationSection minecartSection = yaml.getConfigurationSection("hopper-minecarts");
        if (minecartSection != null) {
            for (String key : minecartSection.getKeys(false)) {
                UUID entity = parseUuid(key);
                UUID owner = parseUuid(minecartSection.getString(key));
                if (entity != null && owner != null) {
                    minecarts.put(entity, owner);
                }
            }
        }
        return new LoadedData(chests, hoppers, minecarts);
    }

    @Override
    public void saveChest(ChestKey key, ChestData data) {
        // data.yml は全体を書き直す方式
    }

    @Override
    public void deleteChest(ChestKey key) {
        // data.yml は全体を書き直す方式
    }

    @Override
    public void saveHopper(String key, UUID owner) {
        // data.yml は全体を書き直す方式
    }

    @Override
    public void deleteHopper(String key) {
        // data.yml は全体を書き直す方式
    }

    @Override
    public void saveMinecart(UUID entityId, UUID owner) {
        // data.yml は全体を書き直す方式
    }

    @Override
    public void flush(LoadedData data) {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection section = yaml.createSection("chests");
        for (Map.Entry<ChestKey, ChestData> entry : data.chests().entrySet()) {
            String keyString = entry.getKey().toKeyString();
            ChestData chest = entry.getValue();
            section.set(keyString + ".type", chest.getType().name());
            section.set(keyString + ".owner", chest.getOwnerName());
            section.set(keyString + ".owner-uuid", chest.getOwnerUuid() == null ? null : chest.getOwnerUuid().toString());
            List<Map<String, Object>> shared = new ArrayList<>();
            for (ChestData.SharedUser user : chest.getShared()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", user.name());
                row.put("uuid", user.uuid() == null ? null : user.uuid().toString());
                shared.add(row);
            }
            section.set(keyString + ".shared", shared);
        }
        ConfigurationSection hoppers = yaml.createSection("hoppers");
        for (Map.Entry<String, UUID> entry : data.hoppers().entrySet()) {
            hoppers.set(entry.getKey(), entry.getValue().toString());
        }
        ConfigurationSection minecarts = yaml.createSection("hopper-minecarts");
        for (Map.Entry<UUID, UUID> entry : data.minecarts().entrySet()) {
            minecarts.set(entry.getKey().toString(), entry.getValue().toString());
        }
        try {
            file.getParentFile().mkdirs();
            // 一時ファイルに書いてから安全に置き換える
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            yaml.save(tmp);
            try {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "data.yml の保存に失敗しました", e);
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
