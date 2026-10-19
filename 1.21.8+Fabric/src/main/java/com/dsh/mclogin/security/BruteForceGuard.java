package com.dsh.mclogin.security;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.config.LoginConfig;
import com.dsh.mclogin.data.PlayerAccount;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反暴力破解守卫。
 *
 * <p>两个维度并行统计：
 * <ol>
 *   <li><b>账号维度</b>：数据落在 {@link PlayerAccount} 的 {@code failedAttempts / lockUntilMillis / lastFailedMillis}，
 *       随账号一起持久化到 accounts.yml。</li>
 *   <li><b>IP 维度</b>：数据落在内存 {@link #ipStates}（不持久化，重启清零），
 *       可通过 {@code enableIpGuard} 关闭。</li>
 * </ol>
 *
 * <p>策略：
 * <ul>
 *   <li>失败计数在各自的衰减窗口内累加；窗口内没有新的失败则视为"冷却"，计数清零。</li>
 *   <li><b>两个维度的阈值/锁定时长/窗口互相独立</b>：账号维度默认 5 次 / 300 秒 / 10 分钟；
 *       IP 维度默认 20 次 / 300 秒 / 10 分钟（[C-N2] 缓解共享出口连坐）。</li>
 *   <li>锁定期间任何登录尝试直接拒绝，并提示剩余秒数。</li>
 *   <li>登录成功调用 {@link #reset(java.util.UUID, String)}：账号维度无条件清零；
 *       IP 维度只在"本窗口失败全部针对该账号"时定向清零（G8-2c，堵死 B2 绕过链）。</li>
 * </ul>
 */
public final class BruteForceGuard {

    /** "账号不存在"分支的标记（记为空串），表示该次失败没有指向任何已注册账号。 */
    private static final String ANONYMOUS_TARGET = "";

    /** IP 维度的状态条目。 */
    private static final class IpState {

        /** 单个 IP 在一个窗口内最多跟踪多少个"被尝试账号"（防内存无限增长）。 */
        private static final int MAX_TRACKED_UUIDS = 256;

        /** 连续失败次数。 */
        private int failedAttempts = 0;

        /** 最后一次失败时间戳（毫秒）。 */
        private long lastFailedMillis = 0L;

        /** 锁定截止时间戳（毫秒）。 */
        private long lockUntilMillis = 0L;

        /**
         * 本窗口内被尝试过的账号 UUID 集合（G8-2c 定向清理的依据）。
         *
         * <p>"账号不存在"的失败记为 {@link #ANONYMOUS_TARGET}。
         */
        private final Set<String> attackedUuids = new HashSet<>();

        /** 被尝试账号数是否已超过跟踪上限（超限后放弃定向清理，保守不放行）。 */
        private boolean attackedOverflow = false;

        /**
         * 是否处于锁定。
         *
         * @return true 锁定中
         */
        private boolean isLocked() {
            return lockUntilMillis > System.currentTimeMillis();
        }

        /**
         * 剩余锁定秒数。
         *
         * @return 秒数
         */
        private long remainingSeconds() {
            final long remain = lockUntilMillis - System.currentTimeMillis();
            return remain > 0 ? remain / 1000L : 0L;
        }

        /**
         * 窗口衰减：计数与"被尝试账号集合"必须同步清零。
         *
         * <p>只清计数不清集合，定向清理就会拿过期数据做判断
         * （例如上一窗口打过别人、本窗口只打自己，却因集合残留而拒绝清零）。
         *
         * @param now           当前时间戳
         * @param windowSeconds 衰减窗口（秒）
         */
        private void decayIfExpired(final long now, final int windowSeconds) {
            if (lastFailedMillis > 0L && now - lastFailedMillis > windowSeconds * 1000L) {
                failedAttempts = 0;
                attackedUuids.clear();
                attackedOverflow = false;
            }
        }

        /**
         * 记录一次失败。
         *
         * @param now        当前时间戳
         * @param targetKey  本次尝试针对的账号（{@link #ANONYMOUS_TARGET} 表示账号不存在）
         * @param maxFails   该维度的锁定阈值
         * @param lockSeconds 该维度的锁定时长（秒）
         */
        private void addFailure(final long now,
                                final String targetKey,
                                final int maxFails,
                                final int lockSeconds) {
            failedAttempts = failedAttempts + 1;
            lastFailedMillis = now;
            if (attackedUuids.size() < MAX_TRACKED_UUIDS) {
                attackedUuids.add(targetKey);
            } else if (!attackedUuids.contains(targetKey)) {
                // 集合已满且是新目标：只能放弃精确定向判断（宁可不清理，也不误清理）
                attackedOverflow = true;
            }
            if (failedAttempts >= maxFails) {
                lockUntilMillis = now + lockSeconds * 1000L;
            }
        }

        /**
         * 本窗口内的失败是否<b>全部</b>针对同一个账号（G8-2c 判定）。
         *
         * @param uuidKey 刚登录成功的账号 UUID 字符串
         * @return true 表示可以定向清零
         */
        private boolean allFailuresTarget(final String uuidKey) {
            return !attackedOverflow
                    && attackedUuids.size() == 1
                    && attackedUuids.contains(uuidKey);
        }

        /**
         * 清空全部失败痕迹（计数 + 锁定 + 定向集合）。
         */
        private void clearFailures() {
            failedAttempts = 0;
            lastFailedMillis = 0L;
            lockUntilMillis = 0L;
            attackedUuids.clear();
            attackedOverflow = false;
        }
    }

    /** IP -> 状态（内存态，重启清零）。 */
    private final ConcurrentHashMap<String, IpState> ipStates = new ConcurrentHashMap<>();

    /**
     * 默认构造。
     */
    public BruteForceGuard() {
        // 字段已初始化
    }

    /**
     * 记录一次失败（账号维度 + IP 维度）。
     *
     * <p><b>调用约定</b>：本方法<b>假定调用方已先做锁定判定</b>（即 {@link #lockRemainingSeconds} 返回 0 才调用）。
     * 若调用方在锁定状态下仍调用本方法，效果是把 lockUntilMillis 继续往后推（本实现允许，属保守行为）。
     *
     * @param account 账号对象（可为 null，表示账号不存在；此时只记录 IP 维度）
     * @param ip      来源 IP（可为 null 或空，表示未知）
     * @return 本次失败后账号维度剩余的"可再失败次数"（0 表示已触发锁定）
     */
    public int recordFailure(final PlayerAccount account, final String ip) {
        final LoginConfig config = getConfig();
        final int maxFails = config.getMaxFailedAttempts();
        final int lockSeconds = config.getLockSeconds();
        final int windowSeconds = config.getFailWindowSeconds();
        final long now = System.currentTimeMillis();

        int remaining = maxFails;

        if (account != null) {
            // B1 修复：锁定到期即重置。
            // 默认 lockSeconds(300) < failWindowSeconds(600)，且锁定期内调用方会直接拒绝、
            // 走不到本方法（lastFailedMillis 不更新）。若不处理，解锁后第一次失败：
            //   now - lastFailed = 300s < 600s → 不清零 → failedAttempts=6 ≥ 5 → 立刻又锁 300s，
            // 账号会长期处于"一错即锁"，必须静默 600 秒才能靠衰减清零。
            if (account.getLockUntilMillis() > 0L
                    && account.getLockUntilMillis() <= now
                    && account.getFailedAttempts() >= maxFails) {
                account.setFailedAttempts(0);
                account.setLastFailedMillis(0L);
            }
            // 衰减窗口：距上次失败超过窗口则先清零
            if (account.getLastFailedMillis() > 0L
                    && now - account.getLastFailedMillis() > windowSeconds * 1000L) {
                account.setFailedAttempts(0);
            }
            final int fails = account.getFailedAttempts() + 1;
            account.setFailedAttempts(fails);
            account.setLastFailedMillis(now);
            if (fails >= maxFails) {
                account.setLockUntilMillis(now + lockSeconds * 1000L);
                remaining = 0;
            } else {
                remaining = maxFails - fails;
            }
        }

        if (config.isEnableIpGuard() && ip != null && !ip.isBlank()) {
            // [C-N2] IP 维度改用**独立**阈值/锁定时长/衰减窗口（默认 20 次 / 300 秒 / 10 分钟）：
            // 旧实现与账号维度共用 maxFailedAttempts=5，在共享出口（NAT/宿舍/未转发真实 IP 的代理）
            // 下会变成"任意一人输错 5 次，该出口下所有玩家一起被锁 300 秒"。
            final int ipMaxFails = config.getIpMaxFailedAttempts();
            final int ipLockSeconds = config.getIpLockSeconds();
            final int ipWindowSeconds = config.getIpFailWindowSeconds();
            // G8-2c：记录本次失败针对的是哪个账号，供 reset() 做定向清理判断。
            final String targetKey = account == null ? ANONYMOUS_TARGET : account.getUuid();
            final IpState state = ipStates.computeIfAbsent(ip, key -> new IpState());
            synchronized (state) {
                state.decayIfExpired(now, ipWindowSeconds);
                state.addFailure(now, targetKey, ipMaxFails, ipLockSeconds);
            }
        }
        return remaining;
    }

    /**
     * 查询某账号当前是否被锁定。
     *
     * @param account 账号对象，可为 null
     * @return 剩余锁定秒数，0 表示未锁定
     */
    public long accountLockRemainingSeconds(final PlayerAccount account) {
        if (account == null) {
            return 0L;
        }
        return account.getRemainingLockSeconds();
    }

    /**
     * 查询某 IP 当前是否被锁定。
     *
     * @param ip IP 字符串
     * @return 剩余锁定秒数，0 表示未锁定
     */
    public long ipLockRemainingSeconds(final String ip) {
        if (!getConfig().isEnableIpGuard() || ip == null || ip.isBlank()) {
            return 0L;
        }
        final IpState state = ipStates.get(ip);
        if (state == null) {
            return 0L;
        }
        synchronized (state) {
            return state.isLocked() ? state.remainingSeconds() : 0L;
        }
    }

    /**
     * 综合判定：账号或 IP 任一被锁定即拒绝。
     *
     * @param account 账号对象
     * @param ip      来源 IP
     * @return 剩余锁定秒数，0 表示允许尝试
     */
    public long lockRemainingSeconds(final PlayerAccount account, final String ip) {
        return Math.max(accountLockRemainingSeconds(account), ipLockRemainingSeconds(ip));
    }

    /**
     * 登录成功：<b>只清零账号维度</b>的失败记录。
     *
     * <p><b>B2 修复（高危，勿改回）</b>：原实现额外执行 {@code ipStates.remove(ip)}，
     * 即"一次登录成功就清空该 IP 的全部失败历史"。攻击链：
     * 从 IP_A 对目标账号撞 4 次（未达阈值 5）→ 用自己的正确账号从 IP_A 登录成功一次 →
     * IP_A 历史清零 → 再撞 4 次 → 循环。攻击者只要掌握任意一个账号即可无限洗白，
     * 且可轮换多个目标账号各打 4 枪，{@code enableIpGuard}（默认 true）形同虚设。
     *
     * <p>现在的策略（两步）：
     * <ol>
     *   <li>账号维度：无条件清零（该账号自己的失败记录）。</li>
     *   <li>IP 维度：<b>G8-2c 定向清理</b> —— 仅当该 IP 本窗口内的失败<b>全部</b>针对
     *       刚刚登录成功的这个账号时才清空。合法用户"自己连错 4 次再登录成功"会被正确清零；
     *       而 B2 的攻击链（打目标 4 枪 → 登录自己的号洗白 → 再打 4 枪）在这里被堵死：
     *       因为 IP 的记录里含有别人的 UUID，不满足"全部针对同一账号"，计数保留。
     *       集合为空（本窗口没失败过）时无需清理，天然满足。</li>
     * </ol>
     *
     * <p>注意：定向清理<b>不</b>解决 [C-N2] 共享出口连坐（那是阈值问题），两者分开修。
     *
     * @param uuid 玩家 UUID（可为 null）
     * @param ip   来源 IP（可为 null/空，表示未知）
     */
    public void reset(final java.util.UUID uuid, final String ip) {
        final com.dsh.mclogin.data.YamlStorage storage = MCLoginMod.getInstance() == null
                ? null : MCLoginMod.getInstance().getStorage();
        if (uuid != null && storage != null) {
            final PlayerAccount account = storage.get(uuid);
            if (account != null) {
                account.resetFailures();
                storage.markDirty();
            }
        }
        if (uuid != null) {
            clearIpFailuresIfTargeted(ip, uuid.toString());
        }
    }

    /**
     * G8-2c 定向清理：仅当该 IP 本窗口内的失败全部针对 {@code uuidKey} 时才清空 IP 维度状态。
     *
     * @param ip      来源 IP
     * @param uuidKey 刚登录成功的账号 UUID 字符串
     */
    private void clearIpFailuresIfTargeted(final String ip, final String uuidKey) {
        final LoginConfig config = getConfig();
        if (!config.isEnableIpGuard() || ip == null || ip.isBlank()) {
            return;
        }
        final IpState state = ipStates.get(ip);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.allFailuresTarget(uuidKey)) {
                state.clearFailures();
            }
        }
    }

    /**
     * 清理过期的 IP 状态（供定时任务调用，避免无限增长）。
     */
    public void purgeExpired() {
        final LoginConfig config = getConfig();
        // 取两个窗口的较大值：账号维度窗口与 IP 维度窗口现在可以独立配置，
        // 用较小值会清掉"按 IP 窗口还没到期"的记录（[C-N2] 独立配置的配套）。
        final long windowMillis = Math.max(config.getFailWindowSeconds(),
                config.getIpFailWindowSeconds()) * 1000L;
        final long now = System.currentTimeMillis();
        final Iterator<Map.Entry<String, IpState>> it = ipStates.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, IpState> entry = it.next();
            final IpState state = entry.getValue();
            synchronized (state) {
                final boolean expired = !state.isLocked()
                        && (state.lastFailedMillis == 0L || now - state.lastFailedMillis > windowMillis);
                if (expired) {
                    it.remove();
                }
            }
        }
    }

    /**
     * 当前记录的 IP 状态数量（调试用）。
     *
     * @return 数量
     */
    public int getTrackedIpCount() {
        return ipStates.size();
    }

    /**
     * 清空所有 IP 状态（重载配置时调用）。
     */
    public void clearIpStates() {
        ipStates.clear();
    }

    /**
     * 获取配置（模组实例不可用时返回默认配置对象）。
     *
     * @return 配置对象（永不为 null）
     */
    private LoginConfig getConfig() {
        if (MCLoginMod.getInstance() != null && MCLoginMod.getInstance().getConfig() != null) {
            return MCLoginMod.getInstance().getConfig();
        }
        final LoginConfig fallback = new LoginConfig();
        fallback.register();
        return fallback;
    }
}
