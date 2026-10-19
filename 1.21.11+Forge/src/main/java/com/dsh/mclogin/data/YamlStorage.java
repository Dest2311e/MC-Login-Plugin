package com.dsh.mclogin.data;

import com.dsh.mclogin.MCLoginMod;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 账号存储：{@code accounts.yml}（YAML 文本，<b>零第三方依赖</b>）。
 *
 * <p><b>P0 修复说明</b>：最初版本使用 {@code org.yaml.snakeyaml.Yaml} 并以 {@code compileOnly} 声明。
 * 经核实，MC 1.20.1 官方 {@code version.json} 的 88 条 libraries 中<b>不含</b> {@code org.yaml:snakeyaml}，
 * Forge 1.20.1 自身的 {@code build.gradle} 同样没有（Forge 用 night-config 解析 TOML，不需要 YAML）。
 * 因此 {@code compileOnly} 会导致运行时首次触碰该类即抛 {@code NoClassDefFoundError}，模组开服即崩。
 * 现在改为<b>自带的极简 YAML writer + 缩进 parser</b>：
 * 本文件格式固定（顶层 {@code version} 标量 + {@code accounts} 两层 Map，值全为标量），
 * 手写解析完全够用，且彻底消除第三方依赖，符合"不引第三方库"的诉求。
 *
 * <p>其余特性：
 * <ul>
 *   <li><b>内存缓存</b>：所有读写走 {@link #cache}，避免频繁 IO。</li>
 *   <li><b>原子写</b>：先写 {@code accounts.yml.tmp}，再 {@code ATOMIC_MOVE / REPLACE_EXISTING} 覆盖目标文件。</li>
 *   <li><b>异步落盘</b>：仅在 {@link #markDirty()} 后由后台线程按配置间隔批量写入，不阻塞服务器主线程；
 *       {@link #saveNow()} 供关服等场景同步调用。</li>
 * </ul>
 *
 * <p><b>落盘可靠性（P0/P1 修复）</b>：
 * <ul>
 *   <li><b>代际式 dirty</b>：{@link #markDirty()} 自增代际，写盘成功后记录"已落到第 N 代"。
 *       旧实现 {@code dirty.compareAndSet(true,false)} 后再写盘，会把写盘期间到来的新改动
 *       一并吞掉（标记没了、内容也没进文件）。代际式不会丢：代际更大就再存一次，全量写幂等。</li>
 *   <li><b>{@link #saveNow()} 返回 {@code boolean}</b>，失败时保留代际，下个周期自动重试。</li>
 *   <li><b>失败保留现场</b>：写盘失败时把 tmp 重命名为 {@code accounts.yml.failed-<ts>}
 *       （里面正是最新全量数据），不再无条件删除。</li>
 *   <li><b>{@link #exportRescueCopy()}</b>：供 {@code /mclogin reload} 在覆盖内存前导出
 *       {@code accounts.yml.rescue-<ts>}，避免"改密成功但未落盘 → reload → 静默回滚旧密码"。</li>
 *   <li><b>短退避重试</b>：仅对 {@link java.nio.file.AccessDeniedException}（文件被其它句柄占用）
 *       做 {@code 3×200ms} 重试，且只在自动落盘线程里做；关服路径最多一次短重试，不长时间阻塞关服。</li>
 * </ul>
 *
 * <p>线程安全：{@code cache} 为 {@link ConcurrentHashMap}，写文件用 {@code synchronized} 串行化；
 * 序列化每个账号时对其对象加锁（[C-N1]：避免 dump 到"新 salt + 旧 hash"的中间态）。
 */
public final class YamlStorage {

    /** 账号文件名。 */
    private static final String FILE_NAME = "accounts.yml";

    /** 临时文件名（原子写用）。 */
    private static final String TMP_FILE_NAME = "accounts.yml.tmp";

    /** YAML 顶层版本号键。 */
    private static final String KEY_VERSION = "version";

    /** YAML 账号集合键。 */
    private static final String KEY_ACCOUNTS = "accounts";

    /** 当前文件格式版本。 */
    private static final int FORMAT_VERSION = 1;

    /** 一级缩进（账号 UUID 所在层）。 */
    private static final String INDENT_2 = "  ";

    /** 二级缩进（账号字段所在层）。 */
    private static final String INDENT_4 = "    ";

    /** 自动落盘线程的最大尝试次数（仅对 AccessDeniedException 重试）。 */
    private static final int AUTOSAVE_MAX_ATTEMPTS = 3;

    /** 同步/关服路径的最大尝试次数（= 1 次正常 + 最多 1 次短重试，避免长时间阻塞关服）。 */
    private static final int SYNC_MAX_ATTEMPTS = 2;

    /** 重试退避间隔（毫秒）。 */
    private static final long RETRY_BACKOFF_MILLIS = 200L;

    /** 写盘失败时保留现场的备份名前缀。 */
    private static final String FAILED_FILE_PREFIX = "accounts.yml.failed-";

    /** 未落盘改动的救援副本名前缀。 */
    private static final String RESCUE_FILE_PREFIX = "accounts.yml.rescue-";

    /**
     * 显式空库标记：YAML 内联空映射 {@code {}}（允许内部空格，如 {@code { } }）。
     *
     * <p>运维把 {@code accounts:} 写成 {@code accounts: {}} 即表示"我确认账号库为空"，
     * 模组据此判定为合法清空而非解析异常。必须是<b>同一行上的字面量</b>：
     * {@code accounts:} 单独一行（后面换行再跟内容）不算。
     */
    private static final Pattern INLINE_EMPTY_MAP = Pattern.compile("\\{\\s*\\}");

    /**
     * 判定为"解析异常"时，对磁盘原文留档的副本名前缀。
     *
     * <p>用途：解析器读不懂的文件本身就是<b>唯一一份原始数据</b>；一旦后续落盘把它覆盖，
     * 就再也拿不回来。留档后运维可以对照恢复。
     */
    private static final String UNPARSABLE_FILE_PREFIX = "accounts.yml.unparsable-";

    /** 备份文件时间戳格式（含毫秒，避免同一秒内多次失败互相覆盖）。 */
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /** 数据目录（默认 {@code <game>/config}）。 */
    private Path dataDir = null;

    /** 账号缓存：UUID -> 账号。 */
    private final ConcurrentHashMap<UUID, PlayerAccount> cache = new ConcurrentHashMap<>();

    /**
     * 改动代际：每次 {@link #markDirty()} 自增。
     *
     * <p><b>为什么不用 {@code boolean dirty}</b>：旧实现
     * {@code dirty.compareAndSet(true, false)} 先消费标记再写盘，写盘期间到来的新改动
     * 会连标记一起被吞（内存里有新值、文件里没有，且不会再自动重试）。
     * 代际式改为"写盘前取快照代际 N，成功后记录已落到第 N 代"，
     * 期间若有新改动则代际 &gt; N，下一次调度自然再写一遍（全量写盘幂等）。
     */
    private final AtomicLong generation = new AtomicLong(0L);

    /** 已确认落盘的代际（只增不减，见 {@link #markPersistedUpTo(long)}）。 */
    private final AtomicLong savedGeneration = new AtomicLong(0L);

    /** 连续保存失败次数（成功一次即清零），供 {@code /mclogin status} 暴露给运维。 */
    private final AtomicLong consecutiveSaveFailures = new AtomicLong(0L);

    /**
     * 最近一次 {@link #load()} 是否判定为"磁盘内容疑似不可解析"。
     *
     * <p>true 表示：文件里有 {@code accounts} 段，却一条账号都没解析出来，
     * 因此<b>保留了内存原状态、没有用磁盘内容覆盖</b>。由 {@code /mclogin status} 暴露给运维 ——
     * 只靠日志不够，运维不看日志，而"账号库被静默读空"正是最需要被看见的状态。
     */
    private volatile boolean lastLoadSuspect = false;

    /**
     * 最近一次救援副本导出时的代际（初始 -1 表示从未导出）。
     *
     * <p>用于避免同一代际导出两份内容相同的副本：{@code /mclogin reload} 会在调用
     * {@link #load()} 之前自行导出一次，若 {@code load()} 不加判断地再导一次，
     * 每次重载都会多出一个冗余文件。
     */
    private volatile long lastRescueGeneration = -1L;

    /** 后台落盘调度器（守护线程）。 */
    private ScheduledExecutorService scheduler = null;

    /** 落盘任务句柄。 */
    private ScheduledFuture<?> autosaveTask = null;

    /** 文件写锁。 */
    private final Object fileLock = new Object();

    /**
     * 初始化存储目录并启动自动落盘调度。
     *
     * <p>可重复调用：第二次调用时若调度器仍在运行则仅确保目录存在。
     *
     * @param directory 数据目录，例如 {@code <game>/config}
     */
    public void init(final Path directory) {
        this.dataDir = directory == null ? Path.of("config") : directory;
        try {
            Files.createDirectories(this.dataDir);
        } catch (final IOException ex) {
            MCLoginMod.LOGGER.error("[MCLogin] 无法创建数据目录 {}", this.dataDir, ex);
        }
        startScheduler();
    }

    /**
     * 启动（或重启）后台自动落盘调度。
     */
    private void startScheduler() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "mclogin-storage-autosave");
            thread.setDaemon(true);
            return thread;
        });
        final int intervalSeconds = resolveAutosaveInterval();
        autosaveTask = scheduler.scheduleWithFixedDelay(
                this::saveIfDirty, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * 解析自动落盘间隔（秒）；配置不可用时返回默认值 60。
     *
     * <p>兜底值须与 {@code LoginConfig.DEFAULT_AUTOSAVE_INTERVAL_SECONDS}（P2-8 已改为 60）
     * 保持一致：这是"模组实例/配置对象不可用"（近乎仅测试场景）时的最后防线，
     * 若仍是旧值 300，会出现"README 写默认 60、实际 5 分钟才落一次盘"的文档-行为分叉。
     *
     * @return 间隔秒数
     */
    private int resolveAutosaveInterval() {
        try {
            if (MCLoginMod.getInstance() != null && MCLoginMod.getInstance().getConfig() != null) {
                return MCLoginMod.getInstance().getConfig().getAutosaveIntervalSeconds();
            }
        } catch (final RuntimeException ex) {
            // 配置尚未就绪，使用默认值
        }
        return 60;
    }

    /**
     * 从磁盘加载账号数据到内存缓存。
     *
     * <p><b>P0 数据丢失防护（本方法的核心不变量）</b>：必须区分三种"解析出 0 条"：
     * <ol>
     *   <li>文件里<b>根本没有</b> {@code accounts} 段 → 真·空库，合法清空缓存；</li>
     *   <li>{@code accounts: {}}（YAML 内联空映射）→ <b>显式空库</b>，
     *       运维明确声明"本应为空"，合法清空缓存；</li>
     *   <li><b>有</b> {@code accounts} 段（非 {@code {}}）却一条都没解析出来 → <b>可疑</b>，
     *       一律<b>保留原缓存 + 打 WARN</b>，并由 {@link #isLastLoadSuspect()} 暴露到
     *       {@code /mclogin status}。</li>
     * </ol>
     * 判据落在"文件里有没有 accounts 段 / 有没有显式空库标记"上，而<b>不是</b>"解析出几条" ——
     * 后者会把运维合法删空账号库与解析失败混为一谈，导致想清空时清不掉。
     * 第 2 条是给运维留的<b>确定可用</b>的清空路径：写 {@code accounts: {}} 即生效。
     *
     * <p><b>为什么必须这样做（实测复现的静默删库链）</b>：只把顶层 {@code accounts:} 多缩进
     * 2 个空格、账号数据本体一字不改 → 解析器读不到 accounts 段 → 解析<b>成功</b>返回 0 条
     * （不抛异常，下面的 {@code catch (RuntimeException)} 根本不会触发）→ {@code cache.clear()}
     * 把 3 条账号清掉 → 此后<b>任何一次</b> {@code markDirty()}（注册 / 改密 / 失败计数 / 登录成功
     * 都会触发）都让 {@code saveNow()} 把空库全量写回 → 磁盘上原本完好的账号被静默删除，
     * 日志里只有一句看起来完全正常的"已加载 0 条账号记录"。
     *
     * <p>另：本方法在<b>即将用磁盘内容覆盖内存之前</b>，若存在未落盘改动会先导出救援副本，
     * 使"开服路径"与"{@code /mclogin reload} 路径"获得同等保护。
     */
    public boolean load() {
        synchronized (fileLock) {
            return loadLocked();
        }
    }

    private boolean loadLocked() {
        // 代际在读取之前取：解析期间到来的改动不会被视为"已被本次加载覆盖"。
        final long generationAtStart = generation.get();
        final Path file = getFile();
        if (!Files.exists(file)) {
            if (!cache.isEmpty() || lastLoadSuspect) {
                lastLoadSuspect = true;
                return false;
            }
            MCLoginMod.LOGGER.info("[MCLogin] 未找到账号文件 {}，将使用空账号库", file);
            return true;
        }
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            MCLoginMod.LOGGER.error("[MCLogin] 读取账号文件失败：{}", file, ex);
            lastLoadSuspect = true;
            return false;
        }

        final ParseResult parsed;
        try {
            parsed = parseAccounts(lines);
        } catch (final RuntimeException ex) {
            MCLoginMod.LOGGER.error("[MCLogin] 解析账号 YAML 失败，保留原有缓存", ex);
            lastLoadSuspect = true;
            return false;
        }

        // ---------- P0 防护点：有 accounts 段（且非显式空库标记）却 0 条 = 解析异常 ----------
        if (!parsed.hasAccountsSection() || (!parsed.explicitEmpty() && parsed.accounts().isEmpty()
                && !isLegacyEmptyStore(lines))) {
            lastLoadSuspect = true;
            final int keptInMemory = cache.size();
            final Path keptCopy = preserveUnparsableCopy(file);
            if (keptInMemory > 0) {
                MCLoginMod.LOGGER.warn(
                        "[MCLogin] 危险：{} 缺失有效 accounts 段或内容无法解析，已阻止注册与覆盖保存。", file);
                MCLoginMod.LOGGER.warn(
                        "[MCLogin] 已保留内存中原有的 {} 条账号，不会用本次结果覆盖；磁盘原文另留档为 {}。"
                                + "请用 /mclogin status 确认并手工核对备份后再改动该文件。",
                        Integer.valueOf(keptInMemory), keptCopy);
            } else {
                // 内存本来就是空的：可能是合法的空账号库，也可能是首次开服就读不懂。
                // 行为与上分支一致（保留内存态），但措辞不同，避免运维误以为丢过数据。
                MCLoginMod.LOGGER.warn(
                        "[MCLogin] 账号库 {} 加载失败，当前内存为空，已阻止注册与覆盖保存。"
                                + "请检查文件并重载。磁盘原文另留档为 {}。",
                        file, keptCopy);
            }
            // 早退：保留原缓存与原代际（故意不调用 markPersistedUpTo）。
            // 这样内存态仍被视为"比磁盘新"，后续落盘把内存里的好数据写回磁盘，而不是相反。
            return false;
        }
        if (parsed.explicitEmpty() && parsed.accounts().isEmpty()) {
            // 运维用 accounts: {} 显式声明空库 —— 这是合法清空，不是解析异常。
            // （若 {} 之后又跟着正常条目，则解析结果非空，走常规加载，不打这条日志。）
            MCLoginMod.LOGGER.info(
                    "[MCLogin] 检测到显式空库标记 accounts: {{}}，按空账号库处理（原内存中 {} 条将被清空）",
                    Integer.valueOf(cache.size()));
        }

        // 覆盖内存前：存在未落盘改动则先导出救援副本（开服路径与 reload 路径同等保护）。
        final Map<UUID, PlayerAccount> replacement = new HashMap<>();
        int loaded = 0;
        for (final Map.Entry<String, Map<String, String>> entry : parsed.accounts().entrySet()) {
            try {
                final UUID uuid = UUID.fromString(entry.getKey());
                PlayerAccount account = fromMap(uuid, entry.getValue());
                if (account.getHash().isEmpty() || account.getSalt().isEmpty()) {
                    throw new IllegalArgumentException("Missing password fields");
                }
                replacement.put(uuid, account);
                loaded++;
            } catch (final IllegalArgumentException ex) {
                MCLoginMod.LOGGER.warn("[MCLogin] 账号记录不完整或 UUID 非法，取消整次重载：{}", entry.getKey());
                lastLoadSuspect = true;
                preserveUnparsableCopy(file);
                return false;
            }
        }
        if (hasPendingChanges() && lastRescueGeneration != generation.get() && exportRescueCopy() == null) {
            return false;
        }
        cache.clear();
        cache.putAll(replacement);
        lastLoadSuspect = false;
        MCLoginMod.LOGGER.info("[MCLogin] 已加载 {} 条账号记录", loaded);

        // 内存态已被磁盘内容整体替换，代际随之对齐；否则 reload 之后
        // hasPendingChanges() 会永远为 true，把"磁盘已是权威"误报成"有待落盘改动"。
        // 注意：只有走到这里（解析并替换成功）才对齐；文件缺失/读取失败/解析失败
        // 三种早退分支保持原缓存与原代际，改动仍会被后续保存写回。
        markPersistedUpTo(generationAtStart);
        return true;
    }

    /** Accept the exact empty form written by 1.0.0, without accepting malformed child rows. */
    private static boolean isLegacyEmptyStore(List<String> lines) {
        boolean found = false;
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            if (found) return false;
            if (line.equals("accounts:")) found = true;
            else if (!line.equals("version: 1")) return false;
        }
        return found;
    }

    /**
     * 标记数据已修改（不立即写盘）：自增代际。
     *
     * <p>写盘线程据此判断"内存里是否有比已落盘更新的改动"。
     */
    public void markDirty() {
        generation.incrementAndGet();
    }

    /**
     * 是否存在尚未落盘的改动。
     *
     * @return true 表示内存态比磁盘新
     */
    public boolean hasPendingChanges() {
        return generation.get() != savedGeneration.get();
    }

    /**
     * 连续保存失败次数（成功一次即清零）。
     *
     * @return 次数
     */
    public long getConsecutiveSaveFailures() {
        return consecutiveSaveFailures.get();
    }

    /**
     * 若存在未落盘修改则写入磁盘（供自动落盘调度器调用，带短退避重试）。
     */
    public void saveIfDirty() {
        if (hasPendingChanges()) {
            saveWithRetry(AUTOSAVE_MAX_ATTEMPTS, true);
        }
    }

    /**
     * 立即（同步）写入磁盘。会执行原子替换，并在文件系统不支持时回退到普通 MOVE。
     *
     * <p>失败时<b>不</b>清除改动：代际保持不变，若自动落盘调度器仍在运行，
     * 下个周期会自然重试。
     *
     * @return true 表示账号数据已成功落盘；false 表示失败（内存态保留、待重试）
     */
    public boolean saveNow() {
        return saveWithRetry(SYNC_MAX_ATTEMPTS, false);
    }

    /**
     * 把当前内存态导出为救援副本 {@code accounts.yml.rescue-<ts>}。
     *
     * <p>用途：{@code /mclogin reload} 会以磁盘内容覆盖内存缓存。若此时存在未落盘改动，
     * 那些改动会被销毁（最坏情形：玩家改密已回"成功"但尚未落盘 → 管理员 reload →
     * 密码静默回滚为旧密码，旧密码重新可用）。重载前先导出副本，既保住
     * "以磁盘为准"的语义，又不静默丢数据。
     *
     * @return 成功时返回副本路径；失败或尚未初始化目录时返回 null
     */
    public Path exportRescueCopy() {
        if (dataDir == null) {
            return null;
        }
        final Path target = dataDir.resolve(RESCUE_FILE_PREFIX + timestamp());
        try {
            Files.createDirectories(dataDir);
            Files.writeString(target, dumpAccounts(cache), StandardCharsets.UTF_8);
            // 记下本次导出时的代际：/mclogin reload 会先自行导出再调 load()，
            // load() 据此跳过重复导出（内容完全相同，多一份只是噪音）。
            lastRescueGeneration = generation.get();
            return target;
        } catch (final IOException ex) {
            MCLoginMod.LOGGER.error("[MCLogin] 导出救援副本失败：{}", target, ex);
            return null;
        }
    }

    /**
     * 覆盖内存缓存之前，若存在未落盘改动则导出一份救援副本（同一代际只导一次）。
     *
     * <p><b>为什么放在 {@code load()} 里而不是只在 {@code /mclogin reload} 里</b>：
     * 真正销毁内存态的是 {@link #load()}，不是 reload 命令。开服路径
     * （{@code MCLoginMod#onServerAboutToStart} → {@code init + load}）走的是同一个
     * {@code load()}，把保护下沉到这里，两条路径天然获得同等保护，不会漏掉未来新增的调用方。
     *
     * <p>注意：开服时内存为空、无未落盘改动，因此<b>不会</b>产生任何副本文件 ——
     * 那时候也没有东西需要保护。
     */
    private void exportRescueCopyIfPending() {
        if (!hasPendingChanges()) {
            return;
        }
        if (lastRescueGeneration == generation.get()) {
            return;
        }
        final Path rescue = exportRescueCopy();
        if (rescue != null) {
            MCLoginMod.LOGGER.warn("[MCLogin] 覆盖内存前存在未落盘改动，已导出救援副本 {}", rescue);
        }
    }

    /**
     * 把判定为"解析异常"的磁盘原文另存一份，避免被后续落盘覆盖后无从恢复。
     *
     * <p>读不懂的文件里恰恰装着<b>唯一一份原始账号数据</b>；内存里的副本可能不完整
     * （例如首次开服就读不懂时内存为空），所以必须先把原文留档。
     *
     * @param file 原账号文件
     * @return 留档路径；失败返回 null
     */
    private Path preserveUnparsableCopy(final Path file) {
        final Path target = siblingOfFile(UNPARSABLE_FILE_PREFIX + timestamp());
        try {
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (final IOException ex) {
            MCLoginMod.LOGGER.warn("[MCLogin] 为不可解析的账号文件留档失败（忽略）：{}", file, ex);
            return null;
        }
    }

    /**
     * 最近一次 {@link #load()} 是否判定为"磁盘内容疑似不可解析"（已保留内存态）。
     *
     * <p>供 {@code /mclogin status} 暴露给运维：账号库被读空却没人察觉，是最危险的状态。
     *
     * @return true 表示上次加载可疑，内存态未被磁盘内容覆盖
     */
    public boolean isLastLoadSuspect() {
        return lastLoadSuspect;
    }

    /**
     * 关闭调度器（服务器停止时调用），关闭前强制落盘一次。
     *
     * @return true 表示最后一次落盘成功；调用方据此决定日志文案，
     *         避免"写入账号文件失败"与"账号数据已落盘"同时出现的假成功
     */
    public boolean shutdown() {
        final boolean saved = saveWithRetry(SYNC_MAX_ATTEMPTS, false);
        if (autosaveTask != null) {
            autosaveTask.cancel(false);
            autosaveTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        return saved;
    }

    // ==================== 落盘内部实现 ====================

    /**
     * 写盘并按需短退避重试。
     *
     * <p><b>只对 {@link AccessDeniedException} 重试</b>：它几乎总是"文件被另一个句柄占用"
     * （编辑器 / 杀毒 / 备份工具），等几百毫秒往往就好了；其余 IO 错误
     * （磁盘满、权限位、路径失效）重试没用，立刻失败，交由"保留代际 → 下周期重试"兜底。
     * 这也避免了对长期被占用的文件把日志噪音乘以 N。
     *
     * @param maxAttempts 最大尝试次数（至少 1）
     * @param fromAutosaveLoop 是否来自自动落盘调度线程。仅用于失败日志措辞：
     *        自动落盘失败后确有"下个周期重试"可言；关服/同步路径（{@link #saveNow()} /
     *        {@link #shutdown()}）之后调度器随即关闭，不存在下个周期，若仍写
     *        "将于下个自动落盘周期重试"会误导运维（GLM 复核发现，文案级修复）。
     * @return true 落盘成功
     */
    private boolean saveWithRetry(final int maxAttempts, final boolean fromAutosaveLoop) {
        final int attempts = Math.max(1, maxAttempts);
        IOException lastError = null;
        int tried = 0;
        for (int attempt = 0; attempt < attempts; attempt++) {
            tried++;
            lastError = writeOnce();
            if (lastError == null) {
                consecutiveSaveFailures.set(0L);
                return true;
            }
            if (!(lastError instanceof AccessDeniedException) || attempt == attempts - 1) {
                break;
            }
            try {
                Thread.sleep(RETRY_BACKOFF_MILLIS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        consecutiveSaveFailures.incrementAndGet();
        MCLoginMod.LOGGER.error("[MCLogin] 写入账号文件失败：{}（本次已尝试 {} 次；内存改动仍保留{}）",
                getFile(), Integer.valueOf(tried),
                fromAutosaveLoop
                        ? "，将于下个自动落盘周期重试"
                        : "；本路径不会自动重试，若是关服路径，改动将随进程退出丢失",
                lastError);
        return false;
    }

    /**
     * 单次写盘（tmp → 原子替换）。
     *
     * @return 成功返回 null；失败返回异常（由调用方决定是否重试与如何记日志）
     */
    private IOException writeOnce() {
        synchronized (fileLock) {
            if (lastLoadSuspect) {
                return new IOException("Account load failed; refusing to overwrite the account file");
            }
            // 快照代际必须在 dump 之前取：dump 之后发生的改动必然使 generation > snapshot，
            // 于是被判定为"尚未落盘"，绝不会被本次保存冒充成功。
            final long snapshot = generation.get();
            final Path file = getFile();
            final Path tmp = tmpFile();
            final String text;
            try {
                text = dumpAccounts(cache);
            } catch (final RuntimeException ex) {
                // 序列化异常不得逃逸：调用方（关服路径）必须总能走到调度器关闭
                return new IOException("序列化账号数据时发生未预期异常", ex);
            }
            try {
                final Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (final AtomicMoveNotSupportedException ex) {
                    // 某些文件系统（部分网络盘/旧 NTFS 场景）不支持原子移动，退化为普通覆盖
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                markPersistedUpTo(snapshot);
                return null;
            } catch (final IOException ex) {
                preserveFailedTmp(tmp);
                return ex;
            }
        }
    }

    /**
     * 记录"截至 {@code generationAtStart} 的改动已落盘"（只增不减）。
     *
     * <p>用 {@code max} 而非直接赋值：两个写者交错时（如关服线程与自动落盘线程），
     * 落后的写者不应把已确认的代际往回拨。
     *
     * @param generationAtStart 已成功落盘的代际
     */
    private void markPersistedUpTo(final long generationAtStart) {
        savedGeneration.accumulateAndGet(generationAtStart, Math::max);
    }

    /**
     * 写盘失败时保留现场：把 tmp 重命名为 {@code accounts.yml.failed-<ts>} 而不是删掉。
     *
     * <p>失败的 tmp 里正是<b>最新全量数据</b>，既是救援副本也是事故证据；
     * 旧实现无条件 {@code deleteIfExists(tmp)} 恰好毁掉了它（DSH 的 T6"无残留"之所以 PASS，
     * 只是因为测试没走失败路径）。
     *
     * @param tmp 临时文件路径
     */
    private void preserveFailedTmp(final Path tmp) {
        try {
            if (!Files.exists(tmp)) {
                return;
            }
            final Path kept = siblingOfFile(FAILED_FILE_PREFIX + timestamp());
            Files.move(tmp, kept, StandardCopyOption.REPLACE_EXISTING);
            MCLoginMod.LOGGER.error("[MCLogin] 本次写盘失败，已将最新全量数据保留为 {}", kept);
        } catch (final IOException ex) {
            // 现场保留失败不应掩盖原始写入错误，仅提示
            MCLoginMod.LOGGER.warn("[MCLogin] 保留失败现场文件时出错（忽略）：{}", tmp, ex);
        }
    }

    /**
     * 账号文件同目录下的临时文件路径。
     *
     * @return {@code <账号文件目录>/accounts.yml.tmp}
     */
    private Path tmpFile() {
        return siblingOfFile(TMP_FILE_NAME);
    }

    /**
     * 账号文件同目录下的兄弟路径。
     *
     * <p>（G8-4 附带项）：旧实现在 {@code dataDir == null} 时把 tmp 落到进程 CWD，
     * 而目标文件落在 {@code ./config}，两者不同目录 → 跨目录 move 可能退化为复制甚至失败。
     * 统一以"目标文件所在目录"为基准即可消除该不一致。
     *
     * @param fileName 文件名
     * @return 路径
     */
    private Path siblingOfFile(final String fileName) {
        final Path parent = getFile().getParent();
        return (parent == null ? Path.of(".") : parent).resolve(fileName);
    }

    /**
     * 当前时间戳字符串（文件后缀用）。
     *
     * @return 形如 {@code 20260927-171530-123}
     */
    private static String timestamp() {
        return LocalDateTime.now().format(TS_FORMAT);
    }

    /**
     * 获取账号（不存在返回 null）。
     *
     * @param uuid 玩家 UUID
     * @return 账号对象或 null
     */
    public PlayerAccount get(final UUID uuid) {
        return uuid == null ? null : cache.get(uuid);
    }

    /**
     * 获取账号，不存在则新建并放入缓存（不立即写盘）。
     *
     * @param uuid 玩家 UUID
     * @return 账号对象（一定非空）
     */
    public PlayerAccount getOrCreate(final UUID uuid) {
        return cache.computeIfAbsent(uuid, key -> {
            final PlayerAccount account = new PlayerAccount();
            account.setUuid(key.toString());
            account.setRegisteredAtMillis(System.currentTimeMillis());
            return account;
        });
    }

    /**
     * 保存（更新缓存并标记脏）。
     *
     * @param account 账号对象
     */
    public void put(final PlayerAccount account) {
        if (account == null) {
            return;
        }
        try {
            cache.put(UUID.fromString(account.getUuid()), account);
            markDirty();
        } catch (final IllegalArgumentException ex) {
            MCLoginMod.LOGGER.warn("[MCLogin] 账号 UUID 非法，未写入缓存：{}", account.getUuid());
        }
    }

    /**
     * 删除账号。
     *
     * @param uuid 玩家 UUID
     */
    public void remove(final UUID uuid) {
        if (uuid == null) {
            return;
        }
        cache.remove(uuid);
        markDirty();
    }

    /**
     * 判断账号是否存在。
     *
     * @param uuid 玩家 UUID
     * @return true 存在
     */
    public boolean has(final UUID uuid) {
        return uuid != null && cache.containsKey(uuid);
    }

    /**
     * 当前缓存中的账号数量。
     *
     * @return 数量
     */
    public int getAccountCount() {
        return cache.size();
    }

    /**
     * 账号文件路径。
     *
     * @return 路径对象
     */
    public Path getFile() {
        return (dataDir == null ? Path.of("config") : dataDir).resolve(FILE_NAME);
    }

    /**
     * 只读快照（供调试命令使用）。
     *
     * @return 不可修改的账号视图
     */
    public Map<UUID, PlayerAccount> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(cache));
    }

    /**
     * 清空缓存（测试/重载用，不写盘）。
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * 判断是否已初始化目录。
     *
     * @return true 已初始化
     */
    public boolean isInitialized() {
        return dataDir != null;
    }

    /**
     * 导出为可写流（供外部备份工具使用）。
     *
     * @param out 输出流
     * @throws IOException 写入失败
     */
    public void exportTo(final OutputStream out) throws IOException {
        out.write(dumpAccounts(cache).getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 自研 YAML 序列化 ====================

    /**
     * 将账号集合序列化为 YAML 文本。
     *
     * <p>输出形如：
     * <pre>
     * version: 1
     * accounts:
     *   'uuid':
     *     uuid: 'uuid'
     *     username: 'Steve'
     *     hash: '...'
     * </pre>
     * 所有字符串使用单引号包裹（内部单引号按 YAML 规则双写转义），数值原样输出。
     *
     * @param source 账号集合
     * @return YAML 文本
     */
    private static String dumpAccounts(final Map<UUID, PlayerAccount> source) {
        final Map<String, PlayerAccount> sorted = new TreeMap<>();
        for (final Map.Entry<UUID, PlayerAccount> entry : source.entrySet()) {
            sorted.put(entry.getKey().toString(), entry.getValue());
        }
        final StringBuilder sb = new StringBuilder(1024);
        sb.append('#').append(" MCLogin 账号库 —— 请勿手工编辑，编辑前请先备份\n");
        sb.append('#').append(" hash 为 PBKDF2WithHmacSHA256 派生结果（Base64），本文件不含任何明文密码\n");
        sb.append(KEY_VERSION).append(": ").append(FORMAT_VERSION).append('\n');
        sb.append(KEY_ACCOUNTS).append(sorted.isEmpty() ? ": {}" : ":").append('\n');
        for (final Map.Entry<String, PlayerAccount> entry : sorted.entrySet()) {
            final PlayerAccount account = entry.getValue();
            // [C-N1] 账号级加锁：主线程可能正在 applyNewPassword（改密 / 登录后收敛迭代数），
            // 那里依次 setSalt→setHash→setIterations→setKeyLength，字段非 volatile。
            // 不加锁就可能 dump 出"(新 salt, 旧 hash)"这种永远无法登录的组合，
            // 而内存里是好的 → 故障只在重启后暴露。两边都 synchronized(account) 即可互斥。
            synchronized (account) {
                sb.append(INDENT_2).append(quote(entry.getKey())).append(':').append('\n');
                appendField(sb, "uuid", quote(account.getUuid()));
                appendField(sb, "username", quote(account.getUsername()));
                appendField(sb, "hash", quote(account.getHash()));
                appendField(sb, "salt", quote(account.getSalt()));
                appendField(sb, "iterations", Integer.toString(account.getIterations()));
                appendField(sb, "keyLength", Integer.toString(account.getKeyLength()));
                appendField(sb, "failedAttempts", Integer.toString(account.getFailedAttempts()));
                appendField(sb, "lockUntilMillis", Long.toString(account.getLockUntilMillis()));
                appendField(sb, "lastFailedMillis", Long.toString(account.getLastFailedMillis()));
                // G8-9：lastIp 属于玩家个人信息，提供合规开关（storeLastIp，默认 true）。
                // 关闭时不落盘——内存值保留，IP 守卫 / 失败计数 / 锁定照常工作；
                // 文件里已有的 lastIp 行会在下一次全量保存时自然消失。
                if (isStoreLastIpEnabled()) {
                    appendField(sb, "lastIp", quote(account.getLastIp()));
                }
                appendField(sb, "registeredAtMillis", Long.toString(account.getRegisteredAtMillis()));
                appendField(sb, "lastLoginAtMillis", Long.toString(account.getLastLoginAtMillis()));
            }
        }
        return sb.toString();
    }

    /**
     * 是否把 lastIp 明文落盘（G8-9，读配置 {@code storage.storeLastIp}，默认 true）。
     *
     * <p>dumpAccounts 是静态方法且可能被救援副本 / 导出路径调用，配置不可用时按默认值（记录）处理，
     * 保证"关闭开关"是唯一的抑制途径，不会因配置加载时序意外漏写。
     *
     * @return true 落盘 lastIp
     */
    private static boolean isStoreLastIpEnabled() {
        try {
            if (MCLoginMod.getInstance() != null && MCLoginMod.getInstance().getConfig() != null) {
                return MCLoginMod.getInstance().getConfig().isStoreLastIp();
            }
        } catch (final RuntimeException ex) {
            // 配置未就绪：按默认值（记录）处理
        }
        return true;
    }

    /**
     * 追加一行 {@code "  key: value"}。
     *
     * @param sb    StringBuilder
     * @param key   字段名
     * @param value 已格式化的值（字符串需已 quote）
     */
    private static void appendField(final StringBuilder sb, final String key, final String value) {
        sb.append(INDENT_4).append(key).append(": ").append(value).append('\n');
    }

    /**
     * 按 YAML 单引号风格包裹字符串（内部 {@code '} 双写转义）。
     *
     * @param raw 原始字符串
     * @return 带引号的 YAML 标量
     */
    private static String quote(final String raw) {
        final String value = raw == null ? "" : raw;
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * 解析固定结构的 YAML 文本为 {@code UUID字符串 -> 字段Map}。
     *
     * <p>只识别三类行：
     * <ol>
     *   <li>缩进 0：顶层键（{@code version} / {@code accounts}）；</li>
     *   <li>缩进 2：账号 UUID 键，开启一个新的账号块；</li>
     *   <li>缩进 4：账号字段 {@code key: value}。</li>
     * </ol>
     * 忽略空行与 {@code #} 注释行。
     *
     * <p><b>P0 修复</b>：返回值额外携带"文件里是否出现过 {@code accounts} 段"。
     * 解析器无论能否读懂都会<b>成功返回</b>（读不懂时只是 {@code accounts} 为空），
     * 只靠"解析出几条"无法区分"文件里真的没有账号"与"文件结构被改坏一条都没读出来"，
     * 而后一种情况若被当成空库，会导致整个账号库被静默删除。
     *
     * <p>注意 {@code accounts} 段的判定<b>与缩进无关</b>：实测复现的删库场景正是
     * "把顶层 {@code accounts:} 多缩进 2 空格"，若只在缩进 0 处找就会漏判。
     *
     * @param lines 文件所有行
     * @return 解析结果（账号保持文件顺序 + 是否见过 accounts 段）
     */
    private static ParseResult parseAccounts(final List<String> lines) {
        final Map<String, Map<String, String>> accounts = new LinkedHashMap<>();
        boolean inAccounts = false;
        boolean sawAccountsSection = false;
        boolean explicitEmpty = false;
        Map<String, String> current = null;
        for (final String line : lines) {
            if (line == null) {
                continue;
            }
            final String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                continue;
            }
            final int indent = indentWidth(line);
            final int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            final String key = unquote(trimmed.substring(0, colon).trim());
            final String value = unquote(trimmed.substring(colon + 1).trim());

            if (KEY_ACCOUNTS.equals(key)) {
                // 与缩进无关：只要文件里出现过 accounts 段，就说明"这里本该有账号数据"。
                sawAccountsSection = true;
                // 显式空库标记：值必须是同一行上的 {} / { } 字面量。
                // 注意 accounts: 单独一行（值为空串）不算 —— 那正是"读不出账号"的可疑形态。
                if (INLINE_EMPTY_MAP.matcher(value).matches()) {
                    explicitEmpty = true;
                }
            }

            if (indent < 2) {
                // 顶层：只有 accounts 节点下才有账号数据
                inAccounts = KEY_ACCOUNTS.equals(key);
                current = null;
                continue;
            }
            if (!inAccounts) {
                continue;
            }
            if (indent < 4) {
                // 账号 UUID 键
                current = new LinkedHashMap<>();
                accounts.put(key, current);
                continue;
            }
            if (current != null) {
                current.put(key, value);
            }
        }
        return new ParseResult(accounts, sawAccountsSection, explicitEmpty);
    }

    /**
     * {@link #parseAccounts(List)} 的返回值。
     *
     * <p>之所以要额外带 {@code hasAccountsSection}：解析"失败"对本解析器而言是
     * <b>静默成功</b>（返回空 Map 而不抛异常），单看 {@code accounts} 无法判断
     * 那是"文件里确实没有账号"还是"文件结构被改坏、一条都没读出来"。
     */
    private static final class ParseResult {

        /** 解析出的账号：UUID 字符串 -> 字段表（保持文件顺序）。 */
        private final Map<String, Map<String, String>> accounts;

        /** 文件中是否出现过 {@code accounts} 段（与缩进无关）。 */
        private final boolean hasAccountsSection;

        /**
         * 是否出现过显式空库标记 {@code accounts: {}}。
         *
         * <p>即运维明确声明"账号库本应为空"，此时 0 条是预期结果，不得判为解析异常。
         */
        private final boolean explicitEmpty;

        /**
         * 构造。
         *
         * @param accounts           解析出的账号
         * @param hasAccountsSection 是否见过 accounts 段
         * @param explicitEmpty      是否见过 {@code accounts: {}} 显式空库标记
         */
        private ParseResult(final Map<String, Map<String, String>> accounts,
                            final boolean hasAccountsSection,
                            final boolean explicitEmpty) {
            this.accounts = accounts;
            this.hasAccountsSection = hasAccountsSection;
            this.explicitEmpty = explicitEmpty;
        }

        /**
         * 解析出的账号。
         *
         * @return 账号表
         */
        private Map<String, Map<String, String>> accounts() {
            return accounts;
        }

        /**
         * 文件中是否出现过 accounts 段。
         *
         * @return true 出现过
         */
        private boolean hasAccountsSection() {
            return hasAccountsSection;
        }

        /**
         * 是否出现过显式空库标记 {@code accounts: {}}。
         *
         * @return true 出现过
         */
        private boolean explicitEmpty() {
            return explicitEmpty;
        }
    }

    /**
     * 计算行首缩进宽度（空格按 1 计，制表符按 4 计）。
     *
     * @param line 原始行
     * @return 缩进宽度
     */
    private static int indentWidth(final String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == ' ') {
                width++;
            } else if (c == '\t') {
                width += 4;
            } else {
                break;
            }
        }
        return width;
    }

    /**
     * 去掉标量的包裹引号并还原转义。
     *
     * @param raw 原始标量
     * @return 解析后的字符串
     */
    private static String unquote(final String raw) {
        if (raw.length() >= 2) {
            final char first = raw.charAt(0);
            final char last = raw.charAt(raw.length() - 1);
            if ((first == '\'' || first == '"') && last == first) {
                final String inner = raw.substring(1, raw.length() - 1);
                return first == '\'' ? inner.replace("''", "'") : inner;
            }
        }
        return raw;
    }

    // ==================== 模型转换 ====================

    /**
     * 从字段 Map 还原账号对象（字段缺失或类型异常时使用安全默认值）。
     *
     * @param uuid 玩家 UUID
     * @param raw  字段 Map
     * @return 账号对象
     */
    private static PlayerAccount fromMap(final UUID uuid, final Map<String, String> raw) {
        final PlayerAccount account = new PlayerAccount();
        account.setUuid(uuid.toString());
        account.setUsername(raw.getOrDefault("username", ""));
        account.setHash(raw.getOrDefault("hash", ""));
        account.setSalt(raw.getOrDefault("salt", ""));
        // G1(ii) 步骤 1：数据层停止发明策略值。
        // 旧实现缺失 iterations 行时兜底 64000，等于把"策略默认"写死进数据层；
        // 现在统一为 0 = 未指定，由 PasswordHasher.verify(密码, 账号, 当前配置值) 兜底决策。
        account.setIterations(asInt(raw.get("iterations"), 0));
        account.setKeyLength(asInt(raw.get("keyLength"), 256));
        account.setFailedAttempts(asInt(raw.get("failedAttempts"), 0));
        account.setLockUntilMillis(asLong(raw.get("lockUntilMillis"), 0L));
        account.setLastFailedMillis(asLong(raw.get("lastFailedMillis"), 0L));
        account.setLastIp(raw.getOrDefault("lastIp", ""));
        // 兜底改为 0（原来是 System.currentTimeMillis()）：其余字段缺失时都兜底 0/空串，
        // 唯独这里写"当前时间"，会让一个因字段丢失而读取不完整的旧账号在下次载入后
        // 显示成"刚刚注册"——审计信息失真，且重启一次变一次。
        // 0 的语义是"未知/未记录"，与其余字段一致。
        account.setRegisteredAtMillis(asLong(raw.get("registeredAtMillis"), 0L));
        account.setLastLoginAtMillis(asLong(raw.get("lastLoginAtMillis"), 0L));
        return account;
    }

    /**
     * 安全取整数。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return 整数
     */
    private static int asInt(final String value, final int defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException ex) {
            return defaultValue;
        }
    }

    /**
     * 安全取长整数。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return 长整数
     */
    private static long asLong(final String value, final long defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (final NumberFormatException ex) {
            return defaultValue;
        }
    }
}
