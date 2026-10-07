package com.dsh.mclogin.security;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PBKDF2 专用异步执行器（G8-1「异步三件套」本体）。
 *
 * <p>三件套在本类的落地方式：
 * <ol>
 *   <li><b>有界线程池</b>：核心=最大={@code max(1, min(2, cpus/2))}（守护线程），
 *       队列 {@link ArrayBlockingQueue}（容量 {@link #QUEUE_CAPACITY}），
 *       拒绝策略 {@link ThreadPoolExecutor.AbortPolicy} ——
 *       ★ <b>禁用 {@code CallerRunsPolicy}</b>：它会把任务退回提交线程（= 服务器主线程）执行，
 *       等于没做异步化。池满/队满的提交一律拒绝，由调用方给玩家"繁忙"提示。</li>
 *   <li><b>同 UUID 在途去重 + ≥500ms 冷却</b>：见 {@link #submit}。
 *       冷却防滥用（G8-10），与 B1/B2 的「锁定」（防撞库）互相独立、互不干扰。</li>
 *   <li><b>回主线程改状态</b>：本执行器只承载<b>纯派生计算</b>；调用方（LoginCommands）
 *       在工作体内自行用 {@code server.execute(...)} 把结果提交回主线程改状态。
 *       本类不感知 MinecraftServer。</li>
 * </ol>
 *
 * <p><b>同池同优先级（B4 成败点）</b>：{@code verify}（账号存在分支）与 {@code burnTime}
 * （账号不存在分支）必须走<b>同一个池、同一条队列</b>——否则两者的排队延迟出现系统性差异，
 * B4 的耗时抹平会被调度差异抵消，账号枚举侧信道以新形态回归。调用方不得为任何分支
 * 绕过本执行器直接在主线程做 PBKDF2。
 *
 * <p>线程安全：{@link #slots} 为 {@link ConcurrentHashMap}；池参数构造后不变。
 */
public final class HashExecutor {

    /** 同 UUID 两次提交之间的最小间隔（毫秒）——G8-10 的冷却，防 DoS 放大。 */
    public static final long COOLDOWN_MILLIS = 500L;

    /** 有界队列容量：池满 + 队满后新提交直接拒绝。 */
    private static final int QUEUE_CAPACITY = 64;

    /** 提交状态表的清理阈值：条目数超过该值时顺带做一次过期清理。 */
    private static final int CLEANUP_THRESHOLD = 128;

    /** 已完成条目的保留时长（毫秒），过后可安全移除。 */
    private static final long FINISHED_RETENTION_MILLIS = 30_000L;

    /** 工作线程编号。 */
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(0);

    /**
     * 提交状态表：UUID -&gt; 正数表示「在途，值为提交时刻」；
     * 负数表示「已完成，值为 -完成时刻」。
     *
     * <p>一个结构同时实现「在途去重」与「≥500ms 冷却」（G8-1 要求"去重兼作冷却"）：
     * 在途时 putIfAbsent 撞正数即拒绝；完成后写成负时间戳，冷却期内提交撞负数且
     * {@code now - 完成时刻 < 500ms} 亦拒绝。
     */
    private final ConcurrentHashMap<UUID, Long> slots = new ConcurrentHashMap<>();

    /** 有界线程池（拒绝策略 AbortPolicy）。 */
    private final ThreadPoolExecutor pool;

    /**
     * 默认构造：按 CPU 数建池。
     */
    public HashExecutor() {
        final int cpus = Runtime.getRuntime().availableProcessors();
        final int threads = Math.max(1, Math.min(2, cpus / 2));
        final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        pool = new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS, queue,
                runnable -> {
                    final Thread thread = new Thread(runnable, "mclogin-hash-" + THREAD_SEQ.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
    }

    /**
     * 提交一段 PBKDF2 派生工作（纯计算；状态变更由工作体自行 {@code server.execute} 回主线程）。
     *
     * <p>三种拒绝情形（都返回 {@code false}，<b>都不回退主线程</b>）：
     * <ol>
     *   <li>同 UUID 在途（上一次派生尚未完成）——防重入，兼防同一玩家并发两次 /login；</li>
     *   <li>同 UUID 距上次完成不足 {@link #COOLDOWN_MILLIS}——冷却，防高频连发制造调度压力；</li>
     *   <li>池满/队满（{@link RejectedExecutionException}）——过载保护。</li>
     * </ol>
     *
     * @param uuid        提交者 UUID（去重与冷却的键）
     * @param hashingWork 纯计算工作体（不得在其中直接改共享可变状态）
     * @return true = 已受理；false = 被拒绝（调用方应给"繁忙"提示，不计失败）
     */
    public boolean submit(final UUID uuid, final Runnable hashingWork) {
        if (uuid == null || hashingWork == null) {
            return false;
        }
        maybeCleanup();
        final long now = System.currentTimeMillis();
        final Long existing = slots.putIfAbsent(uuid, now);
        if (existing != null) {
            if (existing > 0L) {
                return false; // 在途：同 UUID 的上一次派生尚未完成
            }
            if (now + existing < COOLDOWN_MILLIS) {
                return false; // 冷却中（existing 为负，即 -完成时刻）
            }
            if (!slots.replace(uuid, existing, now)) {
                return false; // 与并发提交者抢槽失败，视为拒绝
            }
        }
        try {
            pool.execute(() -> {
                try {
                    hashingWork.run();
                } finally {
                    // 完成后把槽位标成"已完成"（负数 = -完成时刻），进入冷却期
                    slots.computeIfPresent(uuid,
                            (key, value) -> value > 0L ? -System.currentTimeMillis() : value);
                }
            });
            return true;
        } catch (final RejectedExecutionException ex) {
            slots.remove(uuid, now); // 释放刚占用的槽位（仅当仍是我们写入的值）
            return false;
        }
    }

    /**
     * 已完成超过保留期的条目顺手清理（防长开服缓慢累积）。
     */
    private void maybeCleanup() {
        if (slots.size() < CLEANUP_THRESHOLD) {
            return;
        }
        final long now = System.currentTimeMillis();
        final Iterator<Map.Entry<UUID, Long>> iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            final Long value = iterator.next().getValue();
            if (value < 0L && now + value > FINISHED_RETENTION_MILLIS) {
                iterator.remove();
            }
        }
    }

    /**
     * 关闭执行器（服务器停止时调用）。工作线程本就是守护线程，此处保证即时退出。
     */
    public void shutdown() {
        pool.shutdownNow();
    }

    /**
     * 池内工作线程数（启动日志用）。
     *
     * @return 工作线程数
     */
    public int getPoolSize() {
        return pool.getCorePoolSize();
    }
}
