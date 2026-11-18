package dev.chestlock;

import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * data.yml によるチェスト設定の永続化。
 */
public final class ChestStore {

    private final ChestLockPlugin plugin;
    private final File file;
    private final Map<ChestKey, ChestData> chests = new LinkedHashMap<>();
    /** ホッパーの設置者（ブロックキー文字列 → 設置者 UUID）。 */
    private final Map<String, UUID> hopperOwners = new LinkedHashMap<>();
    /** ホッパー付きトロッコの設置者（エンティティ UUID → 設置者 UUID）。 */
    private final Map<UUID, UUID> minecartOwners = new LinkedHashMap<>();

    public ChestStore(ChestLockPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        chests.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("chests");
        if (section == null) {
            return;
        }
        for (String keyString : section.getKeys(false)) {
            ChestKey key = ChestKey.fromKeyString(keyString);
            ConfigurationSection entry = section.getConfigurationSection(keyString);
            if (key == null || entry == null) {
                continue;
            }
            String typeName = entry.getString("type", "PRIVATE");
            ChestData.Type type;
            try {
                type = ChestData.Type.valueOf(typeName);
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
        ConfigurationSection hoppers = yaml.getConfigurationSection("hoppers");
        if (hoppers != null) {
            for (String key : hoppers.getKeys(false)) {
                UUID owner = parseUuid(hoppers.getString(key));
                if (owner != null) {
                    hopperOwners.put(key, owner);
                }
            }
        }
        ConfigurationSection minecarts = yaml.getConfigurationSection("hopper-minecarts");
        if (minecarts != null) {
            for (String key : minecarts.getKeys(false)) {
                UUID entity = parseUuid(key);
                UUID owner = parseUuid(minecarts.getString(key));
                if (entity != null && owner != null) {
                    minecartOwners.put(entity, owner);
                }
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection section = yaml.createSection("chests");
        for (Map.Entry<ChestKey, ChestData> entry : chests.entrySet()) {
            String keyString = entry.getKey().toKeyString();
            ChestData data = entry.getValue();
            section.set(keyString + ".type", data.getType().name());
            section.set(keyString + ".owner", data.getOwnerName());
            section.set(keyString + ".owner-uuid", data.getOwnerUuid() == null ? null : data.getOwnerUuid().toString());
            List<Map<String, Object>> shared = new ArrayList<>();
            for (ChestData.SharedUser user : data.getShared()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", user.name());
                row.put("uuid", user.uuid() == null ? null : user.uuid().toString());
                shared.add(row);
            }
            section.set(keyString + ".shared", shared);
        }
        ConfigurationSection hoppers = yaml.createSection("hoppers");
        for (Map.Entry<String, UUID> entry : hopperOwners.entrySet()) {
            hoppers.set(entry.getKey(), entry.getValue().toString());
        }
        ConfigurationSection minecarts = yaml.createSection("hopper-minecarts");
        for (Map.Entry<UUID, UUID> entry : minecartOwners.entrySet()) {
            minecarts.set(entry.getKey().toString(), entry.getValue().toString());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "data.yml の保存に失敗しました", e);
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

    /** 座標に直接設定されたデータを返す。 */
    public Optional<ChestData> getDirect(ChestKey key) {
        return Optional.ofNullable(chests.get(key));
    }

    public Optional<ChestData> getDirect(Block block) {
        return getDirect(ChestKey.of(block));
    }

    /**
     * そのブロックが属するチェストの設定を返す。
     * 片方の半分のみ登録されているラージチェストにも反応する。
     */
    public Optional<ChestData> get(Block block) {
        Optional<ChestData> direct = getDirect(block);
        if (direct.isPresent()) {
            return direct;
        }
        Block partner = ItemUtil.findPartnerHalf(block);
        if (partner != null) {
            return getDirect(partner);
        }
        return Optional.empty();
    }

    /** チェストを設定する。ラージチェストの場合は両半分に登録する。 */
    public void register(Block block, ChestData data) {
        chests.put(ChestKey.of(block), data.copy());
        Block partner = ItemUtil.findPartnerHalf(block);
        if (partner != null) {
            chests.put(ChestKey.of(partner), data.copy());
        }
        save();
    }

    /** 片方の半分だけに設定を追加する（増結時の引き継ぎ用）。 */
    public void set(Block block, ChestData data) {
        chests.put(ChestKey.of(block), data.copy());
        save();
    }

    /** チェストの設定を解除する。ラージチェストの場合は両半分を解除する。 */
    public void unregister(Block block) {
        chests.remove(ChestKey.of(block));
        Block partner = ItemUtil.findPartnerHalf(block);
        if (partner != null) {
            chests.remove(ChestKey.of(partner));
        }
        save();
    }

    /** 座標だけを指定した解除（コマンド用）。ラージチェストの場合は両半分を解除する。 */
    public boolean unregister(ChestKey key) {
        ChestData removed = chests.remove(key);
        Block block = key.toBlock();
        if (block != null) {
            Block partner = ItemUtil.findPartnerHalf(block);
            if (partner != null) {
                chests.remove(ChestKey.of(partner));
            }
        }
        if (removed == null) {
            return false;
        }
        save();
        return true;
    }

    public Map<ChestKey, ChestData> all() {
        return chests;
    }

    // ---- ホッパー設置者の記録 ----

    public void setHopperOwner(Block block, UUID owner) {
        hopperOwners.put(ChestKey.of(block).toKeyString(), owner);
        save();
    }

    public UUID getHopperOwner(ChestKey key) {
        return hopperOwners.get(key.toKeyString());
    }

    public UUID getHopperOwner(Block block) {
        return getHopperOwner(ChestKey.of(block));
    }

    public void removeHopper(Block block) {
        if (hopperOwners.remove(ChestKey.of(block).toKeyString()) != null) {
            save();
        }
    }

    public void setMinecartOwner(UUID entityId, UUID owner) {
        minecartOwners.put(entityId, owner);
        save();
    }

    public UUID getMinecartOwner(UUID entityId) {
        return minecartOwners.get(entityId);
    }
}
