package com.dsh.mclogin.command;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.config.LoginConfig;
import com.dsh.mclogin.data.PlayerAccount;
import com.dsh.mclogin.data.YamlStorage;
import com.dsh.mclogin.security.BruteForceGuard;
import com.dsh.mclogin.security.PasswordHasher;
import com.dsh.mclogin.session.SessionManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 命令注册与处理：{@code /register /login /changepassword /unregister /mclogin}。
 *
 * <p>注册方式：在 {@code RegisterCommandsEvent} 中通过
 * {@code net.minecraft.commands.Commands.literal(...)} 构建（1.19+ 的包名）。
 *
 * <p>安全约定：
 * <ul>
 *   <li>任何分支都<b>不打印密码/哈希</b>到日志，也不回显密码给玩家。</li>
 *   <li>失败计数与锁定判定统一走 {@link BruteForceGuard}。</li>
 *   <li>// G8-6/U3（已落地）：四个命令的密码参数统一为 {@code word()}——密码不允许包含空格，
 *       含空格输入在 Brigadier 解析层直接报错，不进入失败计数。</li>
 * </ul>
 *
 * <p><b>G8-1 异步三件套（已落地）</b>：全部 PBKDF2 派生（{@code verify / burnTime /
 * 登录收敛重算 / 注册与改密的新口令派生}）在 {@link com.dsh.mclogin.security.HashExecutor}
 * 的有界线程池内完成；<b>状态变更一律经 {@code server.execute(...)} 回主线程提交</b>
 * （本类的 {@code commitXxx} 方法），工作线程只做纯计算、不写任何共享可变状态。
 * 同 UUID 在途去重兼作 ≥500ms 冷却（防 DoS 放大），池/队满时拒绝并给繁忙提示，
 * <b>绝不回退主线程执行</b>（禁用 CallerRunsPolicy 的原因）。
 *
 * <p><b>U2 i18n（已落地）</b>：全部玩家可见消息走 {@code Component.translatable}，
 * 文案在 {@code assets/mclogin/lang/zh_cn.json} 与 {@code en_us.json}；日志仍为字面量
 * （日志面向运维，不随客户端语言变化）。
 */
public final class LoginCommands {

    /** 私有构造，避免实例化。 */
    private LoginCommands() {
        throw new UnsupportedOperationException("工具类不可实例化");
    }

    /**
     * 注册全部命令。
     *
     * @param dispatcher 命令分发器
     * @param mod        模组实例
     */
    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher,
                                final MCLoginMod mod) {
        dispatcher.register(
                Commands.literal("register")
                        .then(Commands.argument("password", StringArgumentType.word())
                                .then(Commands.argument("confirm", StringArgumentType.word())
                                        .executes(ctx -> doRegister(ctx, mod))))
        );

        dispatcher.register(
                Commands.literal("login")
                        .then(Commands.argument("password", StringArgumentType.word())
                                .executes(ctx -> doLogin(ctx, mod)))
        );

        dispatcher.register(
                Commands.literal("changepassword")
                        .then(Commands.argument("oldPassword", StringArgumentType.word())
                                .then(Commands.argument("newPassword", StringArgumentType.word())
                                        .executes(ctx -> doChangePassword(ctx, mod))))
        );

        dispatcher.register(
                Commands.literal("unregister")
                        .then(Commands.argument("password", StringArgumentType.word())
                                .executes(ctx -> doUnregister(ctx, mod)))
        );

        dispatcher.register(
                Commands.literal("mclogin")
                        .requires(source -> source.hasPermission(3))
                        .then(Commands.literal("reload")
                                .executes(ctx -> doReload(ctx, mod)))
                        .then(Commands.literal("status")
                                .executes(ctx -> doStatus(ctx, mod)))
        );
    }

    // ==================== /register ====================

    /**
     * 处理 {@code /register <密码> <确认密码>}。
     *
     * <p>G8-1：旧实现在主线程同步 {@code applyNewPassword}（一次完整 PBKDF2）；
     * 现在预检后把派生丢进池内（算出 salt/hash，不落任何字段），主线程提交时再写入，
     * 并复查"仍未注册"——旧实现先 {@code getOrCreate} 再算哈希，异步化后若沿用会留下
     * "hash 为空的僵尸账号行"（提交被拒/玩家断线时），故改为提交时才建行。
     *
     * @param ctx 命令上下文
     * @param mod 模组实例
     * @return Brigadier 结果码（1 = 已受理；结果由异步提交回主线程后给出）
     */
    private static int doRegister(final CommandContext<CommandSourceStack> ctx, final MCLoginMod mod) {
        final ServerPlayer player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        return registerWithPassword(mod, player, ctx.getSource().getServer(),
                StringArgumentType.getString(ctx, "password"),
                StringArgumentType.getString(ctx, "confirm"));
    }

    /**
     * ★ R8 / D-UI-2：注册的<b>可复用入口</b>，供铁砧 UI 调用（两步输入：密码 + 确认）。
     *
     * <p>与 {@link #loginWithPassword} 同理：直接调用而非派发 {@code /register} 命令，
     * 保证 UI 与命令走同一条路径（同一策略校验、同一池、同一文案与音效）。
     *
     * @param mod      模组实例
     * @param player   目标玩家
     * @param server   服务端
     * @param password 第一次输入的明文密码
     * @param confirm  第二次输入的确认密码
     * @return Brigadier 结果码
     */
    public static int registerWithPassword(final MCLoginMod mod, final ServerPlayer player,
                                           final MinecraftServer server, final String password,
                                           final String confirm) {
        if (player == null || server == null) {
            return 0;
        }
        final LoginConfig config = mod.getConfig();
        final YamlStorage storage = mod.getStorage();
        final UUID uuid = player.getUUID();

        if (storage.isLastLoadSuspect()) {
            send(player, msg("mclogin.storage.unavailable"));
            return 0;
        }
        if (storage.has(uuid)) {
            send(player, msg("mclogin.register.already"));
            return 0;
        }
        if (!password.equals(confirm)) {
            send(player, msg("mclogin.register.mismatch"));
            return 0;
        }
        final Component policyError = passwordPolicyError(password, config);
        if (policyError != null) {
            send(player, policyError);
            return 0;
        }

        // server 由调用方传入（命令路径取 ctx.getSource().getServer()，UI 路径取 player.level().getServer()）
        final PasswordHasher hasher = mod.getHasher();
        final int iterations = config.getPbkdf2Iterations();
        final boolean submitted = mod.getHashExecutor().submit(uuid, () -> {
            try {
                // 池内：纯派生，不碰任何共享可变状态
                final String salt = hasher.generateSalt();
                final String hash = hasher.hash(password, salt, iterations, PasswordHasher.DEFAULT_KEY_LENGTH);
                server.execute(() -> commitRegister(mod, player, salt, hash, iterations));
            } catch (final RuntimeException ex) {
                MCLoginMod.LOGGER.error("[MCLogin] 注册派生失败（uuid={}）", uuid, ex);
                server.execute(() -> send(player, msg("mclogin.internal_error")));
            }
        });
        return submitFeedback(player, submitted);
    }

    /**
     * /register 的主线程提交点（G8-1）。
     */
    private static void commitRegister(final MCLoginMod mod, final ServerPlayer player,
                                       final String salt, final String hash, final int iterations) {
        if (!isPlayerStillValid(player)) {
            return;
        }
        final YamlStorage storage = mod.getStorage();
        final UUID uuid = player.getUUID();
        if (storage.isLastLoadSuspect()) {
            send(player, msg("mclogin.storage.unavailable"));
            return;
        }
        if (storage.has(uuid)) {
            // 派生期间该 UUID 被并发注册（同 UUID 去重已挡住本人，此处防外部写路径）
            send(player, msg("mclogin.register.already"));
            return;
        }
        final PlayerAccount account = storage.getOrCreate(uuid);
        // [C-N1] 四字段成组写入（哈希已在池内算完，此处只做赋值）
        mod.getHasher().applyPrecomputed(account, salt, hash, iterations, PasswordHasher.DEFAULT_KEY_LENGTH);
        account.setUsername(player.getName().getString());
        account.setLastIp(safeIp(player));
        account.setRegisteredAtMillis(System.currentTimeMillis());
        account.resetFailures();
        storage.markDirty();

        mod.getSessions().markLoggedIn(uuid);
        mod.getSessions().clearFrozenPosition(uuid);
        mod.getGuard().reset(uuid, safeIp(player));

        // R8：注册成功音效（本提交点在 server.execute 内，主线程）
        com.dsh.mclogin.message.SoundService.play(player,
                com.dsh.mclogin.message.SoundService.REGISTER_SUCCESS);
        send(player, msg("mclogin.register.success"));
        MCLoginMod.LOGGER.info("[MCLogin] 玩家 {} 注册成功", player.getName().getString());
    }

    // ==================== /login ====================

    /**
     * 处理 {@code /login <密码>}。
     *
     * <p>G8-1：{@code verify / burnTime / 收敛重算} 全部在池内完成且<b>同池同优先级</b>
     * （B4 的耗时抹平依赖"账号不存在分支"与"账号存在分支"走同一条队列，调度差异为零）。
     * 收敛重算（G1(ii)③）需要明文——明文只在本次命令里存在，故重算也必须在提交前于池内完成；
     * 但四字段落盘写入（{@code applyPrecomputed}）推迟到主线程提交点。
     *
     * <p>锁定预检前移（本轮回）：锁定中（账号或 IP 任一维度）的请求<b>不进池、不烧时间</b>，
     * 且无论账号是否存在都给同一条锁定文案——旧行为"不存在的账号 + 锁定 IP 仍烧时间并续期"
     * 既浪费池资源，也让锁定分支与失败分支可区分。
     *
     * @param ctx 命令上下文
     * @param mod 模组实例
     * @return Brigadier 结果码
     */
    private static int doLogin(final CommandContext<CommandSourceStack> ctx, final MCLoginMod mod) {
        final ServerPlayer player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        return loginWithPassword(mod, player, ctx.getSource().getServer(),
                StringArgumentType.getString(ctx, "password"));
    }

    /**
     * ★ R8 / D-UI-2：登录的<b>可复用入口</b>，供铁砧 UI（{@code ui.AnvilInput}）调用。
     *
     * <p><b>为什么抽出来而不是让 UI 去派发 {@code /login <密码>}</b>：
     * <ul>
     *   <li>派发命令会让明文密码进入命令字符串，且会触发 {@code CommandEvent}
     *       （进而走 {@code LoginRestrictionListener.onCommand}），多出无谓的副作用；</li>
     *   <li>直接调用本方法可以<b>保证</b>"UI 与命令走同一条验证路径"——
     *       同一把 {@code HashExecutor} 池、同一套失败计数与锁定、同一套文案与音效，
     *       不存在"UI 旁路"的可能。</li>
     * </ul>
     *
     * <p>本方法只做搬运：逻辑与 {@code /login} 完全一致，未做任何分叉。
     *
     * @param mod      模组实例
     * @param player   目标玩家（须为服务端玩家）
     * @param server   服务端（用于把状态变更调度回主线程）
     * @param password 明文密码（调用方负责不要记录它）
     * @return Brigadier 结果码
     */
    public static int loginWithPassword(final MCLoginMod mod, final ServerPlayer player,
                                        final MinecraftServer server, final String password) {
        final YamlStorage storage = mod.getStorage();
        final SessionManager sessions = mod.getSessions();
        final BruteForceGuard guard = mod.getGuard();
        final String ip = safeIp(player);
        final UUID uuid = player.getUUID();

        if (sessions.isLoggedIn(uuid)) {
            send(player, msg("mclogin.login.already"));
            return 1;
        }

        final PlayerAccount account = storage.get(uuid);
        final int iterationsBefore = account == null ? 0 : account.getIterations();

        // 锁定预检（账号 + IP 维度；account 为 null 时只看 IP 维度）。
        // 文案统一为"已被临时锁定"，不区分触发维度（不泄露账号存在性）。
        final long remain = guard.lockRemainingSeconds(account, ip);
        if (remain > 0L) {
            // R8：锁定音效（低音调；本处为命令主线程，不经异步池）
            com.dsh.mclogin.message.SoundService.playLocked(player);
            send(player, msg("mclogin.login.locked", remain));
            return 0;
        }

        final PasswordHasher hasher = mod.getHasher();
        final int policyIterations = mod.getConfig().getPbkdf2Iterations();
        final boolean submitted = mod.getHashExecutor().submit(uuid, () -> {
            try {
                if (account == null) {
                    // B4：账号不存在也要烧同等迭代次数（时序抹平）——与 verify 同池同优先级；
                    // 失败计入 IP 维度（recordFailure(null, ip)），否则可对不存在的用户名无限撞库
                    hasher.burnTime(password, policyIterations);
                    server.execute(() -> commitLoginResult(
                            mod, player, null, ip, iterationsBefore, false, null, null, policyIterations));
                    return;
                }
                final boolean ok = hasher.verify(password, account, policyIterations);
                String convergedSalt = null;
                String convergedHash = null;
                if (ok && account.getIterations() != policyIterations) {
                    // G1(ii)③：收敛重算也是一次 PBKDF2，留在池内（明文此刻在手）；
                    // 四字段落盘写入推迟到主线程（G8-1：工作线程不改共享状态）
                    convergedSalt = hasher.generateSalt();
                    convergedHash = hasher.hash(password, convergedSalt, policyIterations,
                            PasswordHasher.DEFAULT_KEY_LENGTH);
                }
                final String newSalt = convergedSalt;
                final String newHash = convergedHash;
                server.execute(() -> commitLoginResult(
                        mod, player, account, ip, iterationsBefore, ok, newSalt, newHash, policyIterations));
            } catch (final RuntimeException ex) {
                MCLoginMod.LOGGER.error("[MCLogin] 登录派生失败（uuid={}）", uuid, ex);
                server.execute(() -> send(player, msg("mclogin.internal_error")));
            }
        });
        return submitFeedback(player, submitted);
    }

    /**
     * /login 的主线程提交点（G8-1）：无论成败，全部状态变更都在这里发生。
     *
     * @param accountAtHashTime 池内参与校验的账号对象；null = "账号不存在"分支
     * @param verified          派生结果（true = 密码正确）
     * @param iterationsBefore  提交前该账号的迭代数（用于收敛日志）
     * @param convergedSalt/-Hash 收敛重算结果（仅成功且需收敛时非 null，G1(ii)③）
     * @param policyIterations  当前配置迭代数
     */
    private static void commitLoginResult(final MCLoginMod mod, final ServerPlayer player,
                                          final PlayerAccount accountAtHashTime, final String ip,
                                          final int iterationsBefore, final boolean verified,
                                          final String convergedSalt, final String convergedHash,
                                          final int policyIterations) {
        if (!isPlayerStillValid(player)) {
            return;
        }
        final YamlStorage storage = mod.getStorage();
        final UUID uuid = player.getUUID();
        final SessionManager sessions = mod.getSessions();
        if (sessions.isLoggedIn(uuid)) {
            return; // 派生期间已在别处完成登录（免登录/并发提交）：幂等退出
        }
        // 重新取当前账号：派生期间可能发生 reload（对象被整体替换）或注销
        final PlayerAccount current = storage.get(uuid);
        if (!verified) {
            // B4：计入失败（current 为 null 时只记 IP 维度）；文案与"账号不存在"分支完全一致。
            // ★ B4 完全收口（本轮回）：去掉"你还可以尝试 N 次"——它会向撞库者确认账号存在，
            //   是 G8-3(a) 的残留通道（时序已由 burnTime 抹平，文案再不统一就前功尽弃）。
            mod.getGuard().recordFailure(current, ip);
            storage.markDirty();
            // R8：登录失败音效（本提交点在 server.execute 内，主线程）
            com.dsh.mclogin.message.SoundService.play(player,
                    com.dsh.mclogin.message.SoundService.LOGIN_FAILED);
            send(player, msg("mclogin.login.failed"));
            return;
        }
        if (current == null || current != accountAtHashTime) {
            // 密码正确但账号在派生期间被 reload/注销：不把结果提交给陈旧对象，按失败处理
            mod.getGuard().recordFailure(null, ip);
            storage.markDirty();
            com.dsh.mclogin.message.SoundService.play(player,
                    com.dsh.mclogin.message.SoundService.LOGIN_FAILED);
            send(player, msg("mclogin.login.failed"));
            return;
        }
        if (convergedSalt != null) {
            // [C-N1] 四字段成组替换（主线程，与 dumpAccounts/verify 的账号锁配对）
            mod.getHasher().applyPrecomputed(current, convergedSalt, convergedHash,
                    policyIterations, PasswordHasher.DEFAULT_KEY_LENGTH);
            MCLoginMod.LOGGER.info("[MCLogin] 已按当前策略收敛账号 {} 的 PBKDF2 迭代数：{} -> {}",
                    current.getUuid(), iterationsBefore, policyIterations);
        }
        current.markLoginSuccess(ip);
        storage.markDirty();
        mod.getGuard().reset(uuid, ip);
        sessions.markLoggedIn(uuid);
        sessions.clearFrozenPosition(uuid);
        // R8：登录成功音效（本提交点在 server.execute 内，主线程）
        com.dsh.mclogin.message.SoundService.play(player,
                com.dsh.mclogin.message.SoundService.LOGIN_SUCCESS);
        send(player, msg("mclogin.login.success"));
        MCLoginMod.LOGGER.info("[MCLogin] 玩家 {} 登录成功（IP={}）", player.getName().getString(), ip);
    }

    // ==================== /changepassword ====================

    /**
     * 处理 {@code /changepassword <旧密码> <新密码>}（需已登录）。
     *
     * <p>G8-1：旧口令 verify 与新口令派生都在池内完成；主线程提交时才落字段。
     * 新口令策略检查前移到预检（纯内存判断），弱密码不消耗池资源。
     *
     * <p>U1 硬约束⑤：改密成功后<b>使会话免登录立即失效</b>（置 {@code lastLoginAtMillis=0}），
     * 否则同 IP 者可借免登录窗口改密<b>永久夺号</b>（D5 按最坏情况设防，零成本）。
     *
     * @param ctx 命令上下文
     * @param mod 模组实例
     * @return Brigadier 结果码
     */
    private static int doChangePassword(final CommandContext<CommandSourceStack> ctx, final MCLoginMod mod) {
        final ServerPlayer player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        final String oldPassword = StringArgumentType.getString(ctx, "oldPassword");
        final String newPassword = StringArgumentType.getString(ctx, "newPassword");

        final LoginConfig config = mod.getConfig();
        final YamlStorage storage = mod.getStorage();
        final PasswordHasher hasher = mod.getHasher();
        final UUID uuid = player.getUUID();

        final PlayerAccount account = storage.get(uuid);
        if (account == null) {
            send(player, msg("mclogin.player.not_registered"));
            return 0;
        }
        if (!mod.getSessions().isLoggedIn(uuid)) {
            send(player, msg("mclogin.changepassword.need_login"));
            return 0;
        }
        final Component policyError = passwordPolicyError(newPassword, config);
        if (policyError != null) {
            send(player, policyError);
            return 0;
        }

        final int policyIterations = config.getPbkdf2Iterations();
        final MinecraftServer server = ctx.getSource().getServer();
        final boolean submitted = mod.getHashExecutor().submit(uuid, () -> {
            try {
                // G1(ii)：verify 补传当前策略迭代数（池内执行）
                final boolean ok = hasher.verify(oldPassword, account, policyIterations);
                String newSalt = null;
                String newHash = null;
                if (ok) {
                    // 新口令派生在池内完成（纯计算），落字段留到主线程提交
                    newSalt = hasher.generateSalt();
                    newHash = hasher.hash(newPassword, newSalt, policyIterations,
                            PasswordHasher.DEFAULT_KEY_LENGTH);
                }
                final String salt = newSalt;
                final String hash = newHash;
                server.execute(() -> commitChangePassword(mod, player, account, ok, salt, hash, policyIterations));
            } catch (final RuntimeException ex) {
                MCLoginMod.LOGGER.error("[MCLogin] 改密派生失败（uuid={}）", uuid, ex);
                server.execute(() -> send(player, msg("mclogin.internal_error")));
            }
        });
        return submitFeedback(player, submitted);
    }

    /**
     * /changepassword 的主线程提交点（G8-1）。
     */
    private static void commitChangePassword(final MCLoginMod mod, final ServerPlayer player,
                                             final PlayerAccount accountAtHashTime, final boolean verified,
                                             final String newSalt, final String newHash,
                                             final int policyIterations) {
        if (!isPlayerStillValid(player)) {
            return;
        }
        final YamlStorage storage = mod.getStorage();
        final UUID uuid = player.getUUID();
        if (!verified) {
            send(player, msg("mclogin.changepassword.old_wrong"));
            return;
        }
        final PlayerAccount current = storage.get(uuid);
        if (current == null || current != accountAtHashTime
                || !mod.getSessions().isLoggedIn(uuid)) {
            // 派生期间账号被 reload/注销或已登出：拒绝提交，绝不静默写陈旧对象
            send(player, msg("mclogin.internal_error"));
            return;
        }
        mod.getHasher().applyPrecomputed(current, newSalt, newHash, policyIterations,
                PasswordHasher.DEFAULT_KEY_LENGTH);
        // U1 硬约束⑤：会话免登录立即失效（只杀重连免登窗口，不影响当前已登录状态；
        // 下次用新密码真实登录会重新建立会话）
        current.setLastLoginAtMillis(0L);
        storage.markDirty();
        send(player, msg("mclogin.changepassword.success"));
        MCLoginMod.LOGGER.info("[MCLogin] 玩家 {} 修改了密码（会话免登录已失效）",
                player.getName().getString());
    }

    // ==================== /unregister ====================

    /**
     * 处理 {@code /unregister <密码>}（注销账号，需已登录且密码正确）。
     *
     * <p>G8-1：verify 在池内完成，删除与重新布防在主线程提交点执行。
     * U1 硬约束⑤：账号行删除 = 会话免登录随之失效（无账号即无免登录依据）。
     *
     * @param ctx 命令上下文
     * @param mod 模组实例
     * @return Brigadier 结果码
     */
    private static int doUnregister(final CommandContext<CommandSourceStack> ctx, final MCLoginMod mod) {
        final ServerPlayer player = requirePlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        final String password = StringArgumentType.getString(ctx, "password");
        final YamlStorage storage = mod.getStorage();
        final UUID uuid = player.getUUID();

        final PlayerAccount account = storage.get(uuid);
        if (account == null) {
            send(player, msg("mclogin.player.not_registered"));
            return 0;
        }
        if (!mod.getSessions().isLoggedIn(uuid)) {
            send(player, msg("mclogin.unregister.need_login"));
            return 0;
        }

        final int policyIterations = mod.getConfig().getPbkdf2Iterations();
        final MinecraftServer server = ctx.getSource().getServer();
        final boolean submitted = mod.getHashExecutor().submit(uuid, () -> {
            try {
                final boolean ok = mod.getHasher().verify(password, account, policyIterations);
                server.execute(() -> commitUnregister(mod, player, account, ok));
            } catch (final RuntimeException ex) {
                MCLoginMod.LOGGER.error("[MCLogin] 注销校验失败（uuid={}）", uuid, ex);
                server.execute(() -> send(player, msg("mclogin.internal_error")));
            }
        });
        return submitFeedback(player, submitted);
    }

    /**
     * /unregister 的主线程提交点（G8-1）。
     */
    private static void commitUnregister(final MCLoginMod mod, final ServerPlayer player,
                                         final PlayerAccount accountAtHashTime, final boolean verified) {
        if (!isPlayerStillValid(player)) {
            return;
        }
        final YamlStorage storage = mod.getStorage();
        final UUID uuid = player.getUUID();
        if (!verified) {
            send(player, msg("mclogin.unregister.wrong"));
            return;
        }
        final PlayerAccount current = storage.get(uuid);
        if (current == null || current != accountAtHashTime
                || !mod.getSessions().isLoggedIn(uuid)) {
            send(player, msg("mclogin.internal_error"));
            return;
        }
        storage.remove(uuid);
        storage.markDirty();

        // B3 修复（高危，勿改回）：这里**不能**调 sessions.onPlayerLeave(uuid)！
        // onPlayerLeave 会连 frozenPositions 一起清掉，而 LoginRestrictionListener 的
        //   ① 超时踢出（依赖 frozen != null 才判超时）
        //   ② 移动限制（依赖 frozen 作为回退坐标）
        // 都以 frozen != null 为前提；frozen 只在 PlayerLoggedInEvent 写入，玩家仍在服内不会重触发。
        // 于是注销后 frozen == null → 移动限制失效（可自由跑）+ 超时踢出失效（可无限滞留）= 绕过 R7。
        // 正确做法是"就地重新布防"：标记未登录 + 重置进服计时 + 以当前坐标重设冻结点。
        final SessionManager sessions = mod.getSessions();
        sessions.markLoggedOut(uuid);
        sessions.recordJoin(uuid);
        sessions.recordFrozenPosition(uuid,
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot());

        send(player, msg("mclogin.unregister.success"));
        MCLoginMod.LOGGER.info("[MCLogin] 玩家 {} 注销了账号", player.getName().getString());
    }

    // ==================== /mclogin ====================

    /**
     * 处理 {@code /mclogin reload}：重新从磁盘加载 accounts.yml 并清理 IP 状态。
     *
     * <p>// TODO(待校验): ForgeConfig 的重载由 Forge 自身管理（ModConfigEvent），
     * 这里只重载账号数据与内存态；若下游希望配置也热重载，需要监听
     * {@code net.minecraftforge.fml.event.config.ModConfigEvent.Reloading}。
     *
     * @param ctx 命令上下文
     * @param mod 模组实例
     * @return Brigadier 结果码
     */
    private static int doReload(final CommandContext<CommandSourceStack> ctx, final MCLoginMod mod) {
        // B6 修复：原实现先 saveNow() 把内存态覆盖写回 accounts.yml、紧接着 load() 又读回来，
        // 运维在磁盘上手改的内容会被冲掉，reload 实际成了 no-op。
        // 正确语义是"以磁盘为准"：直接 load() 覆盖内存缓存即可。
        //
        // P1 补丁（"以磁盘为准"不能静默销毁数据）：若存在未落盘改动，直接 load 会把它销毁，
        // 而管理员最可能去跑 reload 的时机恰恰是"账号数据看起来不对劲"的时候。
        // 因此重载前先把内存态导出为 accounts.yml.rescue-<ts>，再按 B6 语义 load。
        final YamlStorage storage = mod.getStorage();
        final boolean hasPending = storage.hasPendingChanges();
        final Path rescue = hasPending ? storage.exportRescueCopy() : null;
        if ((hasPending && rescue == null) || !storage.load()) {
            ctx.getSource().sendFailure(msg("mclogin.reload.failed"));
            return 0;
        }
        mod.getGuard().clearIpStates();
        // 1.20.1 中 CommandSourceStack#sendSuccess 的签名是 sendSuccess(Supplier<Component>, boolean)。
        // U2：拼接改用可追加组件 + translatable（不再字符串相拼，避免退化回 literal）。
        ctx.getSource().sendSuccess(() -> {
            MutableComponent text = Component.empty().append(msg("mclogin.reload.done"));
            if (hasPending) {
                text = text.append(Component.empty().append(rescue != null
                        ? msg("mclogin.reload.rescue", rescue.toString())
                        : msg("mclogin.reload.rescue_failed")));
            }
            return text;
        }, true);
        return 1;
    }

    /**
     * 处理 {@code /mclogin status}：输出在线/账号统计（不含任何敏感信息）。
     *
     * <p>P1：暴露"待落盘改动 / 连续保存失败次数"；P0：暴露"磁盘内容疑似不可解析"——
     * 这是整个模组最危险的状态（账号库被读空却不报错），只写日志运维一定会漏看。
     * U2：占位符化，不退化回 literal。
     *
     * @param ctx 命令上下文
     * @param mod 模组实例
     * @return Brigadier 结果码
     */
    private static int doStatus(final CommandContext<CommandSourceStack> ctx, final MCLoginMod mod) {
        final YamlStorage storage = mod.getStorage();
        final int accounts = storage.getAccountCount();
        final int online = mod.getSessions().getLoggedInCount();
        final int trackedIps = mod.getGuard().getTrackedIpCount();
        final boolean pending = storage.hasPendingChanges();
        final long saveFailures = storage.getConsecutiveSaveFailures();
        final boolean suspect = storage.isLastLoadSuspect();
        ctx.getSource().sendSuccess(() -> Component.empty()
                .append(msg("mclogin.status.base", accounts, online, trackedIps))
                .append(msg(pending ? "mclogin.status.pending" : "mclogin.status.synced"))
                .append(saveFailures > 0L ? msg("mclogin.status.failures", saveFailures) : Component.empty())
                .append(suspect ? msg("mclogin.status.suspect") : Component.empty()), true);
        return 1;
    }

    // ==================== 工具方法 ====================

    /**
     * 构造玩家消息（R8 后由 {@link com.dsh.mclogin.message.MessageService} 在服务端解析为
     * 字面文本——原版客户端没有本模组语言文件，translatable 会显示键名原文）。
     *
     * @param key  语言键（assets/mclogin/lang/*.json）
     * @param args 占位符参数
     * @return 组件
     */
    private static Component msg(final String key, final Object... args) {
        return com.dsh.mclogin.message.MessageService.component(key, args);
    }

    /**
     * G8-1 提交结果反馈：受理时给占位提示（真实结果由异步提交回主线程后给出）；
     * 拒绝（冷却 / 在途 / 池满）给统一繁忙提示。<b>拒绝不进失败计数</b>——
     * 这是防滥用限流（G8-10 冷却），不是撞库失败，两者不得互相干扰。
     *
     * @param player    玩家
     * @param submitted 是否被执行器受理
     * @return Brigadier 结果码
     */
    private static int submitFeedback(final ServerPlayer player, final boolean submitted) {
        send(player, msg(submitted ? "mclogin.verifying" : "mclogin.busy"));
        return submitted ? 1 : 0;
    }

    /**
     * 密码策略检查（策略逻辑自 {@code PasswordHasher.validatePassword} 等价搬移，
     * U2：提示走 translatable）。
     *
     * @param password 明文密码
     * @param config   配置
     * @return 错误提示组件；null 表示通过
     */
    public static Component passwordPolicyError(final String password, final LoginConfig config) {
        if (password == null || password.isEmpty()) {
            return msg("mclogin.password.empty");
        }
        if (password.length() < config.getMinPasswordLength()) {
            return msg("mclogin.password.too_short", config.getMinPasswordLength());
        }
        if (password.length() > config.getMaxPasswordLength()) {
            return msg("mclogin.password.too_long", config.getMaxPasswordLength());
        }
        if (password.chars().anyMatch(Character::isWhitespace)) {
            return msg("mclogin.password.whitespace");
        }
        return null;
    }

    /**
     * 异步提交回主线程后的在线校验：玩家已断线则丢弃本次结果（会话清理由登出事件负责），
     * 避免对已离线玩家改登录态/发消息。
     *
     * @param player 玩家
     * @return true 仍在线且是同一实例
     */
    private static boolean isPlayerStillValid(final ServerPlayer player) {
        final MinecraftServer server = player.level().getServer();
        return server != null && server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    /**
     * 从命令源取玩家；非玩家（控制台/命令方块）时提示并返回 null。
     *
     * @param source 命令源
     * @return 服务端玩家或 null
     */
    private static ServerPlayer requirePlayer(final CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player;
        }
        source.sendFailure(msg("mclogin.player_only"));
        return null;
    }

    /**
     * 安全获取玩家 IP（异常时返回空串）。
     *
     * <p><b>已由 D1/D2 以字节码实证</b>：1.20.1 中 {@code getIpAddress()} 存在且 public，
     * 返回裸地址字符串（Guava {@code InetAddresses.toAddrString}），不含端口与前导斜杠；
     * 非 INET 连接返回字面量 {@code "<unknown>"}（会话免登录与 IP 守卫均须把该值视为
     * "所有玩家共用的一个桶"，见 PlayerConnectionListener 与 README）。
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

    /**
     * 向玩家发送系统消息。
     *
     * @param player  玩家
     * @param message 组件（translatable，客户端按语言渲染）
     */
    private static void send(final ServerPlayer player, final Component message) {
        player.sendSystemMessage(message);
    }
}
