package dev.chestlock.storage;

import dev.chestlock.ChestData;
import dev.chestlock.ChestKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * チェスト設定の永続化バックエンド（data.yml / MariaDB）。
 */
public interface StorageBackend {

    /** 読み込み結果。 */
    record LoadedData(Map<ChestKey, ChestData> chests, Map<String, UUID> hoppers, Map<UUID, UUID> minecarts) {
        LoadedData copy() {
            Map<ChestKey, ChestData> chestsCopy = new LinkedHashMap<>();

            for (Map.Entry<ChestKey, ChestData> entry : this.chests().entrySet()) {
                chestsCopy.put(entry.getKey(), entry.getValue().copy());
            }

            return new LoadedData(chestsCopy, new LinkedHashMap<>(this.hoppers()),
                    new LinkedHashMap<>(this.minecarts()));
        }
    }

    /** 全データを読み込む。 */
    LoadedData load();

    /** チェスト 1 件を保存（UPSERT）。 */
    CompletableFuture<Void> saveChestAsync(ChestKey key, ChestData data);

    /** チェスト 1 件を削除。 */
    CompletableFuture<Void> deleteChestAsync(ChestKey key);

    /** ホッパー設置者を保存（UPSERT）。 */
    CompletableFuture<Void> saveHopperAsync(String key, UUID owner);

    /** ホッパー設置者を削除。 */
    CompletableFuture<Void> deleteHopperAsync(String key);

    /** ホッパー付きトロッコの設置者を保存（UPSERT）。 */
    CompletableFuture<Void> saveMinecartAsync(UUID entityId, UUID owner);

    /** 全データを書き出す（YAML は全体再生成、DB は不要）。 */
    void flush(LoadedData data);

}
