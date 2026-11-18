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
            logger.log(Level.SEVERE, "MariaDB からの読み込みに失敗しました", e);
        }
        return new LoadedData(chests, hoppers, minecarts);
    }

    @Override
    public void saveChest(ChestKey key, ChestData data) {
        try (Connection c = connect()) {
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
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "MariaDB への保存に失敗しました", e);
        }
    }

    @Override
    public void deleteChest(ChestKey key) {
        try (Connection c = connect()) {
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
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "MariaDB からの削除に失敗しました", e);
        }
    }

    @Override
    public void saveHopper(String key, UUID owner) {
        ChestKey chestKey = ChestKey.fromKeyString(key);
        if (chestKey == null) {
            return;
        }
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
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "MariaDB への保存に失敗しました", e);
        }
    }

    @Override
    public void deleteHopper(String key) {
        ChestKey chestKey = ChestKey.fromKeyString(key);
        if (chestKey == null) {
            return;
        }
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM cl_hoppers WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            ps.setString(1, chestKey.world());
            ps.setInt(2, chestKey.x());
            ps.setInt(3, chestKey.y());
            ps.setInt(4, chestKey.z());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "MariaDB からの削除に失敗しました", e);
        }
    }

    @Override
    public void saveMinecart(UUID entityId, UUID owner) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO cl_minecarts (entity_uuid, owner_uuid) VALUES (?, ?) "
                             + "ON DUPLICATE KEY UPDATE owner_uuid = VALUES(owner_uuid)")) {
            ps.setString(1, entityId.toString());
            ps.setString(2, owner.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "MariaDB への保存に失敗しました", e);
        }
    }

    @Override
    public void flush(LoadedData data) {
        // 行単位で都度保存しているため何もしない
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
