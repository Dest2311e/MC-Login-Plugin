package com.dsh.mclogin.session;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线玩家登录态管理器。
 *
 * <p>维护三类数据：
 * <ul>
 *   <li>{@code loggedIn}：已通过验证的玩家 UUID 集合。</li>
 *   <li>{@code joinTimes}：进服时间戳，用于"超时未登录踢出"。</li>
 *   <li>{@code frozenPositions}：进服时记录的位置，用于未登录时回退移动。</li>
 * </ul>
 *
 * <p>线程安全：全部基于 {@link ConcurrentHashMap}，可被网络线程与服务器线程同时访问。
 */
public final class SessionManager {

    /** 已登录玩家集合。 */
    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();

    /** 玩家进服时间戳（毫秒）。 */
    private final ConcurrentHashMap<UUID, Long> joinTimes = new ConcurrentHashMap<>();

    /** 冻结位置：UUID -> [x, y, z, yRot, xRot]。 */
    private final ConcurrentHashMap<UUID, double[]> frozenPositions = new ConcurrentHashMap<>();

    /** 已播报过"超时前警告"的玩家（R8：每个登录会话只警告一次，登出即清）。 */
    private final Set<UUID> timeoutWarned = ConcurrentHashMap.newKeySet();

    /**
     * 默认构造。
     */
    public SessionManager() {
        // 字段已初始化
    }

    /**
     * 标记玩家已登录。
     *
     * @param uuid 玩家 UUID
     */
    public void markLoggedIn(final UUID uuid) {
        if (uuid != null) {
            loggedIn.add(uuid);
        }
    }

    /**
     * 标记玩家未登录（登出/踢出/注销账号时使用）。
     *
     * @param uuid 玩家 UUID
     */
    public void markLoggedOut(final UUID uuid) {
        if (uuid != null) {
            loggedIn.remove(uuid);
        }
    }

    /**
     * 玩家是否已登录。
     *
     * @param uuid 玩家 UUID
     * @return true 已登录
     */
    public boolean isLoggedIn(final UUID uuid) {
        return uuid != null && loggedIn.contains(uuid);
    }

    /**
     * 记录进服时间。
     *
     * @param uuid 玩家 UUID
     */
    public void recordJoin(final UUID uuid) {
        if (uuid != null) {
            joinTimes.put(uuid, System.currentTimeMillis());
        }
    }

    /**
     * 清除玩家的进服记录。
     *
     * @param uuid 玩家 UUID
     */
    public void clearJoin(final UUID uuid) {
        if (uuid != null) {
            joinTimes.remove(uuid);
        }
    }

    /**
     * 玩家进服至今的毫秒数。
     *
     * @param uuid 玩家 UUID
     * @return 毫秒数；无记录返回 0
     */
    public long elapsedSinceJoinMillis(final UUID uuid) {
        if (uuid == null) {
            return 0L;
        }
        final Long join = joinTimes.get(uuid);
        if (join == null) {
            return 0L;
        }
        return System.currentTimeMillis() - join;
    }

    /**
     * 记录冻结位置（进服瞬间调用）。
     *
     * @param uuid 玩家 UUID
     * @param x    坐标 X
     * @param y    坐标 Y
     * @param z    坐标 Z
     * @param yRot 朝向 Yaw
     * @param xRot 朝向 Pitch
     */
    public void recordFrozenPosition(final UUID uuid,
                                     final double x,
                                     final double y,
                                     final double z,
                                     final float yRot,
                                     final float xRot) {
        if (uuid != null) {
            frozenPositions.put(uuid, new double[]{x, y, z, yRot, xRot});
        }
    }

    /**
     * 获取冻结位置；无记录返回 null。
     *
     * @param uuid 玩家 UUID
     * @return [x, y, z, yRot, xRot] 或 null
     */
    public double[] getFrozenPosition(final UUID uuid) {
        return uuid == null ? null : frozenPositions.get(uuid);
    }

    /**
     * 清除冻结位置（登录后调用）。
     *
     * @param uuid 玩家 UUID
     */
    public void clearFrozenPosition(final UUID uuid) {
        if (uuid != null) {
            frozenPositions.remove(uuid);
        }
    }

    /**
     * 标记"超时前警告已播报"。
     *
     * <p>R8：{@code LoginRestrictionListener} 在距超时还剩 30 秒时调用——
     * 只有第一次调用返回 true（播警告），其后返回 false（不再重复）。
     * 标记随登出（{@link #onPlayerLeave}）与关服清理。
     *
     * @param uuid 玩家 UUID
     * @return true 表示这是本会话第一次（应当播警告）
     */
    public boolean markTimeoutWarnedIfFirst(final UUID uuid) {
        return uuid != null && timeoutWarned.add(uuid);
    }

    /**
     * 玩家退出/掉线时的完整清理（登录态 + 进服时间 + 冻结坐标 + 超时警告旗标）。
     *
     * <p><b>注意（B3 相关）</b>：本方法会连 {@code frozenPositions} 一起清掉，
     * 而 {@code LoginRestrictionListener} 的超时踢出与移动回退都以 {@code frozen != null} 为前提。
     * 因此<b>只应在玩家真正离开服务器</b>（登出、被踢出）时调用；
     * 若玩家仍在服内只是想把他重新置为"未登录"（如 {@code /unregister}），
     * 必须改用 {@code markLoggedOut + recordJoin + recordFrozenPosition(当前坐标)} 就地重新布防，
     * 否则未登录限制会整体失效。
     *
     * @param uuid 玩家 UUID
     */
    public void onPlayerLeave(final UUID uuid) {
        if (uuid == null) {
            return;
        }
        loggedIn.remove(uuid);
        joinTimes.remove(uuid);
        frozenPositions.remove(uuid);
        timeoutWarned.remove(uuid);
    }

    /**
     * 清空所有会话（服务器停止时调用）。
     */
    public void clear() {
        loggedIn.clear();
        joinTimes.clear();
        frozenPositions.clear();
        timeoutWarned.clear();
    }

    /**
     * 当前已登录人数。
     *
     * @return 人数
     */
    public int getLoggedInCount() {
        return loggedIn.size();
    }

    /**
     * 当前有进服记录的人数（含未登录）。
     *
     * @return 人数
     */
    public int getTrackedCount() {
        return joinTimes.size();
    }
}
