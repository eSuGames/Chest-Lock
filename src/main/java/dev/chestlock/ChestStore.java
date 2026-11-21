package dev.chestlock;

import dev.chestlock.storage.MariaDbStorage;
import dev.chestlock.storage.StorageBackend;
import dev.chestlock.storage.YamlStorage;
import org.bukkit.block.Block;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * チェスト設定の永続化。保存先は config の storage.type で data.yml / MariaDB を切り替えられる。
 */
public final class ChestStore {

    private final ChestLockPlugin plugin;
    private final StorageBackend backend;
    private final Map<ChestKey, ChestData> chests = new LinkedHashMap<>();
    /** ホッパーの設置者（ブロックキー文字列 → 設置者 UUID）。 */
    private final Map<String, UUID> hopperOwners = new LinkedHashMap<>();
    /** ホッパー付きトロッコの設置者（エンティティ UUID → 設置者 UUID）。 */
    private final Map<UUID, UUID> minecartOwners = new LinkedHashMap<>();

    public ChestStore(ChestLockPlugin plugin) {
        this.plugin = plugin;
        this.backend = createBackend();
    }

    private StorageBackend createBackend() {
        String type = plugin.getConfig().getString("storage.type", "yaml");

        if ("mariadb".equalsIgnoreCase(type)) {
            // MariaDB が指定されている場合は接続失敗時に停止する（古い YAML へ自動切替しない）
            MariaDbStorage db = new MariaDbStorage(
                    plugin.getConfig().getString("storage.mariadb.host", "localhost"),
                    plugin.getConfig().getInt("storage.mariadb.port", 3306),
                    plugin.getConfig().getString("storage.mariadb.database", "chestlock"),
                    plugin.getConfig().getString("storage.mariadb.username", "chestlock"),
                    plugin.getConfig().getString("storage.mariadb.password", ""),
                    plugin.getConfig().getString("storage.mariadb.params", "useSSL=false&autoReconnect=true"),
                    plugin.getLogger());
            try {
                db.init();
            } catch (Exception e) {
                throw new IllegalStateException("MariaDB に接続できません。データ不整合を防ぐため ChestLock は動作を停止します。", e);
            }
            plugin.getLogger().info("MariaDB を保存先として使用します。");
            return db;
        }

        if ("yaml".equalsIgnoreCase(type)) {
            return new YamlStorage(new File(plugin.getDataFolder(), "data.yml"), plugin.getLogger());
        }

        throw new IllegalArgumentException("不正な storage.type です: " + type);
    }

    public void load() {
        chests.clear();
        hopperOwners.clear();
        minecartOwners.clear();
        StorageBackend.LoadedData data = backend.load();
        chests.putAll(data.chests());
        hopperOwners.putAll(data.hoppers());
        minecartOwners.putAll(data.minecarts());

        // YAML から MariaDB へ切り替えた場合、DB が空のときだけ data.yml の内容を移行する
        // （同期・1 トランザクションで実行し、全件成功した場合のみ完了とする）
        if (backend instanceof MariaDbStorage db && data.chests().isEmpty()) {
            YamlStorage yaml = new YamlStorage(new File(plugin.getDataFolder(), "data.yml"), plugin.getLogger());
            StorageBackend.LoadedData old = yaml.load();
            if (!old.chests().isEmpty()) {
                try {
                    db.importAll(old.chests(), old.hoppers(), old.minecarts());
                } catch (Exception e) {
                    throw new IllegalStateException("data.yml から MariaDB への移行に失敗しました。", e);
                }
                chests.putAll(old.chests());
                hopperOwners.putAll(old.hoppers());
                minecartOwners.putAll(old.minecarts());
                plugin.getLogger().info("data.yml の既存データ " + old.chests().size() + " 件を MariaDB へ移行しました。");
            }
        }
    }

    /** 全データを書き出す（YAML のみ実際に書き換え、DB は都度保存済み）。 */
    public void save() {
        backend.flush(new StorageBackend.LoadedData(chests, hopperOwners, minecartOwners));
    }

    /** 終了処理：未保存の内容を書き出してから接続を閉じる。 */
    public void close() {
        if (backend instanceof MariaDbStorage db) {
            db.close();
        } else if (backend instanceof YamlStorage yaml) {
            yaml.close();
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
        update(block, data);
    }

    /**
     * 設定を更新して保存する。ラージチェストの場合は両半分へ同じ内容を書き込み、
     * 共有設定などの不整合を防ぐ。
     */
    public void update(Block block, ChestData data) {
        ChestKey key = ChestKey.of(block);
        chests.put(key, data.copy());
        backend.saveChest(key, data);
        Block partner = ItemUtil.findPartnerHalf(block);
        if (partner != null) {
            ChestKey partnerKey = ChestKey.of(partner);
            chests.put(partnerKey, data.copy());
            backend.saveChest(partnerKey, data);
        }
        // 変更を即座に永続化する（クラッシュ時のデータ消失防止）
        save();
    }

    /** 片方の半分だけに設定を追加する（増結時の引き継ぎ用）。 */
    public void set(Block block, ChestData data) {
        ChestKey key = ChestKey.of(block);
        chests.put(key, data.copy());
        backend.saveChest(key, data);
        save();
    }

    /** チェストの設定を解除する。ラージチェストの場合は両半分を解除する。 */
    public void unregister(Block block) {
        deleteKey(ChestKey.of(block));
        Block partner = ItemUtil.findPartnerHalf(block);
        if (partner != null) {
            deleteKey(ChestKey.of(partner));
        }
    }

    /** 座標だけを指定した解除（コマンド用）。ラージチェストの場合は両半分を解除する。 */
    public boolean unregister(ChestKey key) {
        boolean removed = chests.containsKey(key);
        deleteKey(key);
        Block block = key.toBlock();
        if (block != null) {
            Block partner = ItemUtil.findPartnerHalf(block);
            if (partner != null) {
                deleteKey(ChestKey.of(partner));
                removed = true;
            }
        }
        return removed;
    }

    private void deleteKey(ChestKey key) {
        chests.remove(key);
        backend.deleteChest(key);
        save();
    }

    public Map<ChestKey, ChestData> all() {
        return chests;
    }

    // ---- ホッパー設置者の記録 ----

    public void setHopperOwner(Block block, UUID owner) {
        String key = ChestKey.of(block).toKeyString();
        hopperOwners.put(key, owner);
        backend.saveHopper(key, owner);
        save();
    }

    public UUID getHopperOwner(ChestKey key) {
        return hopperOwners.get(key.toKeyString());
    }

    public UUID getHopperOwner(Block block) {
        return getHopperOwner(ChestKey.of(block));
    }

    public void removeHopper(Block block) {
        String key = ChestKey.of(block).toKeyString();
        if (hopperOwners.remove(key) != null) {
            backend.deleteHopper(key);
            save();
        }
    }

    public void setMinecartOwner(UUID entityId, UUID owner) {
        minecartOwners.put(entityId, owner);
        backend.saveMinecart(entityId, owner);
        save();
    }

    public UUID getMinecartOwner(UUID entityId) {
        return minecartOwners.get(entityId);
    }
}
