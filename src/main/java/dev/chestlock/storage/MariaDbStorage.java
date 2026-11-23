package dev.chestlock.storage;

import dev.chestlock.ChestData;
import dev.chestlock.ChestKey;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MariaDB（MySQL 互換）による永続化。
 *
 * テーブルは自動作成されます。接続できない場合は起動時に YAML へフォールバックします。
 */
public final class MariaDbStorage implements StorageBackend {

    private final String url;
    private final String username;
    private final String password;
    private final Logger logger;
    /** 書き込みは単一スレッドで順序を保って非同期実行し、メインスレッドを止めない。 */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ChestLock-MariaDB");
        thread.setDaemon(true);
        return thread;
    });

    private interface SqlTask {
        void run() throws SQLException;
    }

    public MariaDbStorage(String host, int port, String database, String username, String password, String params, Logger logger) {
        this.url = "jdbc:mariadb://" + host + ":" + port + "/" + database + (params == null || params.isEmpty() ? "" : "?" + params);
        this.username = username;
        this.password = password;
        this.logger = logger;
        try {
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("MariaDB JDBC ドライバを読み込めませんでした", e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    /** 書き込み処理を非同期で実行する。 */
    private CompletableFuture<Void> executeAsync(String what, SqlTask task) {
        CompletableFuture<Void> result = new CompletableFuture<>();

        try {
            writer.execute(() -> {
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        task.run();

                        result.complete(null);
                        return;
                    } catch (SQLException se) {
                        if (attempt == 3) {
                            logger.log(Level.SEVERE, "MariaDB への" + what + "に失敗しました", se);

                            result.completeExceptionally(se);
                            return;
                        }

                        logger.log(Level.WARNING, "MariaDBへの" + what + "を再試行します: " + attempt, se);
                        
                        try {
                            Thread.sleep(250L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();

                            result.completeExceptionally(ie);
                            return;
                        }
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }

        return result;
    }

    /** テーブルを作成する。接続できない場合は SQLException を送出。 */
    public void init() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS cl_chests (
                        world VARCHAR(64) NOT NULL,
                        x INT NOT NULL,
                        y INT NOT NULL,
                        z INT NOT NULL,
                        type VARCHAR(16) NOT NULL,
                        owner_name VARCHAR(32),
                        owner_uuid CHAR(36),
                        PRIMARY KEY (world, x, y, z)
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS cl_shared (
                        world VARCHAR(64) NOT NULL,
                        x INT NOT NULL,
                        y INT NOT NULL,
                        z INT NOT NULL,
                        shared_name VARCHAR(32),
                        shared_uuid CHAR(36),
                        INDEX idx_cl_shared_chest (world, x, y, z)
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS cl_hoppers (
                        world VARCHAR(64) NOT NULL,
                        x INT NOT NULL,
                        y INT NOT NULL,
                        z INT NOT NULL,
                        owner_uuid CHAR(36) NOT NULL,
                        PRIMARY KEY (world, x, y, z)
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS cl_minecarts (
                        entity_uuid CHAR(36) PRIMARY KEY,
                        owner_uuid CHAR(36) NOT NULL
                    )""");
        }
    }

    @Override
    public LoadedData load() {
        Map<ChestKey, ChestData> chests = new LinkedHashMap<>();
        Map<String, UUID> hoppers = new LinkedHashMap<>();
        Map<UUID, UUID> minecarts = new LinkedHashMap<>();
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z, type, owner_name, owner_uuid FROM cl_chests");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChestKey key = new ChestKey(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                    ChestData.Type type;
                    try {
                        type = ChestData.Type.valueOf(rs.getString(5));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    chests.put(key, new ChestData(type, rs.getString(6), parseUuid(rs.getString(7))));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z, shared_name, shared_uuid FROM cl_shared");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChestData data = chests.get(new ChestKey(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
                    if (data != null) {
                        data.addShared(rs.getString(5), parseUuid(rs.getString(6)));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z, owner_uuid FROM cl_hoppers");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID owner = parseUuid(rs.getString(5));
                    if (owner != null) {
                        hoppers.put(new ChestKey(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)).toKeyString(), owner);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT entity_uuid, owner_uuid FROM cl_minecarts");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID entity = parseUuid(rs.getString(1));
                    UUID owner = parseUuid(rs.getString(2));
                    if (entity != null && owner != null) {
                        minecarts.put(entity, owner);
                    }
                }
            }
        } catch (SQLException e) {
            // 読み込み失敗を「データなし」と区別する：保護なしで稼働させないため例外で中断する
            throw new IllegalStateException("MariaDB からのデータ読み込みに失敗しました", e);
        }
        return new LoadedData(chests, hoppers, minecarts);
    }

    @Override
    public CompletableFuture<Void> saveChestAsync(ChestKey key, ChestData data) {
        ChestData snapshot = data.copy();

        return executeAsync("チェスト設定の保存", () -> saveChestSync(key, snapshot));
    }

    /** チェスト本体と共有情報を 1 つのトランザクションで保存する。 */
    private void saveChestSync(ChestKey key, ChestData data) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cl_chests (world, x, y, z, type, owner_name, owner_uuid) VALUES (?, ?, ?, ?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE type = VALUES(type), owner_name = VALUES(owner_name), owner_uuid = VALUES(owner_uuid)")) {
                    ps.setString(1, key.world());
                    ps.setInt(2, key.x());
                    ps.setInt(3, key.y());
                    ps.setInt(4, key.z());
                    ps.setString(5, data.getType().name());
                    ps.setString(6, data.getOwnerName());
                    ps.setString(7, data.getOwnerUuid() == null ? null : data.getOwnerUuid().toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM cl_shared WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                    ps.setString(1, key.world());
                    ps.setInt(2, key.x());
                    ps.setInt(3, key.y());
                    ps.setInt(4, key.z());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cl_shared (world, x, y, z, shared_name, shared_uuid) VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (ChestData.SharedUser user : data.getShared()) {
                        ps.setString(1, key.world());
                        ps.setInt(2, key.x());
                        ps.setInt(3, key.y());
                        ps.setInt(4, key.z());
                        ps.setString(5, user.name());
                        ps.setString(6, user.uuid() == null ? null : user.uuid().toString());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<Void> deleteChestAsync(ChestKey key) {
        return executeAsync("チェスト設定の削除", () -> deleteChestSync(key));
    }

    private void deleteChestSync(ChestKey key) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM cl_shared WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                    ps.setString(1, key.world());
                    ps.setInt(2, key.x());
                    ps.setInt(3, key.y());
                    ps.setInt(4, key.z());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM cl_chests WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                    ps.setString(1, key.world());
                    ps.setInt(2, key.x());
                    ps.setInt(3, key.y());
                    ps.setInt(4, key.z());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    @Override
    public CompletableFuture<Void> saveHopperAsync(String key, UUID owner) {
        ChestKey chestKey = ChestKey.fromKeyString(key);

        if (chestKey == null) {
            return CompletableFuture.completedFuture(null);
        }

        return executeAsync("保存", () -> {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO cl_hoppers (world, x, y, z, owner_uuid) VALUES (?, ?, ?, ?, ?) "
                                 + "ON DUPLICATE KEY UPDATE owner_uuid = VALUES(owner_uuid)")) {
                ps.setString(1, chestKey.world());
                ps.setInt(2, chestKey.x());
                ps.setInt(3, chestKey.y());
                ps.setInt(4, chestKey.z());
                ps.setString(5, owner.toString());
                ps.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteHopperAsync(String key) {
        ChestKey chestKey = ChestKey.fromKeyString(key);

        if (chestKey == null) {
            return CompletableFuture.completedFuture(null);
        }

        return executeAsync("削除", () -> {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM cl_hoppers WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, chestKey.world());
                ps.setInt(2, chestKey.x());
                ps.setInt(3, chestKey.y());
                ps.setInt(4, chestKey.z());
                ps.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveMinecartAsync(UUID entityId, UUID owner) {
        return executeAsync("保存", () -> {
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO cl_minecarts (entity_uuid, owner_uuid) VALUES (?, ?) "
                                 + "ON DUPLICATE KEY UPDATE owner_uuid = VALUES(owner_uuid)")) {
                ps.setString(1, entityId.toString());
                ps.setString(2, owner.toString());
                ps.executeUpdate();
            }
        });
    }

    @Override
    public void flush(LoadedData data) {
        // 行単位で都度保存しているため何もしない
    }

    /**
     * YAML からの移行を同期・1 トランザクションで実行する。
     * 全件の保存に成功した場合のみ完了となる。失敗時は SQLException を送出。
     */
    public void importAll(Map<ChestKey, ChestData> chests, Map<String, UUID> hoppers, Map<UUID, UUID> minecarts) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM cl_shared");
                    s.executeUpdate("DELETE FROM cl_chests");
                    s.executeUpdate("DELETE FROM cl_hoppers");
                    s.executeUpdate("DELETE FROM cl_minecarts");
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cl_chests (world, x, y, z, type, owner_name, owner_uuid) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (Map.Entry<ChestKey, ChestData> entry : chests.entrySet()) {
                        ChestKey key = entry.getKey();
                        ChestData data = entry.getValue();
                        ps.setString(1, key.world());
                        ps.setInt(2, key.x());
                        ps.setInt(3, key.y());
                        ps.setInt(4, key.z());
                        ps.setString(5, data.getType().name());
                        ps.setString(6, data.getOwnerName());
                        ps.setString(7, data.getOwnerUuid() == null ? null : data.getOwnerUuid().toString());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cl_shared (world, x, y, z, shared_name, shared_uuid) VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (Map.Entry<ChestKey, ChestData> entry : chests.entrySet()) {
                        ChestKey key = entry.getKey();
                        for (ChestData.SharedUser user : entry.getValue().getShared()) {
                            ps.setString(1, key.world());
                            ps.setInt(2, key.x());
                            ps.setInt(3, key.y());
                            ps.setInt(4, key.z());
                            ps.setString(5, user.name());
                            ps.setString(6, user.uuid() == null ? null : user.uuid().toString());
                            ps.addBatch();
                        }
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cl_hoppers (world, x, y, z, owner_uuid) VALUES (?, ?, ?, ?, ?)")) {
                    for (Map.Entry<String, UUID> entry : hoppers.entrySet()) {
                        ChestKey key = ChestKey.fromKeyString(entry.getKey());
                        if (key == null) {
                            continue;
                        }
                        ps.setString(1, key.world());
                        ps.setInt(2, key.x());
                        ps.setInt(3, key.y());
                        ps.setInt(4, key.z());
                        ps.setString(5, entry.getValue().toString());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cl_minecarts (entity_uuid, owner_uuid) VALUES (?, ?)")) {
                    for (Map.Entry<UUID, UUID> entry : minecarts.entrySet()) {
                        ps.setString(1, entry.getKey().toString());
                        ps.setString(2, entry.getValue().toString());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** 未完了の書き込みを待ってから終了する。 */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("MariaDB への書き込みが完了する前に終了しました。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
