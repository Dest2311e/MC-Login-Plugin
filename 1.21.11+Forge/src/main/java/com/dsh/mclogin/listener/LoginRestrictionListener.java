package com.dsh.mclogin.listener;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.session.SessionManager;
import com.mojang.brigadier.ParseResults;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;

import java.util.Locale;

/**
 * 未登录玩家限制器。
 *
 * <p>限制项（均可通过 {@code mclogin-server.toml} 的 {@code restriction} 段开关）：
 * <ul>
 *   <li><b>移动</b>：{@code TickEvent.PlayerTickEvent} 中把玩家位置回退到进服坐标，
 *       并清空速度（{@code allowMove=false} 时）。</li>
 *   <li><b>交互</b>：取消 {@code PlayerInteractEvent}（破坏/放置/使用物品/交互实体），
 *       并额外取消 {@code BlockEvent.BreakEvent} 兜底。</li>
 *   <li><b>聊天</b>：取消 {@code ServerChatEvent}。</li>
 *   <li><b>命令</b>：取消 {@code CommandEvent}（放行 {@code /login /register /changepassword
 *       /unregister /mclogin}，其余一律拒绝）。</li>
 *   <li><b>攻击实体</b>：取消 {@code AttackEntityEvent}。</li>
 *   <li><b>超时踢出</b>：进服超过 {@code loginTimeoutSeconds} 仍未登录则断开连接。</li>
 * </ul>
 *
 * <p><b>已核对（对照 NeoForge/Forge 1.20.1 源码）</b>：
 * <ul>
 *   <li>{@code net.minecraftforge.event.ServerChatEvent}：存在且 {@code @Cancelable}，{@code getPlayer()} 返回 {@code ServerPlayer}。</li>
 *   <li>{@code net.minecraftforge.event.CommandEvent}：存在且 {@code @Cancelable}，{@code getParseResults()} 返回
 *       {@code ParseResults<CommandSourceStack>}。</li>
 *   <li>{@code TickEvent.PlayerTickEvent#player}：是 {@code public final Player player}（<b>公开字段，无 getter</b>）。</li>
 *   <li>{@code AttackEntityEvent}：继承 {@code PlayerEvent}，{@code getEntity()} 返回 {@code Player}，可取消。</li>
 * </ul>
 *
 * <p>// TODO(待校验): 以下点仍需下游在真实环境确认：
 * <ol>
 *   <li>{@code PlayerEvent.PlayerRespawnEvent} 与 {@code PlayerContainerEvent.Open} 在 1.20.1 的
 *       确切构造/子类形态与可取消性（仅用于冻结坐标刷新与容器兜底，失败不影响主流程；
 *       Open 是右键开箱之外的第二道兜底——其他模组/命令直接打开容器 UI 的路径）。</li>
 *   <li><b>G4 复核结论（GLM，2026-09-27）</b>：切旁观 / 骑乘实体 / 末影珍珠等<b>位移类</b>绕过
 *       在 {@code allowMove=false}（默认）下已被逐 tick 位置回退中和——未登录玩家的坐标
 *       每 tick 被拉回冻结点，无论其通过何种方式位移；残差仅为 1 tick 内的视觉穿透（外观级）。
 *       真正需要事件兜底的只有"打开容器 UI"这一类（见 {@code PlayerContainerEvent.Open}）。</li>
 *   <li>更高版本（1.20.6+/1.21.x/NeoForge）中 {@code ServerChatEvent} 与 {@code CommandEvent} 的包名与取消方式可能变化，需重新适配。</li>
 * </ol>
 */
public final class LoginRestrictionListener {

    /**
     * 允许未登录玩家执行的命令（不含斜杠）。
     *
     * <p><b>N1 修复</b>：原白名单含 {@code "l"} / {@code "reg"} 两个<b>本模组并未注册</b>的别名。
     * 一旦装了其他注册了 {@code /l} 或 {@code /reg} 的模组，未登录玩家就能借这些别名执行任意逻辑。
     * 白名单必须与 {@code LoginCommands.register()} 中实际注册的命令严格一一对应。
     */
    private static final String[] ALLOWED_COMMANDS = {
            "login", "register", "changepassword", "unregister", "mclogin"
    };

    /** IP 状态清理周期（tick）：1200 tick ≈ 60 秒。 */
    private static final int PURGE_INTERVAL_TICKS = 1200;

    /** 模组实例。 */
    private final MCLoginMod mod;

    /** 自上次清理 IP 状态以来经过的 tick 数。 */
    private int purgeTickCounter = 0;

    /**
     * 构造。
     *
     * @param mod 模组实例
     */
    public LoginRestrictionListener(final MCLoginMod mod) {
        this.mod = mod;
    }

    /**
     * 每 tick 检查：超时踢出 + 位置回退。
     *
     * @param event 玩家 tick 事件
     */
    @SubscribeEvent
    public void onPlayerTick(final TickEvent.PlayerTickEvent.Pre event) {
        if (!(event.player() instanceof ServerPlayer player)) {
            return;
        }
        final SessionManager sessions = mod.getSessions();
        final java.util.UUID uuid = player.getUUID();

        if (sessions.isLoggedIn(uuid)) {
            return;
        }

        // 1) 超时踢出
        final int timeout = mod.getConfig().getLoginTimeoutSeconds();
        if (timeout > 0) {
            final long elapsed = sessions.elapsedSinceJoinMillis(uuid);
            if (sessions.getFrozenPosition(uuid) != null && elapsed > timeout * 1000L) {
                // R8：断开原因也走服务端解析（原版客户端看不到 translatable 的键翻译）
                player.connection.disconnect(com.dsh.mclogin.message.MessageService.component(
                        "mclogin.timeout.kick", timeout));
                MCLoginMod.LOGGER.info("[MCLogin] 玩家 {} 登录超时被踢出", player.getName().getString());
                sessions.onPlayerLeave(uuid);
                return;
            }
            // R8：超时踢出前 30 秒警告（只提示一次；tick 处理器在主线程，音效可直接播放）
            if (timeout > 30 && sessions.getFrozenPosition(uuid) != null
                    && elapsed >= (timeout - 30) * 1000L
                    && sessions.markTimeoutWarnedIfFirst(uuid)) {
                com.dsh.mclogin.message.SoundService.play(player,
                        com.dsh.mclogin.message.SoundService.TIMEOUT_WARNING);
                player.sendSystemMessage(com.dsh.mclogin.message.MessageService.component(
                        "mclogin.timeout.warning", 30));
            }
        }

        // 2) 移动限制：回退到进服坐标
        if (!mod.getConfig().isAllowMove()) {
            final double[] frozen = sessions.getFrozenPosition(uuid);
            if (frozen != null) {
                final double dx = player.getX() - frozen[0];
                final double dy = player.getY() - frozen[1];
                final double dz = player.getZ() - frozen[2];
                if (dx * dx + dy * dy + dz * dz > 0.0001D) {
                    // ★ 真机验证修复（见记录.txt 第二十六节）：absMoveTo 只改服务端实体坐标，
                    //   不向客户端发传送包 —— MC 玩家移动是客户端权威的，客户端照常发移动包，
                    //   服务端回退立刻被覆盖，客户端毫无感知（forcedMove 不触发），限制形同虚设。
                    //   connection.teleport 会发送 PlayerPosition 包，强制客户端回位，才是真回退。
                    player.connection.teleport(frozen[0], frozen[1], frozen[2], (float) frozen[3], (float) frozen[4]);
                    player.setDeltaMovement(Vec3.ZERO);
                }
            }
        }
    }

    /**
     * 服务器 tick：定期清理过期的 IP 守卫状态。
     *
     * <p><b>B5 修复</b>：{@code BruteForceGuard#purgeExpired()} 原本定义了却没有任何调用点，
     * {@code ipStates} 只增不减，长开服务器会持续累积（只能靠重启或 {@code /mclogin reload} 全清）。
     * 这里挂在 {@code ServerTickEvent} 上，每 1200 tick（约 1 分钟）清理一次。
     *
     * @param event 服务器 tick 事件
     */
    @SubscribeEvent
    public void onServerTick(final TickEvent.ServerTickEvent.Pre event) {
        purgeTickCounter++;
        if (purgeTickCounter >= PURGE_INTERVAL_TICKS) {
            purgeTickCounter = 0;
            mod.getGuard().purgeExpired();
        }
    }

    /**
     * 交互限制。
     *
     * @param event 交互事件
     */
    @SubscribeEvent
    public boolean onRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        return event.getEntity() instanceof ServerPlayer player
            && !mod.getSessions().isLoggedIn(player.getUUID()) && !mod.getConfig().isAllowInteract();
    }
    @SubscribeEvent
    public boolean onRightClickItem(final PlayerInteractEvent.RightClickItem event) {
        return event.getEntity() instanceof ServerPlayer player
            && !mod.getSessions().isLoggedIn(player.getUUID()) && !mod.getConfig().isAllowInteract();
    }
    @SubscribeEvent
    public boolean onLeftClickBlock(final PlayerInteractEvent.LeftClickBlock event) {
        return event.getEntity() instanceof ServerPlayer player
            && !mod.getSessions().isLoggedIn(player.getUUID()) && !mod.getConfig().isAllowInteract();
    }
    @SubscribeEvent
    public boolean onEntityInteract(final PlayerInteractEvent.EntityInteract event) {
        return event.getEntity() instanceof ServerPlayer player
            && !mod.getSessions().isLoggedIn(player.getUUID()) && !mod.getConfig().isAllowInteract();
    }
    @SubscribeEvent
    public boolean onEntityInteractSpecific(final PlayerInteractEvent.EntityInteractSpecific event) {
        return event.getEntity() instanceof ServerPlayer player
            && !mod.getSessions().isLoggedIn(player.getUUID()) && !mod.getConfig().isAllowInteract();
    }

    /**
     * 破坏方块兜底限制。
     *
     * @param event 破坏事件
     */
    @SubscribeEvent
    public boolean onBlockBreak(final BlockEvent.BreakEvent event) {
        boolean canceled = false;
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return canceled;
        }
        if (mod.getSessions().isLoggedIn(player.getUUID())) {
            return canceled;
        }
        if (!mod.getConfig().isAllowInteract()) {
            canceled = true;
        }
    
        return canceled;
    }

    /**
     * 攻击实体限制。
     *
     * @param event 攻击事件
     */
    @SubscribeEvent
    public boolean onAttackEntity(final AttackEntityEvent event) {
        boolean canceled = false;
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return canceled;
        }
        if (mod.getSessions().isLoggedIn(player.getUUID())) {
            return canceled;
        }
        if (!mod.getConfig().isAllowInteract()) {
            canceled = true;
        }
    
        return canceled;
    }

    /**
     * 丢弃物品限制（N2 修复）。
     *
     * <p>注意 Forge 语义：取消 {@code ItemTossEvent} 会阻止物品进入世界，
     * <b>但不会阻止它从背包中移除</b>（见该事件 javadoc）。若只 cancel 不补偿，
     * 未登录玩家按 Q 丢东西会<b>永久损失该物品</b>。因此这里取消后要把物品塞回背包；
     * 背包已满时才以实体形式放回玩家脚下，避免物品凭空消失。
     *
     * @param event 丢弃物品事件
     */
    @SubscribeEvent
    public boolean onItemToss(final ItemTossEvent event) {
        boolean canceled = false;
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return canceled;
        }
        if (mod.getSessions().isLoggedIn(player.getUUID())) {
            return canceled;
        }
        if (mod.getConfig().isAllowInteract()) {
            return canceled;
        }
        canceled = true;
        final ItemStack stack = ((ItemEntity) event.getEntity()).getItem().copy();
        if (stack.isEmpty()) {
            return canceled;
        }
        if (!player.getInventory().add(stack)) {
            final ItemEntity backup = new ItemEntity(
                    player.level(), player.getX(), player.getY(), player.getZ(), stack);
            backup.setPickUpDelay(20);
            player.level().addFreshEntity(backup);
        }
    
        return canceled;
    }

    /**
     * 拾取物品限制（N2 修复）：未登录玩家不能捡起地上的掉落物。
     *
     * @param event 拾取事件
     */
    @SubscribeEvent
    public boolean onItemPickup(final EntityItemPickupEvent event) {
        boolean canceled = false;
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return canceled;
        }
        if (mod.getSessions().isLoggedIn(player.getUUID())) {
            return canceled;
        }
        if (!mod.getConfig().isAllowInteract()) {
            canceled = true;
        }
    
        return canceled;
    }

    /**
     * 开容器 UI 兜底限制（G8-2 补漏，GLM 落地；★ DSH 于 2026-09-27 真机修复）。
     *
     * <p>右键开箱/熔炉/漏斗已被 {@code RightClickBlock}（{@code PlayerInteractEvent.RightClickBlock}
     * 是其子类）拦住，<b>主路径已覆盖</b>；本监听兜的是"容器 UI 不经右键被打开"的旁路——
     * 其他模组或命令直接为未登录玩家打开容器界面。
     *
     * <p><b>★ 修复记录（DSH 实测，务必保留本注释）</b>：
     * 原实现写的是 {@code event.setCanceled(true)}，但
     * {@code PlayerContainerEvent.Open} <b>不是 @Cancelable 事件</b>，
     * 对它调用 {@code setCanceled()} 会抛：
     * <pre>
     * java.lang.UnsupportedOperationException: Attempted to call Event#setCanceled()
     *   on a non-cancelable event of type:
     *   net.minecraftforge.event.entity.player.PlayerContainerEvent.Open
     *     at com.dsh.mclogin.listener.LoginRestrictionListener.onContainerOpen(...)
     *     at net.minecraft.server.level.ServerPlayer.openMenu(...)
     * </pre>
     * 该异常发生在 {@code ServerPlayer.openMenu()} 的调用栈里且无人捕获 →
     * <b>未登录玩家只要打开任意容器界面就会把整个服务端打崩</b>
     * （实测崩溃报告：{@code forge-server/crash-reports/crash-2026-09-27_23.15.43-server.txt}）。
     *
     * <p>正确做法：<b>不取消，改为"下一 tick 关闭"</b>。
     * 必须延后到下一 tick —— {@code openMenu()} 会在本事件派发**之后**才把
     * {@code player.containerMenu} 赋成新菜单，所以同步 {@code closeContainer()} 会被覆盖掉。
     *
     * <p>豁免：本模组自己的登录 UI 菜单（{@link com.dsh.mclogin.ui.LoginUiMenu}）
     * 本就是在"未登录"状态下打开的，必须放行，否则兜底会把登录界面自己关掉。
     *
     * @param event 开容器事件
     */
    @SubscribeEvent
    public void onContainerOpen(final PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 本模组登录 UI 自己的菜单：放行（否则登录界面会被自己关掉）
        if (event.getContainer() instanceof com.dsh.mclogin.ui.LoginUiMenu) {
            return;
        }
        if (mod.getSessions().isLoggedIn(player.getUUID())) {
            return;
        }
        if (mod.getConfig().isAllowInteract()) {
            return;
        }
        final net.minecraft.server.MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        // 延后一 tick 再关：见上面注释（同步关会被 openMenu 的后续赋值覆盖）
        server.execute(() -> {
            if (player.isRemoved()) {
                return;
            }
            // 期间若已完成登录，就不该再关（例如免登录/异步验证刚回主线程）
            if (mod.getSessions().isLoggedIn(player.getUUID())) {
                return;
            }
            player.closeContainer();
        });
    }

    /**
     * 聊天限制。
     *
     * @param event 聊天事件
     */
    @SubscribeEvent
    public boolean onServerChat(final ServerChatEvent event) {
        boolean canceled = false;
        // ★ D3 修复（DSH 于 2026-09-27 实测真实编译发现）：
        // Forge 1.20.1 的 ServerChatEvent **覆写了 getPlayer()，返回类型协变为 ServerPlayer**，
        // 因此 `instanceof ServerPlayer` 模式匹配是冗余的，javac 直接报错：
        //   LoginRestrictionListener.java:301: 错误: 模式变量 ServerPlayer 是表达式类型 ServerPlayer 的子类型
        // 注意：此前的"桩件类型检查"（gen-stubs.ps1，JAVAC EXIT=0）**发现不了这条** ——
        // 因为桩件把 PlayerEvent#getPlayer() 统一声明成了 Player，反而让这种写法在桩件下"合法"。
        // 这正说明桩件只能证明工程内部自洽，不能替代真实编译。
        final ServerPlayer player = event.getPlayer();
        if (player == null) {
            return canceled;
        }
        if (mod.getSessions().isLoggedIn(player.getUUID())) {
            return canceled;
        }
        if (!mod.getConfig().isAllowChat()) {
            canceled = true;
            player.sendSystemMessage(com.dsh.mclogin.message.MessageService.component(
                "mclogin.restrict.chat"));
        }
    
        return canceled;
    }

    /**
     * 命令限制：未登录仅允许登录相关命令。
     *
     * @param event 命令事件
     */
    @SubscribeEvent
    public boolean onCommand(final CommandEvent event) {
        boolean canceled = false;
        final ParseResults<?> results = event.getParseResults();
        if (results == null) {
            return canceled;
        }
        final Object source = results.getContext() == null ? null : results.getContext().getSource();
        if (!(source instanceof net.minecraft.commands.CommandSourceStack stack)) {
            return canceled;
        }
        if (!(stack.getEntity() instanceof ServerPlayer player)) {
            return canceled;
        }
        if (mod.getSessions().isLoggedIn(player.getUUID())) {
            return canceled;
        }
        final String raw = results.getReader() == null ? "" : results.getReader().getString();
        // G8-7 ★禁止打印 raw：它含密码原文（如 "/login mypass"）。只允许 extractRoot(raw)
        // 取首 token；任何 debug/info 日志都不得输出 raw 本身，否则密码会进日志文件。
        final String root = extractRoot(raw);
        if (isAllowed(root)) {
            return canceled;
        }
        canceled = true;
        player.sendSystemMessage(com.dsh.mclogin.message.MessageService.component(
                "mclogin.restrict.command"));
    
        return canceled;
    }

    /**
     * 从原始输入中提取根命令名（去掉前导斜杠并取第一个 token）。
     *
     * @param raw 原始输入，例如 {@code "/tp Steve"}
     * @return 根命令名小写；空串表示解析不出
     */
    private static String extractRoot(final String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        final int space = text.indexOf(' ');
        if (space >= 0) {
            text = text.substring(0, space);
        }
        final int colon = text.indexOf(':');
        if (colon >= 0) {
            text = text.substring(colon + 1);
        }
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * 判断根命令是否在白名单内。
     *
     * @param root 根命令名（小写）
     * @return true 允许
     */
    private static boolean isAllowed(final String root) {
        for (final String allowed : ALLOWED_COMMANDS) {
            if (allowed.equals(root)) {
                return true;
            }
        }
        return false;
    }
}
