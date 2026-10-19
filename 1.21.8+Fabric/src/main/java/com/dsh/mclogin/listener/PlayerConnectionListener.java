package com.dsh.mclogin.listener;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.config.LoginConfig;
import com.dsh.mclogin.data.PlayerAccount;
import com.dsh.mclogin.session.SessionManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 玩家进出服监听器。
 *
 * <p>职责：
 * <ul>
 *   <li>进服：记录进服时间与冻结位置；判定是否已注册并给出提示；
 *       <b>U1 会话免登录</b>——满足「同 IP + 距上次成功登录 &lt; 时限」时自动放行；</li>
 *   <li>登出/掉线：清理会话与冻结位置；</li>
 *   <li>超时未登录踢出：由 {@link com.dsh.mclogin.listener.LoginRestrictionListener} 在
 *       {@code PlayerTickEvent} 中统一处理，避免此处重复遍历。</li>
 * </ul>
 *
 * <p><b>U1 会话免登录的六条硬约束</b>（DSH D5 调研 / 记录第十九节，实现见
 * {@link #trySessionAutoLogin}）：
 * <ol>
 *   <li>仅 {@code online-mode=true} 生效（离线服下同 IP 者可用受害者用户名直接进服，
 *       口令这道"唯一有效门槛"被整体绕过——比"抢注"严重一个数量级）；</li>
 *   <li>总开关默认 {@code false}（与 AuthMeReloaded 出厂默认一致）；</li>
 *   <li>⚠ 自动放行<b>绝不刷新</b> {@code lastLoginAtMillis / lastIp}——
 *       {@code markLoginSuccess} 会同时写这两个字段，走它等于可无限续期，时限形同虚设；</li>
 *   <li>放行同步 {@code markLoggedIn + clearFrozenPosition}（B3 教训：登录态与 frozen 必须同步）；</li>
 *   <li>改密/注销使会话立即失效（命令侧已实现：改密置 {@code lastLoginAtMillis=0}，
 *       注销删除账号行——无账号即无免登录依据）；</li>
 *   <li>默认时限 5 分钟（区间 0~15）；{@code 0 = 关闭}，不是"永不过期"
 *       （与 AuthMe 同名配置语义相反）。</li>
 * </ol>
 *
 * <p>注册方式：由 {@link MCLoginMod} 构造中 {@code MinecraftForge.EVENT_BUS.register(...)} 显式注册。
 */
public final class PlayerConnectionListener {

    /** {@code getIpAddress()} 对非 INET 连接返回的字面量（DSH 字节码反汇编结论）：
     *  所有这类玩家共用同一个"IP"，绝不能作为会话免登录的依据。 */
    private static final String UNKNOWN_IP = "<unknown>";

    /** 模组实例。 */
    private final MCLoginMod mod;

    /**
     * 构造。
     *
     * @param mod 模组实例
     */
    public PlayerConnectionListener(final MCLoginMod mod) {
        this.mod = mod;
    }

    /**
     * 玩家进服。
     *
     * @param event 登录事件
     */
    public void onPlayerLoggedIn(final ServerPlayer player) {
        final SessionManager sessions = mod.getSessions();
        final java.util.UUID uuid = player.getUUID();

        sessions.recordJoin(uuid);
        sessions.markLoggedOut(uuid);

        // U1：会话免登录判定（满足则自动放行并直接返回，不设 frozen、不发登录提示）
        if (trySessionAutoLogin(player)) {
            return;
        }

        sessions.recordFrozenPosition(uuid,
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());

        // R8：进服提示音效（登录事件在主线程）
        com.dsh.mclogin.message.SoundService.play(player,
                com.dsh.mclogin.message.SoundService.JOIN_HINT);

        final PlayerAccount account = mod.getStorage().get(uuid);
        if (mod.getStorage().isLastLoadSuspect()) {
            player.sendSystemMessage(com.dsh.mclogin.message.MessageService.component("mclogin.storage.unavailable"));
            return;
        }
        if (account == null) {
            player.sendSystemMessage(com.dsh.mclogin.message.MessageService.component("mclogin.join.register"));
        } else {
            player.sendSystemMessage(com.dsh.mclogin.message.MessageService.component("mclogin.join.login"));
            if (account.isLocked()) {
                player.sendSystemMessage(com.dsh.mclogin.message.MessageService.component(
                        "mclogin.join.locked", account.getRemainingLockSeconds()));
            }
        }

        final int timeout = mod.getConfig().getLoginTimeoutSeconds();
        if (timeout > 0) {
            player.sendSystemMessage(com.dsh.mclogin.message.MessageService.component(
                    "mclogin.join.timeout", timeout));
        }
    }

    /**
     * U1 会话免登录：判定「同 IP + 距上次成功登录 &lt; 时限」并自动放行。
     *
     * <p>判定顺序刻意做到<b>任何一条不满足即放行失败</b>，且失败不产生任何副作用；
     * 成功路径只置登录态（硬约束③：不调用 {@code markLoginSuccess}，不刷新时间戳），
     * 并同步清 frozen（硬约束④）。
     *
     * @param player 玩家
     * @return true = 已自动放行（调用方应跳过登录提示与冻结）
     */
    private boolean trySessionAutoLogin(final ServerPlayer player) {
        if (mod.getStorage().isLastLoadSuspect()) return false;
        final LoginConfig config = mod.getConfig();
        // 硬约束②⑥：总开关默认 false；时限 0 = 关闭
        if (!config.isEnableSessionAutoLogin()) {
            return false;
        }
        final int timeoutMinutes = config.getSessionTimeoutMinutes();
        if (timeoutMinutes <= 0) {
            return false;
        }
        // 硬约束①：仅正版验证服生效（离线服下"同 IP"等价于"任何知道你名字的人"）
        final MinecraftServer server = player.level().getServer();
        if (server == null || !server.usesAuthentication()) {
            return false;
        }
        final PlayerAccount account = mod.getStorage().get(player.getUUID());
        if (account == null) {
            return false;
        }
        final String lastIp = account.getLastIp();
        final String ip = safeIp(player);
        // "<unknown>"（非 INET 连接）与空串都视为"无有效 IP"：前者是所有此类玩家共用的
        // 字面量，拿它做免登录依据等于向整批玩家开放账号
        if (lastIp == null || lastIp.isEmpty() || UNKNOWN_IP.equals(lastIp)
                || ip.isEmpty() || UNKNOWN_IP.equals(ip) || !lastIp.equals(ip)) {
            return false;
        }
        final long lastLogin = account.getLastLoginAtMillis();
        if (lastLogin <= 0L) {
            return false; // 从未真实登录过（或改密后已失效），无会话可复用
        }
        final long elapsedMillis = System.currentTimeMillis() - lastLogin;
        if (elapsedMillis >= timeoutMinutes * 60_000L) {
            return false;
        }
        // 硬约束③：只置登录态，不刷新 lastIp / lastLoginAtMillis（防无限续期）
        // 硬约束④：markLoggedIn 与 clearFrozenPosition 同步处理（B3）
        final java.util.UUID uuid = player.getUUID();
        mod.getSessions().markLoggedIn(uuid);
        mod.getSessions().clearFrozenPosition(uuid);
        // R8：免登录视同登录成功，播同一音效（登录事件在主线程）
        com.dsh.mclogin.message.SoundService.play(player,
                com.dsh.mclogin.message.SoundService.LOGIN_SUCCESS);
        player.sendSystemMessage(com.dsh.mclogin.message.MessageService.component(
                "mclogin.session.autologin", elapsedMillis / 1000L));
        MCLoginMod.LOGGER.info("[MCLogin] 玩家 {} 通过会话免登录自动放行（同 IP，距上次登录 {} 秒）",
                player.getName().getString(), elapsedMillis / 1000L);
        return true;
    }

    /**
     * 玩家登出/掉线：清理会话。
     *
     * @param event 登出事件
     */
    public void onPlayerLoggedOut(final ServerPlayer player) {
        if (player == null) {
            return;
        }
        mod.getSessions().onPlayerLeave(player.getUUID());
    }

    /**
     * 玩家重生/换维度：更新冻结位置，避免跨维度后把玩家拉回旧坐标。
     *
     * @param event 重生事件
     */
    public void onPlayerRespawn(final ServerPlayer player) {
        final SessionManager sessions = mod.getSessions();
        if (!sessions.isLoggedIn(player.getUUID())) {
            sessions.recordFrozenPosition(player.getUUID(),
                    player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        } else {
            sessions.clearFrozenPosition(player.getUUID());
        }
    }

    /**
     * 安全获取玩家 IP（异常时返回空串）；语义与 {@code LoginCommands.safeIp} 一致。
     *
     * @param player 玩家
     * @return IP 字符串或空串
     */
    private static String safeIp(final ServerPlayer player) {
        try {
            final String ip = player.getIpAddress();
            return ip == null ? "" : ip;
        } catch (final RuntimeException ex) {
            return "";
        }
    }
}
