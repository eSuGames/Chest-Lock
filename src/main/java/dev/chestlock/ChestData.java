package dev.chestlock;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 1 つのチェストに設定されたロック情報。
 */
public final class ChestData {

    public enum Type {
        /** 公共用チェスト */
        PUBLIC,
        /** 個人用チェスト */
        PRIVATE
    }

    /** 共有ユーザー 1 人分。UUID が取れない場合は null。 */
    public record SharedUser(String name, UUID uuid) {
    }

    private final Type type;
    private String ownerName;
    private UUID ownerUuid;
    private final List<SharedUser> shared = new ArrayList<>();

    public ChestData(Type type, String ownerName, UUID ownerUuid) {
        this.type = type;
        this.ownerName = ownerName;
        this.ownerUuid = ownerUuid;
    }

    public static ChestData publicChest(Player setter) {
        return new ChestData(Type.PUBLIC, setter.getName(), setter.getUniqueId());
    }

    public static ChestData privateChest(Player setter) {
        return new ChestData(Type.PRIVATE, setter.getName(), setter.getUniqueId());
    }

    /** ラージチェスト増結時の引き継ぎ用コピー。 */
    public ChestData copy() {
        ChestData c = new ChestData(type, ownerName, ownerUuid);
        c.shared.addAll(shared);
        return c;
    }

    public Type getType() {
        return type;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public List<SharedUser> getShared() {
        return shared;
    }

    public boolean isOwner(Player player) {
        return ownerUuid != null && ownerUuid.equals(player.getUniqueId());
    }

    /** 所有者または共有ユーザーなら true。 */
    public boolean canUse(Player player) {
        if (isOwner(player)) {
            return true;
        }
        for (SharedUser u : shared) {
            if (u.uuid() != null && u.uuid().equals(player.getUniqueId())) {
                return true;
            }
            if (u.name() != null && u.name().equalsIgnoreCase(player.getName())) {
                return true;
            }
        }
        return false;
    }

    public boolean isShared(String name, UUID uuid) {
        for (SharedUser u : shared) {
            if (uuid != null && uuid.equals(u.uuid())) {
                return true;
            }
            if (u.name() != null && u.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 所有者・共有ユーザーかどうか（ホッパー制限用）。
     * UUID が不明な名前のみの共有ユーザーは、プレイヤー名でも照合する。
     */
    public boolean canUse(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (uuid.equals(ownerUuid)) {
            return true;
        }
        for (SharedUser u : shared) {
            if (uuid.equals(u.uuid())) {
                return true;
            }
            if (u.uuid() == null && u.name() != null) {
                String name = Bukkit.getOfflinePlayer(uuid).getName();
                if (name != null && name.equalsIgnoreCase(u.name())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** UUID が不明な共有ユーザーに、判明した UUID を後から登録する。 */
    public void backfillSharedUuid(Player player) {
        for (int i = 0; i < shared.size(); i++) {
            SharedUser u = shared.get(i);
            if (u.uuid() == null && u.name() != null && u.name().equalsIgnoreCase(player.getName())) {
                shared.set(i, new SharedUser(u.name(), player.getUniqueId()));
            }
        }
    }

    public void addShared(String name, UUID uuid) {
        shared.add(new SharedUser(name, uuid));
    }
}
