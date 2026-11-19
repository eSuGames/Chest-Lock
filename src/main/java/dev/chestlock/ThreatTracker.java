package dev.chestlock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 「勝手に開けようとした / 破壊しようとした」ペナルティの段階管理。
 *
 * 試行を数えて進む：
 * 2 回目 → 本人に警告
 * 3 回目 → 所有者・共有ユーザーに警告（アラート）
 * 4 回目 → 本人に PvP 警告
 * 5 回目 → 5 分間の PvP ペナルティ開始
 *
 * - 試行のカウントは attemptWindowMs の無操作でリセットされる
 * - アラート（3 回目）後は、個人用チェストなら alertCooldownMs・公共チェストなら
 *   publicAlertCooldownMs の間、新しいシーケンスが開始されない
 */
public final class ThreatTracker {

    public enum Step {
        NONE, STEP2, STEP3, STEP4, STEP5
    }

    private final long attemptWindowMs;
    private final long alertCooldownMs;
    private final long publicAlertCooldownMs;

    private final Map<UUID, Map<ChestKey, Deque<Long>>> attempts = new HashMap<>();
    private final Map<UUID, Long> suppressedUntil = new HashMap<>();

    public ThreatTracker(long attemptWindowMs, long alertCooldownMs, long publicAlertCooldownMs) {
        this.attemptWindowMs = attemptWindowMs;
        this.alertCooldownMs = alertCooldownMs;
        this.publicAlertCooldownMs = publicAlertCooldownMs;
    }

    /** 試行を記録し、到達した段階を返す。publicChest は公共チェストでの試行かどうか。 */
    public Step record(UUID offender, ChestKey key, boolean publicChest) {
        long now = System.currentTimeMillis();
        Map<ChestKey, Deque<Long>> byChest = attempts.computeIfAbsent(offender, o -> new HashMap<>());
        Deque<Long> times = byChest.computeIfAbsent(key, k -> new ArrayDeque<>());

        // クールダウン中は新しいシーケンスを開始しない
        if (times.isEmpty()) {
            Long until = suppressedUntil.get(offender);
            if (until != null && now < until) {
                return Step.NONE;
            }
        }

        // 無操作が attemptWindowMs 続いたらカウントをリセットする
        if (!times.isEmpty() && now - times.peekLast() > attemptWindowMs) {
            times.clear();
        }
        times.addLast(now);
        int count = times.size();
        if (count >= 5) {
            times.clear();
            cleanup(offender, key, byChest, times);
            return Step.STEP5;
        }
        if (count == 4) {
            return Step.STEP4;
        }
        if (count == 3) {
            long cooldown = publicChest ? publicAlertCooldownMs : alertCooldownMs;
            suppressedUntil.put(offender, now + cooldown);
            return Step.STEP3;
        }
        if (count == 2) {
            return Step.STEP2;
        }
        return Step.NONE;
    }

    /** 空になったエントリを削除してメモリを節約する。 */
    private void cleanup(UUID offender, ChestKey key, Map<ChestKey, Deque<Long>> byChest, Deque<Long> times) {
        if (times.isEmpty()) {
            byChest.remove(key);
        }
        if (byChest.isEmpty()) {
            attempts.remove(offender);
        }
    }
}
