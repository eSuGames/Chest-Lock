package dev.chestlock.storage;

import dev.chestlock.ChestData;
import dev.chestlock.ChestKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * data.yml による永続化。
 *
 * - 読み込み失敗（ファイル破損）は例外で通知する（保護なしでの稼働を防ぐ）
 * - 書き込みは変更の直後に非同期実行される（メインスレッドの遅延防止）
 * - 一時ファイル経由のアトミック書き込みで書きかけ破損を防ぐ
 */
public final class YamlStorage implements StorageBackend {

    private final File file;
    private final Logger logger;
    /** 最新の保存スナップショット。 */
    private volatile LoadedData pending;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ChestLock-YamlSave");
        thread.setDaemon(true);
        return thread;
    });

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
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            yaml.loadFromString(content);
        } catch (IOException | InvalidConfigurationException e) {
            // 破損ファイルを「空データ」として扱わない（上書きによる全損を防ぐ）
            throw new IllegalStateException("data.yml が壊れているため読み込めません。修復または削除が必要です: " + file.getPath(), e);
        }
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

    // ---- 行単位 API：data.yml は全体を書き直す方式のため、ここでは何もしない ----
 
    @Override
    public CompletableFuture<Void> saveChestAsync(ChestKey key, ChestData data) {
        return CompletableFuture.completedFuture(null);
    }
 
    @Override
    public CompletableFuture<Void> deleteChestAsync(ChestKey key) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> saveHopperAsync(String key, UUID owner) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> deleteHopperAsync(String key) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> saveMinecartAsync(UUID entityId, UUID owner) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void flush(LoadedData data) {
        LoadedData snapshot = data.copy();

        pending = snapshot;
        if (scheduled.compareAndSet(false, true)) {
            writer.execute(() -> {
                scheduled.set(false);

                LoadedData latest = pending;
                if (latest != null) {
                    write(latest);
                }
            });
        }
    }

    private void write(LoadedData data) {
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
                Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "data.yml の保存に失敗しました", e);
        }
    }

    /** 未完了の書き込みを待ってから終了する。 */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("data.yml の書き込みが完了する前に終了しました。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LoadedData snapshot = pending;
        if (snapshot != null) {
            write(snapshot);
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
