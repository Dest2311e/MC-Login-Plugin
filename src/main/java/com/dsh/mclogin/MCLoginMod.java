package com.dsh.mclogin;

import com.dsh.mclogin.command.LoginCommands;
import com.dsh.mclogin.config.LoginConfig;
import com.dsh.mclogin.data.YamlStorage;
import com.dsh.mclogin.listener.LoginRestrictionListener;
import com.dsh.mclogin.listener.PlayerConnectionListener;
import com.dsh.mclogin.security.BruteForceGuard;
import com.dsh.mclogin.security.HashExecutor;
import com.dsh.mclogin.security.PasswordHasher;
import com.dsh.mclogin.session.SessionManager;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * MCLogin 模组主类。
 *
 * <p>职责：
 * <ul>
 *   <li>作为 {@code @Mod} 入口，注册配置与全局单例（存储 / 会话 / 加密 / 反暴力）。</li>
 *   <li>在 {@link RegisterCommandsEvent} 中注册 {@code /register /login /changepassword /unregister /mclogin} 命令。</li>
 *   <li>在 {@code ServerAboutToStartEvent} / {@code ServerStoppedEvent} 中加载与持久化账号数据。</li>
 *   <li>向 {@code MinecraftForge.EVENT_BUS} 注册游戏事件监听器（连接与未登录限制）。</li>
 * </ul>
 *
 * <p>事件总线说明：{@code RegisterCommandsEvent}、{@code ServerAboutToStartEvent}、
 * {@code ServerStoppedEvent} 在 Forge 1.20.x 中均由 {@code MinecraftForge.EVENT_BUS}（游戏总线）派发，
 * 因此本类在构造中显式注册到 {@code MinecraftForge.EVENT_BUS}。
 * 注意：NeoForge / 更高版本中 {@code RegisterCommandsEvent} 改由 mod 总线派发，
 * 届时需改为 {@code modBus.addListener(this::onRegisterCommands)}（见 README 待办）。
 */
@Mod(MCLoginMod.MOD_ID)
public final class MCLoginMod {

    /** 模组 ID，必须与 mods.toml 中的 modId 一致。 */
    public static final String MOD_ID = "mclogin";

    /** 日志器。注意：任何密码/哈希都不得写入日志。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 全局单例：本模组实例的引用（服务端启动后有效）。 */
    private static MCLoginMod instance = null;

    /** 配置（ForgeConfig）。 */
    private final LoginConfig config = new LoginConfig();

    /** 账号存储（YAML，异步落盘）。 */
    private final YamlStorage storage = new YamlStorage();

    /** 在线玩家登录态。 */
    private final SessionManager sessions = new SessionManager();

    /** 密码哈希器（PBKDF2WithHmacSHA256）。 */
    private final PasswordHasher hasher = new PasswordHasher();

    /** 反暴力破解守卫。 */
    private final BruteForceGuard guard = new BruteForceGuard();

    /**
     * PBKDF2 专用异步执行器（G8-1）。
     *
     * <p>落地本执行器后，{@code verify / burnTime / 收敛重算 / 注册与改密派生}
     * 全部离开服务器主线程，"调高迭代数"与"抗 DoS 放大"不再互斥——
     * 这是默认迭代数提到 600000（OWASP 现行水位）的前提。
     * 旧的"迭代数 > 200000 且无异步执行器则 WARN"护栏随之作废：
     * 异步恒启用，无需再告警（检查执行器是否启用的语义已无意义，直接移除）。
     */
    private final HashExecutor hashExecutor = new HashExecutor();

    private final com.dsh.mclogin.security.PasswordResetService passwordReset =
            new com.dsh.mclogin.security.PasswordResetService(this);

    /** Optional recovery integration. No provider is installed by default. */
    public com.dsh.mclogin.security.PasswordResetService getPasswordReset() {
        return passwordReset;
    }

    /**
     * 构造入口，由 FML 调用。
     */
    public MCLoginMod() {
        instance = this;
        final IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册 Forge 配置（SERVER 类型，生成 <game>/config/mclogin-server.toml）
        config.register();

        // 注册到游戏事件总线：命令注册、服务器启停
        MinecraftForge.EVENT_BUS.register(this);

        // 注册游戏监听器：进出服、未登录限制
        MinecraftForge.EVENT_BUS.register(new PlayerConnectionListener(this));
        MinecraftForge.EVENT_BUS.register(new LoginRestrictionListener(this));
        // R8 / D-UI-2：登录 UI（箱子主菜单 + 铁砧文字输入 + 未登录提示血条）。
        // 独立监听器、独立 ui/ 包，对既有认证流程零耦合（见 LoginUiListener 类注释）。
        MinecraftForge.EVENT_BUS.register(new com.dsh.mclogin.ui.LoginUiListener(this));

        MCLoginMod.LOGGER.info("[MCLogin] 模组已加载，modId={}", MOD_ID);
        // modBus 目前无需额外监听，保留变量引用以避免 unused 警告
        if (modBus == null) {
            MCLoginMod.LOGGER.warn("[MCLogin] mod 事件总线为空（理论上不会发生）");
        }
    }

    /**
     * 注册命令。
     *
     * <p>1.19+ 中 {@code net.minecraft.commands.Commands} 提供 {@code literal} 构建器。
     *
     * @param event 命令注册事件
     */
    @SubscribeEvent
    public void onRegisterCommands(final RegisterCommandsEvent event) {
        LoginCommands.register(event.getDispatcher(), this);
        MCLoginMod.LOGGER.info("[MCLogin] 命令注册完成");
    }

    /**
     * 服务器启动前：加载账号数据与配置。
     *
     * @param event 服务器即将启动事件
     */
    @SubscribeEvent
    public void onServerAboutToStart(final ServerAboutToStartEvent event) {
        // 数据目录：<game>/config（全局配置目录，不随存档变化）
        // TODO(待校验): 若希望账号库跟随存档（每世界独立），可改为
        //   event.getServer().getServerDirectory().resolve("config")（MinecraftServer#getServerDirectory）
        final java.nio.file.Path dataDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        storage.init(dataDir);
        storage.load();
        MCLoginMod.LOGGER.info("[MCLogin] 账号数据已加载，共 {} 条", storage.getAccountCount());
        // R8：按配置加载服务端提示语言（MessageService 在服务端把键解析成字面文本，
        // 原版客户端没有本模组语言文件，translatable 会显示键名原文）
        com.dsh.mclogin.message.MessageService.ensureLanguage(config.getLanguage());
        logAsyncHasherStatus(event);
        warnIfSessionEnabledOnOfflineServer(event);
    }

    /**
     * G8-1 启动日志：PBKDF2 派生已异步化，替代旧版"迭代数过高 + 同步执行"的 WARN 护栏
     * （异步恒启用，高迭代不再阻塞主线程，护栏语义"检查执行器是否启用"随之失去意义）。
     *
     * @param event 服务器即将启动事件
     */
    private void logAsyncHasherStatus(final ServerAboutToStartEvent event) {
        MCLoginMod.LOGGER.info(
                "[MCLogin] PBKDF2 异步执行器已启用（threads={}，queue=64，cooldown={}ms，拒绝策略=Abort）；"
                        + "当前配置迭代数={}",
                hashExecutor.getPoolSize(), HashExecutor.COOLDOWN_MILLIS,
                config.getPbkdf2Iterations());
    }

    /**
     * U1 硬约束①：会话免登录以 {@code online-mode=true} 为硬前提。
     * 离线服下 UUID 由玩家名派生，同 IP 攻击者用受害者用户名进服即被自动放行，
     * 口令这道"唯一有效门槛"被整体绕过——比"抢注"严重一个数量级（D5 调研结论）。
     * 因此配置打开但检测到离线模式时，打 WARN 明示"实际不会生效"；
     * 每次放行前的逐玩家复查见 {@code PlayerConnectionListener.trySessionAutoLogin}。
     *
     * @param event 服务器即将启动事件
     */
    private void warnIfSessionEnabledOnOfflineServer(final ServerAboutToStartEvent event) {
        final MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        if (config.isEnableSessionAutoLogin()
                && config.getSessionTimeoutMinutes() > 0
                && !server.usesAuthentication()) {
            MCLoginMod.LOGGER.warn("[MCLogin] 检测到 online-mode=false：会话免登录（U1）已强制失效。"
                    + "离线服下免登录等于向同 IP 者开放全部账号（口令门槛被整体绕过），"
                    + "请改用正版验证服，或保持本功能关闭。");
        }
    }

    /**
     * 服务器停止：清理会话并强制落盘。
     *
     * @param event 服务器已停止事件
     */
    @SubscribeEvent
    public void onServerStopped(final ServerStoppedEvent event) {
        passwordReset.clear();
        sessions.clear();
        guard.clearIpStates();
        // G8-1：先停异步执行器（不再受理新派生），再做最后一次落盘
        hashExecutor.shutdown();
        // P0 修复：文案必须依据落盘结果，否则关服日志里会同时出现
        // "写入账号文件失败" 与 "账号数据已落盘"，给出一次假成功。
        final boolean saved = storage.shutdown();
        if (saved) {
            MCLoginMod.LOGGER.info("[MCLogin] 服务器已停止，账号数据已落盘");
        } else {
            MCLoginMod.LOGGER.error("[MCLogin] 服务器已停止，但账号数据【未能落盘】："
                    + "内存中的改动将随进程退出而丢失。请查看上方 IO 错误，"
                    + "并检查配置目录下 accounts.yml.failed-<ts> 现场文件与 /mclogin status 输出。");
        }
    }

    /**
     * 获取全局模组实例。
     *
     * @return 模组实例
     */
    public static MCLoginMod getInstance() {
        return instance;
    }

    /**
     * 获取配置。
     *
     * @return 配置对象
     */
    public LoginConfig getConfig() {
        return config;
    }

    /**
     * 获取账号存储。
     *
     * @return 存储对象
     */
    public YamlStorage getStorage() {
        return storage;
    }

    /**
     * 获取会话管理器。
     *
     * @return 会话管理器
     */
    public SessionManager getSessions() {
        return sessions;
    }

    /**
     * 获取密码哈希器。
     *
     * @return 哈希器
     */
    public PasswordHasher getHasher() {
        return hasher;
    }

    /**
     * 获取反暴力破解守卫。
     *
     * @return 守卫对象
     */
    public BruteForceGuard getGuard() {
        return guard;
    }

    /**
     * 获取 PBKDF2 异步执行器（G8-1）。
     *
     * @return 执行器
     */
    public HashExecutor getHashExecutor() {
        return hashExecutor;
    }
}
