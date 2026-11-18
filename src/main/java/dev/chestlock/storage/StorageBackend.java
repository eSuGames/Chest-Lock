package dev.chestlock.storage;

import dev.chestlock.ChestData;
import dev.chestlock.ChestKey;

import java.util.Map;
import java.util.UUID;

/**
 * チェスト設定の永続化バックエンド（data.yml / MariaDB）。
 */
public interface StorageBackend {

    /** 読み込み結果。 */
    record LoadedData(Map<ChestKey, ChestData> chests, Map<String, UUID> hoppers, Map<UUID, UUID> minecarts) {
    }

    /** 全データを読み込む。 */
    LoadedData load();

    /** チェスト 1 件を保存（UPSERT）。 */
    void saveChest(ChestKey key, ChestData data);

    /** チェスト 1 件を削除。 */
    void deleteChest(ChestKey key);

    /** ホッパー設置者を保存（UPSERT）。 */
    void saveHopper(String key, UUID owner);

    /** ホッパー設置者を削除。 */
    void deleteHopper(String key);

    /** ホッパー付きトロッコの設置者を保存（UPSERT）。 */
    void saveMinecart(UUID entityId, UUID owner);

    /** 全データを書き出す（YAML は全体再生成、DB は不要）。 */
    void flush(LoadedData data);
}
