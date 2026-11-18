package dev.chestlock.listener;

import dev.chestlock.ChestData;
import dev.chestlock.ChestLockPlugin;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ペナルティ対象者に対する 5 分間の PvP ペナルティ。
 *
 * - 所有者・共有ユーザーからのみ攻撃可能（PvP 無効ワールド・サーバーでも通る）
 * - ペナルティ対象者は誰にも攻撃できない（抵抗不可）
 * - 第三者はペナルティ対象者を攻撃できない
 *
 * PvP が無効な場合、サーバー本体はダメージイベントを発生させる前に攻撃を弾くため、
 * 攻撃が成立する瞬間だけワールドの PvP を一時的に有効化し、直後に元へ戻す。
 * 設定を永続的に変更しないため、Multiverse 等のワールド管理プラグインと競合しない。
 */
public final class PvpRetaliationListener implements Listener {

    private static final class Window {
        private final long expiresAt;
        private final Set<UUID> attackerIds = new HashSet<>();
        private final Set<String> attackerNames = new HashSet<>();

        Window(long expiresAt, ChestData data) {
            this.expiresAt = expiresAt;
            if (data.getOwnerUuid() != null) {
                attackerIds.add(data.getOwnerUuid());
            }
            for (ChestData.SharedUser user : data.getShared()) {
                if (user.uuid() != null) {
                    attackerIds.add(user.uuid());
                }
                if (user.name() != null) {
                    attackerNames.add(user.name());
                }
            }
        }

        boolean expired(long now) {
            return now > expiresAt;
        }

        boolean allows(Player player) {
            return attackerIds.contains(player.getUniqueId()) || attackerNames.contains(player.getName());
        }
    }

    private final ChestLockPlugin plugin;
    private final long windowMs;
    private final Map<UUID, Window> windows = new HashMap<>();
    /** このプラグインが一時的に PvP を有効化したワールド。 */
    private final Set<UUID> flippedWorlds = new HashSet<>();

    public PvpRetaliationListener(ChestLockPlugin plugin, long windowMs) {
        this.plugin = plugin;
        this.windowMs = windowMs;
    }

    /** PvP ペナルティを開始する。 */
    public void open(Player offender, ChestData data) {
        windows.put(offender.getUniqueId(), new Window(System.currentTimeMillis() + windowMs, data));
    }

    private Window activeWindow(UUID playerId) {
        Window window = windows.get(playerId);
        if (window == null) {
            return null;
        }
        if (window.expired(System.currentTimeMillis())) {
            windows.remove(playerId);
            return null;
        }
        return window;
    }

    /** 攻撃を通すため、この瞬間だけワールドの PvP を有効化し、次 tick に元へ戻す。 */
    private void flipPvp(World world) {
        if (world.getPVP()) {
            return;
        }
        world.setPVP(true);
        flippedWorlds.add(world.getUID());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (flippedWorlds.remove(world.getUID())) {
                world.setPVP(false);
            }
        });
    }

    /** ミリェ攻撃（近接）。 */
    @EventHandler(ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        Player attacker = event.getPlayer();
        if (!(event.getAttacked() instanceof Player victim)) {
            return;
        }
        if (activeWindow(attacker.getUniqueId()) != null) {
            // ペナルティ対象者は抵抗できない
            event.setCancelled(true);
            return;
        }
        Window victimWindow = activeWindow(victim.getUniqueId());
        if (victimWindow != null) {
            if (!victimWindow.allows(attacker)) {
                // 所有者・共有ユーザーからだけ攻撃できる
                event.setCancelled(true);
                return;
            }
            flipPvp(victim.getWorld());
        }
    }

    /** 矢・トライデント等の飛翔体。 */
    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getHitEntity() instanceof Player victim)) {
            return;
        }
        Window victimWindow = activeWindow(victim.getUniqueId());
        if (victimWindow == null) {
            return;
        }
        ProjectileSource shooter = event.getEntity().getShooter();
        if (!(shooter instanceof Player attacker)) {
            // 所有者・共有ユーザー以外が仕掛けた攻撃は通さない
            event.setCancelled(true);
            return;
        }
        if (activeWindow(attacker.getUniqueId()) != null) {
            event.setCancelled(true);
            return;
        }
        if (victimWindow.allows(attacker)) {
            flipPvp(victim.getWorld());
        } else {
            event.setCancelled(true);
        }
    }

    /** その他のダメージ（ポーション等）の最終的な取り締まり。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = playerOf(event.getDamager());
        if (attacker == null) {
            return;
        }
        if (event.getEntity() instanceof Player victim) {
            Window victimWindow = activeWindow(victim.getUniqueId());
            if (victimWindow != null && !victimWindow.allows(attacker)) {
                event.setCancelled(true);
                return;
            }
            if (activeWindow(attacker.getUniqueId()) != null) {
                // ペナルティ対象者は抵抗できない
                event.setCancelled(true);
                return;
            }
            // ペナルティ成立のために一時的に PvP を有効化している間は、
            // 無関係なプレイヤー同士の攻撃も通さない
            if (flippedWorlds.contains(victim.getWorld().getUID())) {
                event.setCancelled(true);
            }
            return;
        }
        if (activeWindow(attacker.getUniqueId()) != null) {
            // ペナルティ対象者は抵抗できない
            event.setCancelled(true);
        }
    }

    private Player playerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
