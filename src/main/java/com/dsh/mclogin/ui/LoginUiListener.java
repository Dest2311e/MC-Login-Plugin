package com.dsh.mclogin.ui;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.message.MessageService;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录 UI 的<b>驱动与状态同步</b>（R8 / D-UI-2，DSH 侧实现）。
 *
 * <p>设计原则：<b>零耦合</b>。本监听器不修改、也不依赖认证流程的内部结构，
 * 只做两件事：
 * <ol>
 *   <li>玩家进服后<b>延迟一小段</b>再检查登录态，若未登录则开 UI（延迟是为了让
 *       U1 的会话免登录有机会先跑完，避免对已自动放行的玩家弹界面）；</li>
 *   <li>每 20 tick <b>轮询</b>一次登录态：已登录 → 撤掉未登录提示血条；
 *       未登录 → 保证血条在。这样"登录成功"不需要任何人来通知本类。</li>
 * </ol>
 *
 * <p>安全：界面只是<b>便利</b>，未登录限制由既有的 {@code LoginRestrictionListener}
 * 独立强制执行 —— <b>关掉界面（按 Esc）不会绕过任何限制</b>，
 * 因为本类从不参与"是否放行"的判断。
 */
public final class LoginUiListener {

    /** 进服后延迟多少 tick 再弹 UI（20 tick = 1 秒）。 */
    private static final int OPEN_DELAY_TICKS = 20;

    /** 登录态轮询周期（tick）。 */
    private static final int POLL_INTERVAL_TICKS = 10;

    private final MCLoginMod mod;

    /** 待开界面的倒计时（uuid -> 剩余 tick）。 */
    private final Map<UUID, Integer> pendingOpen = new ConcurrentHashMap<>();

    /** 距离上次轮询过了多少 tick。 */
    private int tickCounter = 0;

    public LoginUiListener(final MCLoginMod mod) {
        this.mod = mod;
    }

    // ==================== 进服 / 离服 ====================

    @SubscribeEvent
    public void onPlayerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!mod.getConfig().isEnableLoginUi()) {
            return;
        }
        pendingOpen.put(player.getUUID(), OPEN_DELAY_TICKS);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        final UUID uuid = player.getUUID();
        pendingOpen.remove(uuid);
        mod.getPasswordReset().discard(uuid);
        UiSession.discard(uuid);
    }

    // ==================== 每 tick 驱动 ====================

    @SubscribeEvent
    public void onServerTick(final TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        tickCounter++;
        mod.getPasswordReset().tick();

        // 1) 待开界面倒计时
        if (!pendingOpen.isEmpty()) {
            final Iterator<Map.Entry<UUID, Integer>> it = pendingOpen.entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<UUID, Integer> e = it.next();
                final int left = e.getValue() - 1;
                if (left > 0) {
                    e.setValue(left);
                    continue;
                }
                it.remove();
                final ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
                if (player != null) {
                    openIfStillLoggedOut(player);
                }
            }
        }

        // 2) 登录态轮询：同步血条 + 登录成功后收尾
        if (tickCounter % POLL_INTERVAL_TICKS != 0) {
            return;
        }
        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncFor(player);
        }
    }

    /** 玩家仍未登录 → 提示；已登录 → 撤掉一切未登录界面。 */
    private void syncFor(final ServerPlayer player) {
        final UUID uuid = player.getUUID();
        final boolean loggedIn = mod.getSessions().isLoggedIn(uuid);
        if (loggedIn) {
            final UiSession ui = UiSession.of(uuid);
            if (ui.getStage() != UiSession.Stage.NONE) {
                ui.setStage(UiSession.Stage.NONE);
                ui.clearPendingText();
                if (player.containerMenu instanceof LoginUiMenu) player.closeContainer();
            }
            // 血条不在此处关：hideBossBar 幂等，重复调用无副作用
            ui.hideBossBar();
            return;
        }
        final UiSession ui = UiSession.of(uuid);
        if (!mod.getConfig().isEnableLoginUi()) {
            return;
        }
        if (!player.isAlive() || player.hasDisconnected() || pendingOpen.containsKey(uuid)) return;
        ui.showBossBar(player);
        // Closing the UI never dismisses authentication. Reopen without repeated sounds.
        if (!(player.containerMenu instanceof LoginUiMenu)) {
            if (mod.getPasswordReset().isAuthorized(player)) {
                ui.openAnvil(mod, player, UiSession.Stage.RESET_INPUT);
            } else {
                ui.openMenu(mod, player);
            }
        }
    }

    /** 延迟到点后再次确认"仍未登录"才开界面。 */
    private void openIfStillLoggedOut(final ServerPlayer player) {
        if (mod.getSessions().isLoggedIn(player.getUUID())) {
            return;
        }
        final UiSession ui = UiSession.of(player.getUUID());
        if (!player.isAlive()) return;
        ui.showBossBar(player);
        // 标题 + 动作栏：比聊天栏一行字醒目（纯展示包，原版客户端直接可见）
        player.connection.send(new ClientboundSetTitleTextPacket(
                MessageService.component(mod.getStorage().isLastLoadSuspect() ? "mclogin.ui.title.unavailable"
                        : mod.getStorage().has(player.getUUID())
                        ? "mclogin.ui.title.hint" : "mclogin.ui.title.register")));
        player.connection.send(new ClientboundSetActionBarTextPacket(
                MessageService.component(mod.getStorage().isLastLoadSuspect() ? "mclogin.storage.unavailable"
                        : mod.getStorage().has(player.getUUID()) ? "mclogin.join.login" : "mclogin.join.register")));
        ui.openMenu(mod, player);
    }
}
