package com.dsh.mclogin.config;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;

/**
 * MCLogin 配置（使用 Forge 标准 ForgeConfig，生成 {@code <game>/config/mclogin-server.toml}）。
 *
 * <p>设计说明（供下游确认）：任务书允许 "YAML 或 ForgeConfig 二选一"。本项目选择 ForgeConfig，因为：
 * <ul>
 *   <li>Forge 原生支持，无需自行解析与热重载；</li>
 *   <li>账号数据（accounts.yml）仍使用 YAML，符合"存储用 YAML"的要求；</li>
 *   <li>避免两套 YAML 解析逻辑混淆。</li>
 * </ul>
 *
 * <p>若下游希望配置也走 YAML，可保留本类的字段名，将 {@code ConfigValue} 换成 YAML 映射即可，
 * 调用方（{@code getXxx()} 方法签名）无需变更。
 */
public final class LoginConfig {

    /** 规格对（左侧 Spec / 右侧配置数据）。 */
    private static final Pair<CommonSpec, ForgeConfigSpec> SPEC_PAIR =
            new ForgeConfigSpec.Builder().configure(CommonSpec::new);

    /** 内部日志器（不输出任何敏感信息）。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 全局注册标记：避免重复向 ModLoadingContext 注册同一份 spec。 */
    private static boolean registered = false;

    /** 已注册的规格（register 后非空）。 */
    private ForgeConfigSpec spec = null;

    /** 配置数据持有者。 */
    private CommonSpec data = SPEC_PAIR.getLeft();

    // ==================== 反暴力破解（账号维度） ====================
    /** 允许的最大连续失败次数。 */
    private static final int DEFAULT_MAX_FAILS = 5;

    /** 达到阈值后的锁定时长（秒）。 */
    private static final int DEFAULT_LOCK_SECONDS = 300;

    /** 失败计数的衰减窗口（秒）：窗口内无失败则清零。 */
    private static final int DEFAULT_FAIL_WINDOW_SECONDS = 600;

    // ==================== 反暴力破解（IP 维度，独立阈值：[C-N2]） ====================
    /**
     * IP 维度允许的最大连续失败次数。
     *
     * <p><b>[C-N2] 共享出口连坐</b>：IP 维度取的是 TCP 对端地址。若代理未转发真实 IP
     * （所有玩家都是代理 IP），或整栋宿舍/网吧共用一个 NAT 出口，账号维度的 5 次阈值
     * 会变成"任意一个人输错 5 次密码，该出口下所有人都被锁 300 秒"。
     * 因此 IP 维度默认放宽到 20 次；B2 修复后 IP 计数不再被成功登录清空，这条更必要。
     */
    private static final int DEFAULT_IP_MAX_FAILS = 20;

    /** IP 维度达到阈值后的锁定时长（秒）。 */
    private static final int DEFAULT_IP_LOCK_SECONDS = 300;

    /** IP 维度失败计数的衰减窗口（秒）。 */
    private static final int DEFAULT_IP_FAIL_WINDOW_SECONDS = 600;

    // ==================== 登录超时 ====================
    /** 进服后必须在多少秒内完成登录，超时踢出（0 = 不踢）。 */
    private static final int DEFAULT_LOGIN_TIMEOUT_SECONDS = 120;

    /** 未登录玩家是否允许移动。 */
    private static final boolean DEFAULT_ALLOW_MOVE = false;

    /** 未登录玩家是否允许交互（破坏/放置方块、使用物品）。 */
    private static final boolean DEFAULT_ALLOW_INTERACT = false;

    /** 未登录玩家是否允许聊天。 */
    private static final boolean DEFAULT_ALLOW_CHAT = false;

    // ==================== 密码策略 ====================
    /** 密码最小长度。 */
    private static final int DEFAULT_MIN_PASSWORD_LENGTH = 6;

    /** 密码最大长度（防止超长输入导致 PBKDF2 计算耗时）。 */
    private static final int DEFAULT_MAX_PASSWORD_LENGTH = 64;

    /**
     * PBKDF2 迭代次数。
     *
     * <p>G8-1 落地后 PBKDF2 已在专用有界线程池异步执行（主线程零阻塞、有同 UUID 冷却
     * 与过载拒绝），默认值由 64000 提到 OWASP 现行水位 <b>600000</b>（记录第十七节裁决：
     * "600k 必须与三件套同一次落地"——现在三件套已就位）。
     * 下界保持 64000：存量账号可在登录成功时按配置值自动收敛上来。
     */
    private static final int DEFAULT_PBKDF2_ITERATIONS = 600000;

    // ==================== 存储 ====================
    /**
     * 账号数据自动落盘间隔（秒）。
     *
     * <p>原默认 300s 太长：崩溃 / {@code kill -9} / 断电 / OOM-Kill 这些路径
     * {@code ServerStoppedEvent} 不触发，最长 300 秒内的改动（新注册、改密、失败计数、
     * lockUntil）会丢。缩短到 60s 把最坏丢失窗口压到 1 分钟
     * （真正的 debounce 与 shutdown hook 见 G8-4 遗留项）。
     */
    private static final int DEFAULT_AUTOSAVE_INTERVAL_SECONDS = 60;

    /**
     * 是否把玩家 lastIp 明文写入 accounts.yml（G8-9）。
     *
     * <p>lastIp 属于玩家个人信息：合规场景（或服主不想留存）可关闭本项，
     * 关闭后落盘文件中不再出现 lastIp 行（文件里已有的行会在下一次全量保存时消失）。
     * 仅影响<b>持久化</b>：内存中的 IP 维度失败计数 / 锁定 / 会话逻辑照常工作。
     */
    private static final boolean DEFAULT_STORE_LAST_IP = true;

    // ==================== 会话免登录（U1，默认关闭） ====================
    /**
     * 是否启用会话免登录（U1，<b>默认 false</b>，与 AuthMeReloaded 出厂默认一致）。
     *
     * <p>六条硬约束（DSH D5 调研 / R7 第十九节，实现见 PlayerConnectionListener）：
     * ① 仅 {@code online-mode=true} 生效（离线服强制失效并 WARN）；
     * ② 默认关闭；③ 放行<b>绝不刷新</b> lastLoginAtMillis/lastIp（防无限续期）；
     * ④ 放行同步 markLoggedIn + clearFrozenPosition（B3）；⑤ 改密/注销立即失效会话；
     * ⑥ {@code sessionTimeoutMinutes = 0} 表示关闭（不是"永不过期"，与 AuthMe 语义相反）。
     */
    private static final boolean DEFAULT_ENABLE_SESSION_AUTO_LOGIN = false;

    /**
     * 会话免登录时限（分钟）：距上次成功登录低于该值且同 IP 才自动放行。
     * 区间 0~15；0 = 关闭。默认 5（AuthMe 用 10；取更保守值是因网吧/宿舍 DHCP 回收更快）。
     */
    private static final int DEFAULT_SESSION_TIMEOUT_MINUTES = 5;

    // ==================== 语言与音效（R8） ====================
    /**
     * 服务端提示语言（R8 任务 A）：{@code zh_cn}（默认）/ {@code en_us}。
     *
     * <p>R8 关键变化：消息在<b>服务端</b>解析成字面文本再发送（{@code MessageService}）——
     * 原版客户端没有本模组的语言文件（side=SERVER 不下发 assets），
     * U2 的 {@code translatable} 会显示键名原文（真机实证）。语言文件取自 jar 内
     * {@code assets/mclogin/lang/<language>.json}，缺失键回退 en_us，再回退键名。
     */
    private static final String DEFAULT_LANGUAGE = "zh_cn";

    /** 是否播放提示音效（R8 任务 B，原版音效，无需客户端 mod）。 */
    private static final boolean DEFAULT_ENABLE_SOUNDS = true;

    /** R8 / D-UI-2：登录 UI 默认开启（可用命令的玩家可关掉）。 */
    private static final boolean DEFAULT_ENABLE_LOGIN_UI = true;

    /** 音效音量（0.0~1.0）。 */
    private static final double DEFAULT_SOUND_VOLUME = 1.0;

    /** 音效音调（0.5~2.0；锁定音效在配置音调基础上乘 0.6）。 */
    private static final double DEFAULT_SOUND_PITCH = 1.0;

    /** 是否启用 IP 维度的失败计数（与账号维度并行）。 */
    private static final boolean DEFAULT_ENABLE_IP_GUARD = true;

    /**
     * 私有构造之外无逻辑；实例由 {@link #register()} 初始化。
     * 此构造存在是为了让字段 {@code data} 有明确初值。
     */
    public LoginConfig() {
        this.spec = null;
        this.data = SPEC_PAIR.getLeft();
    }

    /**
     * 向 Forge 注册配置（SERVER 侧），生成 {@code config/mclogin-server.toml}。
     *
     * <p>本方法应在模组构造阶段调用；重复调用只会绑定已构建的 spec，不会重复注册。
     *
     * <p>注册失败（例如 FML 环境不可用）时不抛异常，读取器会回落到默认值。
     */
    public synchronized void register() {
        this.spec = SPEC_PAIR.getRight();
        this.data = SPEC_PAIR.getLeft();
        if (registered) {
            return;
        }
        try {
            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC_PAIR.getRight());
            registered = true;
        } catch (final Throwable ex) {
            // 兜底：未注册时所有读取器返回默认值，模组仍可正常工作
            LOGGER.warn("[MCLogin] ForgeConfig 注册失败，将使用默认配置", ex);
        }
    }

    /**
     * 获取底层 ForgeConfigSpec（供调试/重载使用）。
     *
     * @return 配置规格，可能为 null（未注册时）
     */
    public ForgeConfigSpec getSpec() {
        return spec;
    }

    // ==================== 读取器 ====================

    /**
     * 最大连续失败次数。
     *
     * @return 次数
     */
    public int getMaxFailedAttempts() {
        return safeInt(data.maxFailedAttempts, DEFAULT_MAX_FAILS);
    }

    /**
     * 锁定时长（秒）。
     *
     * @return 秒数
     */
    public int getLockSeconds() {
        return safeInt(data.lockSeconds, DEFAULT_LOCK_SECONDS);
    }

    /**
     * 失败计数衰减窗口（秒）。
     *
     * @return 秒数
     */
    public int getFailWindowSeconds() {
        return safeInt(data.failWindowSeconds, DEFAULT_FAIL_WINDOW_SECONDS);
    }

    /**
     * IP 维度允许的最大连续失败次数（[C-N2]，独立于账号维度）。
     *
     * @return 次数
     */
    public int getIpMaxFailedAttempts() {
        return safeInt(data.ipMaxFailedAttempts, DEFAULT_IP_MAX_FAILS);
    }

    /**
     * IP 维度锁定时长（秒）。
     *
     * @return 秒数
     */
    public int getIpLockSeconds() {
        return safeInt(data.ipLockSeconds, DEFAULT_IP_LOCK_SECONDS);
    }

    /**
     * IP 维度失败计数衰减窗口（秒）。
     *
     * @return 秒数
     */
    public int getIpFailWindowSeconds() {
        return safeInt(data.ipFailWindowSeconds, DEFAULT_IP_FAIL_WINDOW_SECONDS);
    }

    /**
     * 进服后登录超时（秒），0 表示不踢。
     *
     * @return 秒数
     */
    public int getLoginTimeoutSeconds() {
        return safeInt(data.loginTimeoutSeconds, DEFAULT_LOGIN_TIMEOUT_SECONDS);
    }

    /**
     * 未登录玩家是否允许移动。
     *
     * @return true 允许
     */
    public boolean isAllowMove() {
        return safeBool(data.allowMove, DEFAULT_ALLOW_MOVE);
    }

    /**
     * 未登录玩家是否允许交互。
     *
     * @return true 允许
     */
    public boolean isAllowInteract() {
        return safeBool(data.allowInteract, DEFAULT_ALLOW_INTERACT);
    }

    /**
     * 未登录玩家是否允许聊天。
     *
     * @return true 允许
     */
    public boolean isAllowChat() {
        return safeBool(data.allowChat, DEFAULT_ALLOW_CHAT);
    }

    /**
     * 密码最小长度。
     *
     * @return 长度
     */
    public int getMinPasswordLength() {
        return safeInt(data.minPasswordLength, DEFAULT_MIN_PASSWORD_LENGTH);
    }

    /**
     * 密码最大长度。
     *
     * @return 长度
     */
    public int getMaxPasswordLength() {
        return safeInt(data.maxPasswordLength, DEFAULT_MAX_PASSWORD_LENGTH);
    }

    /**
     * PBKDF2 迭代次数。
     *
     * @return 迭代次数
     */
    public int getPbkdf2Iterations() {
        return safeInt(data.pbkdf2Iterations, DEFAULT_PBKDF2_ITERATIONS);
    }

    /**
     * 账号自动落盘间隔（秒）。
     *
     * @return 秒数
     */
    public int getAutosaveIntervalSeconds() {
        return safeInt(data.autosaveIntervalSeconds, DEFAULT_AUTOSAVE_INTERVAL_SECONDS);
    }

    /**
     * 是否把玩家 lastIp 明文写入 accounts.yml（G8-9）。
     *
     * @return true 写入（默认）；false 不写入（仅影响持久化，IP 守卫照常工作）
     */
    public boolean isStoreLastIp() {
        return safeBool(data.storeLastIp, DEFAULT_STORE_LAST_IP);
    }

    /**
     * 是否启用会话免登录（U1，默认 false）。
     *
     * @return true 启用（还需 sessionTimeoutMinutes &gt; 0 且 online-mode=true）
     */
    public boolean isEnableSessionAutoLogin() {
        return safeBool(data.enableSessionAutoLogin, DEFAULT_ENABLE_SESSION_AUTO_LOGIN);
    }

    /**
     * 会话免登录时限（分钟），0 = 关闭。
     *
     * @return 分钟数
     */
    public int getSessionTimeoutMinutes() {
        return safeInt(data.sessionTimeoutMinutes, DEFAULT_SESSION_TIMEOUT_MINUTES);
    }

    /**
     * 服务端提示语言（R8）。
     *
     * @return 语言代码（如 zh_cn）
     */
    public String getLanguage() {
        return safeString(data.language, DEFAULT_LANGUAGE);
    }

    /**
     * 是否播放提示音效（R8）。
     *
     * @return true 播放（默认）
     */
    public boolean isEnableSounds() {
        return safeBool(data.enableSounds, DEFAULT_ENABLE_SOUNDS);
    }

    /**
     * R8 / D-UI-2：是否启用登录 UI（箱子主菜单 + 铁砧文字输入 + 未登录提示血条）。
     *
     * @return true 启用（默认）
     */
    public boolean isEnableLoginUi() {
        return safeBool(data.enableLoginUi, DEFAULT_ENABLE_LOGIN_UI);
    }

    /**
     * 音效音量（0.0~1.0）。
     *
     * @return 音量
     */
    public double getSoundVolume() {
        return safeDouble(data.soundVolume, DEFAULT_SOUND_VOLUME);
    }

    /**
     * 音效音调（0.5~2.0）。
     *
     * @return 音调
     */
    public double getSoundPitch() {
        return safeDouble(data.soundPitch, DEFAULT_SOUND_PITCH);
    }

    /**
     * 是否启用 IP 维度守卫。
     *
     * @return true 启用
     */
    public boolean isEnableIpGuard() {
        return safeBool(data.enableIpGuard, DEFAULT_ENABLE_IP_GUARD);
    }

    /**
     * 安全读取整数配置（配置未就绪时返回默认值）。
     *
     * @param value        配置值，可能为 null
     * @param defaultValue 默认值
     * @return 最终值
     */
    private static int safeInt(final net.minecraftforge.common.ForgeConfigSpec.IntValue value,
                               final int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return value.get();
        } catch (final RuntimeException ex) {
            return defaultValue;
        }
    }

    /**
     * 安全读取布尔配置（配置未就绪时返回默认值）。
     *
     * @param value        配置值，可能为 null
     * @param defaultValue 默认值
     * @return 最终值
     */
    private static boolean safeBool(final net.minecraftforge.common.ForgeConfigSpec.BooleanValue value,
                                    final boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return value.get();
        } catch (final RuntimeException ex) {
            return defaultValue;
        }
    }

    /**
     * 安全读取字符串配置（配置未就绪时返回默认值）。
     *
     * @param value        配置值，可能为 null
     * @param defaultValue 默认值
     * @return 最终值（null 时返回默认值，保证非 null）
     */
    private static String safeString(final net.minecraftforge.common.ForgeConfigSpec.ConfigValue<String> value,
                                     final String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            final String actual = value.get();
            return actual == null ? defaultValue : actual;
        } catch (final RuntimeException ex) {
            return defaultValue;
        }
    }

    /**
     * 安全读取双精度配置（配置未就绪时返回默认值）。
     *
     * @param value        配置值，可能为 null
     * @param defaultValue 默认值
     * @return 最终值
     */
    private static double safeDouble(final net.minecraftforge.common.ForgeConfigSpec.DoubleValue value,
                                     final double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return value.get();
        } catch (final RuntimeException ex) {
            return defaultValue;
        }
    }

    /**
     * 配置规格定义（内部使用）。
     */
    public static final class CommonSpec {

        /** 最大连续失败次数。 */
        private final ForgeConfigSpec.IntValue maxFailedAttempts;

        /** 锁定时长（秒）。 */
        private final ForgeConfigSpec.IntValue lockSeconds;

        /** 失败计数衰减窗口（秒）。 */
        private final ForgeConfigSpec.IntValue failWindowSeconds;

        /** IP 维度最大连续失败次数（[C-N2]）。 */
        private final ForgeConfigSpec.IntValue ipMaxFailedAttempts;

        /** IP 维度锁定时长（秒）。 */
        private final ForgeConfigSpec.IntValue ipLockSeconds;

        /** IP 维度失败计数衰减窗口（秒）。 */
        private final ForgeConfigSpec.IntValue ipFailWindowSeconds;

        /** 登录超时（秒）。 */
        private final ForgeConfigSpec.IntValue loginTimeoutSeconds;

        /** 未登录是否允许移动。 */
        private final ForgeConfigSpec.BooleanValue allowMove;

        /** 未登录是否允许交互。 */
        private final ForgeConfigSpec.BooleanValue allowInteract;

        /** 未登录是否允许聊天。 */
        private final ForgeConfigSpec.BooleanValue allowChat;

        /** 密码最小长度。 */
        private final ForgeConfigSpec.IntValue minPasswordLength;

        /** 密码最大长度。 */
        private final ForgeConfigSpec.IntValue maxPasswordLength;

        /** PBKDF2 迭代次数。 */
        private final ForgeConfigSpec.IntValue pbkdf2Iterations;

        /** 自动落盘间隔（秒）。 */
        private final ForgeConfigSpec.IntValue autosaveIntervalSeconds;

        /** 是否把 lastIp 明文落盘（G8-9）。 */
        private final ForgeConfigSpec.BooleanValue storeLastIp;

        /** 是否启用会话免登录（U1）。 */
        private final ForgeConfigSpec.BooleanValue enableSessionAutoLogin;

        /** 会话免登录时限（分钟），0 = 关闭。 */
        private final ForgeConfigSpec.IntValue sessionTimeoutMinutes;

        /** 服务端提示语言（R8）。 */
        private final ForgeConfigSpec.ConfigValue<String> language;

        /** 是否播放提示音效（R8）。 */
        private final ForgeConfigSpec.BooleanValue enableSounds;

    /** R8 / D-UI-2：登录 UI 总开关（{@code [ui] enableLoginUi}）。 */
    private final ForgeConfigSpec.BooleanValue enableLoginUi;

        /** 音效音量（0.0~1.0）。 */
        private final ForgeConfigSpec.DoubleValue soundVolume;

        /** 音效音调（0.5~2.0）。 */
        private final ForgeConfigSpec.DoubleValue soundPitch;

        /** 是否启用 IP 守卫。 */
        private final ForgeConfigSpec.BooleanValue enableIpGuard;

        /**
         * 构建配置树。
         *
         * @param builder Forge 配置构建器
         */
        private CommonSpec(final ForgeConfigSpec.Builder builder) {
            builder.push("bruteforce");
            maxFailedAttempts = builder
                    .comment("连续登录失败达到该次数后锁定账号/IP")
                    .defineInRange("maxFailedAttempts", DEFAULT_MAX_FAILS, 1, 100);
            lockSeconds = builder
                    .comment("锁定持续时长（秒）")
                    .defineInRange("lockSeconds", DEFAULT_LOCK_SECONDS, 5, 86400);
            failWindowSeconds = builder
                    .comment("失败计数衰减窗口（秒）：该窗口内无新失败则清零计数")
                    .defineInRange("failWindowSeconds", DEFAULT_FAIL_WINDOW_SECONDS, 10, 86400);
            enableIpGuard = builder
                    .comment("是否同时按 IP 维度统计失败次数",
                            "警告：代理未转发真实 IP（或宿舍/网吧共用 NAT 出口）时，",
                            "IP 维度会变成共享出口连坐（一人输错密码，全出口被锁），",
                            "请调高下方的 ipMaxFailedAttempts / ipLockSeconds，或直接关闭本项。")
                    .define("enableIpGuard", DEFAULT_ENABLE_IP_GUARD);
            ipMaxFailedAttempts = builder
                    .comment("IP 维度允许的最大连续失败次数（独立于账号维度的 maxFailedAttempts）",
                            "默认 20（比账号维度宽松），以缓解共享出口连坐。")
                    .defineInRange("ipMaxFailedAttempts", DEFAULT_IP_MAX_FAILS, 1, 1000);
            ipLockSeconds = builder
                    .comment("IP 维度锁定持续时长（秒）")
                    .defineInRange("ipLockSeconds", DEFAULT_IP_LOCK_SECONDS, 5, 86400);
            ipFailWindowSeconds = builder
                    .comment("IP 维度失败计数衰减窗口（秒）")
                    .defineInRange("ipFailWindowSeconds", DEFAULT_IP_FAIL_WINDOW_SECONDS, 10, 86400);
            builder.pop();

            builder.push("restriction");
            loginTimeoutSeconds = builder
                    .comment("进服后必须完成登录的时限（秒），0 表示不踢出")
                    .defineInRange("loginTimeoutSeconds", DEFAULT_LOGIN_TIMEOUT_SECONDS, 0, 3600);
            allowMove = builder
                    .comment("未登录玩家是否允许移动（false = 回退位置）")
                    .define("allowMove", DEFAULT_ALLOW_MOVE);
            allowInteract = builder
                    .comment("未登录玩家是否允许交互（破坏/放置/使用物品）")
                    .define("allowInteract", DEFAULT_ALLOW_INTERACT);
            allowChat = builder
                    .comment("未登录玩家是否允许聊天")
                    .define("allowChat", DEFAULT_ALLOW_CHAT);
            builder.pop();

            builder.push("password");
            minPasswordLength = builder
                    .comment("密码最小长度")
                    .defineInRange("minPasswordLength", DEFAULT_MIN_PASSWORD_LENGTH, 1, 128);
            maxPasswordLength = builder
                    .comment("密码最大长度（防超长输入拖慢 PBKDF2）")
                    .defineInRange("maxPasswordLength", DEFAULT_MAX_PASSWORD_LENGTH, 8, 256);
            pbkdf2Iterations = builder
                    .comment("PBKDF2WithHmacSHA256 迭代次数（越大越慢越安全）",
                            "下界已由 10000 抬到 64000：低于此值不如不做。",
                            "本版本所有 PBKDF2 计算都在服务器主线程同步执行，",
                            "调高到 200000 以上会显著冻结 tick（单次登录可占 100ms+），",
                            "启动时会打一条 WARN；调高前请先确认已落地异步化（G8-1）。")
                    .defineInRange("pbkdf2Iterations", DEFAULT_PBKDF2_ITERATIONS, 64000, 1000000);
            builder.pop();

        builder.push("storage");
        autosaveIntervalSeconds = builder
                .comment("账号数据自动落盘间隔（秒）",
                        "崩溃/kill -9/断电路径不会触发关服落盘，本值即最坏丢失窗口。")
                .defineInRange("autosaveIntervalSeconds", DEFAULT_AUTOSAVE_INTERVAL_SECONDS, 10, 3600);
        storeLastIp = builder
                .comment("是否把玩家 lastIp 明文写入 accounts.yml（G8-9）",
                        "false = 不落盘（文件里已有的 lastIp 行会在下次全量保存时消失）；",
                        "仅影响持久化，内存中的 IP 守卫 / 失败计数 / 锁定不受影响。")
                .define("storeLastIp", DEFAULT_STORE_LAST_IP);
        builder.pop();

        builder.push("session");
        enableSessionAutoLogin = builder
                .comment("U1 会话免登录：同 IP 且距上次成功登录低于 sessionTimeoutMinutes 时，",
                        "重新进服自动放行（无需 /login）。默认 false（与 AuthMe 出厂一致）。",
                        "⚠ 仅对 online-mode=true 生效：离线服下同 IP 者可用受害者用户名直接进服",
                        "（口令门槛被整体绕过），检测到离线模式会强制停用并打 WARN。",
                        "⚠ 网吧/校园网/代理/CGNAT 等共享出口下，同 IP 的任何人都可能免密进入",
                        "他人账号（Tab 列表就是用户名清单），请权衡后再开启。",
                        "改密/注销会立即使会话失效；放行不会延长会话时限。")
                .define("enableSessionAutoLogin", DEFAULT_ENABLE_SESSION_AUTO_LOGIN);
        sessionTimeoutMinutes = builder
                .comment("会话免登录时限（分钟），区间 0~15。0 = 关闭（不是\"永不过期\"，",
                        "与 AuthMe 的同名配置语义相反）。默认 5。")
                .defineInRange("sessionTimeoutMinutes", DEFAULT_SESSION_TIMEOUT_MINUTES, 0, 15);
        builder.pop();

        builder.push("general");
        language = builder
                .comment("服务端提示语言：zh_cn（默认）/ en_us。",
                        "R8 后消息由服务端解析为字面文本发送——原版客户端无需装本模组",
                        "也能看到对应语言（side=SERVER 不下发语言文件，translatable 会显示键名）。",
                        "语言文件取自模组 jar 内 assets/mclogin/lang/<language>.json；",
                        "缺失的键回退 en_us，再回退键名本身。改动需重启服务端生效。")
                .define("language", DEFAULT_LANGUAGE);
        builder.pop();

        builder.push("sounds");
        enableSounds = builder
                .comment("是否播放提示音效（R8，原版音效，无需客户端 mod）。",
                        "播放点：进服提示 / 注册成功 / 登录成功 / 登录失败 / 账号锁定 /",
                        "超时踢出前 30 秒警告。全部在主线程播放（异步派生线程不发声音）。")
                .define("enableSounds", DEFAULT_ENABLE_SOUNDS);
        soundVolume = builder
                .comment("音效音量（0.0~1.0）。")
                .defineInRange("soundVolume", DEFAULT_SOUND_VOLUME, 0.0, 1.0);
        soundPitch = builder
                .comment("音效音调（0.5~2.0）。锁定音效固定在此音调基础上乘 0.6（低音调）。")
                .defineInRange("soundPitch", DEFAULT_SOUND_PITCH, 0.5, 2.0);
        builder.pop();

        builder.push("ui");
        enableLoginUi = builder
                .comment("R8 / D-UI-2：是否启用登录 UI（默认 true，可关）。",
                        "启用后：未登录玩家进服会看到屏幕中央标题 + 动作栏提示 + Boss 血条持续提示，",
                        "并自动打开一个箱子主菜单（登录 / 注册 / 查看状态）。",
                        "箱子按钮点\"登录\"会打开原版铁砧界面，玩家在**改名栏**输入密码，",
                        "点铁砧的输出格即提交。原版客户端即可使用，**不需要客户端 mod**。",
                        "安全性：UI 提交与 /login /register 走**完全相同**的验证入口",
                        "（同一 PBKDF2 异步池、同一失败计数与锁定），不是旁路；",
                        "关闭界面（按 Esc）不等于通过验证 —— 未登录限制由独立层强制执行，",
                        "关掉 UI 不会绕过任何限制。",
                        "若你的玩家更习惯用命令，可关掉本项以隐藏界面。")
                .define("enableLoginUi", DEFAULT_ENABLE_LOGIN_UI);
        builder.pop();
        }
    }
}
