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
        /** UUID が判明している攻撃許可者。 */
        private final Set<UUID> verifiedIds = new HashSet<>();
        /** UUID 未確認（名前のみ）の共有ユーザー。 */
        private final Set<String> unverifiedNames = new HashSet<>();

        Window(long expiresAt, ChestData data) {
            this.expiresAt = expiresAt;
            if (data.getOwnerUuid() != null) {
                verifiedIds.add(data.getOwnerUuid());
            }
            for (ChestData.SharedUser user : data.getShared()) {
                if (user.uuid() != null) {
                    verifiedIds.add(user.uuid());
                } else if (user.name() != null) {
                    unverifiedNames.add(user.name());
                }
            }
        }

        boolean expired(long now) {
            return now > expiresAt;
        }

        boolean allows(Player player) {
            // UUID が判明している共有ユーザーは UUID のみで判定する（MCID 変更後の別人を防ぐ）
            return verifiedIds.contains(player.getUniqueId())
                    || unverifiedNames.contains(player.getName());
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

    /** プラグイン停止時に、未復元のワールドの PvP 設定を元へ戻す。 */
    public void restoreWorlds() {
        for (UUID uid : new HashSet<>(flippedWorlds)) {
            World world = Bukkit.getWorld(uid);
            if (world != null && world.getPVP()) {
                world.setPVP(false);
            }
            flippedWorlds.remove(uid);
        }
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
            return;
        }
        if (flippedWorlds.contains(victim.getWorld().getUID())) {
            // フリップ中に成立しかけた無関係な攻撃は通さない
            event.setCancelled(true);
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

    /**
     * プレイヤーへのダメージの最終的な取り締まり。
     *
     * フリップ中（PvP を一時有効化している間）は、許可された
     * 「ペナルティ対象者 × 所有者・共有ユーザー」のペア以外の
     * あらゆるダメージ（矢・爆発・間接攻撃を含む）を絶対に通さない。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageByEntityEvent event) {
        enforceDamage(event, false);
    }

    /** 他のプラグインが後から変更しても最終状態を保証する最終確認。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDamageFinal(EntityDamageByEntityEvent event) {
        enforceDamage(event, true);
    }

    private void enforceDamage(EntityDamageByEntityEvent event, boolean finalCheck) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = playerOf(event.getDamager(), event.getDamageSource());
        Window victimWindow = activeWindow(victim.getUniqueId());
        Window attackerWindow = attacker == null ? null : activeWindow(attacker.getUniqueId());

        if (flippedWorlds.contains(victim.getWorld().getUID())) {
            // フリップ中：許可ペア（所有者・共有ユーザー → ペナルティ対象者）の攻撃のみ通す
            boolean allowed = attacker != null
                    && attackerWindow == null
                    && victimWindow != null
                    && victimWindow.allows(attacker);
            if (allowed) {
                if (!finalCheck && flippedWorlds.remove(victim.getWorld().getUID())) {
                    // ダメージが成立したのでこの瞬間に PvP 設定を元へ戻す（影響時間を最小化）
                    victim.getWorld().setPVP(false);
                }
                return;
            }
            // それ以外のあらゆるダメージ（矢・爆発・間接攻撃を含む）は通さない
            event.setCancelled(true);
            return;
        }

        // ペナルティ対象者は誰も攻撃できない
        if (attackerWindow != null) {
            event.setCancelled(true);
            return;
        }
        // ペナルティ対象者は所有者・共有ユーザーからしか攻撃されない（モブ等の通常ダメージは対象外）
        if (victimWindow != null && attacker != null && !victimWindow.allows(attacker)) {
            event.setCancelled(true);
        }
    }

    private Player playerOf(Entity damager, org.bukkit.damage.DamageSource source) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        Entity causing = source.getCausingEntity();
        return causing instanceof Player player ? player : null;
    }
}
