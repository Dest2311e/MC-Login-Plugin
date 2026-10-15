package com.dsh.mclogin.ui;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.message.MessageService;
import com.dsh.mclogin.message.SoundService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录 UI 的**每玩家会话状态**（R8 / D-UI-2，DSH 侧实现）。
 *
 * <p>职责边界：本类只管"UI 侧的状态"，<b>不参与任何认证判断</b>。
 * 是否已登录一律以 {@code SessionManager} 为准；本类只是界面的记账本。
 *
 * <p>安全约定：
 * <ul>
 *   <li>{@link #pendingText} 只在内存中短暂保存玩家在铁砧里敲入的内容，
 *       提交后<b>立即清除</b>；<b>任何情况下都不写日志</b>（连长度都不写，
 *       避免长度侧信道）。</li>
 *   <li>本类持有的 Boss 血条在玩家登出/登录成功/断线时一律移除，不留残留界面。</li>
 * </ul>
 */
public final class UiSession {

    /** UI 流程所处阶段。 */
    public enum Stage {
        /** 没有进行中的 UI 流程。 */
        NONE,
        /** 已展示主菜单箱子界面。 */
        MENU,
        /** 正在用铁砧采集"登录密码"。 */
        LOGIN_INPUT,
        /** 正在用铁砧采集"注册密码"（第一步）。 */
        REGISTER_INPUT,
        /** 正在用铁砧采集"确认密码"（第二步）。 */
        REGISTER_CONFIRM,
        RESET_INPUT,
        RESET_CONFIRM
    }

    private static final Map<UUID, UiSession> SESSIONS = new ConcurrentHashMap<>();

    /** 取（或建立）某玩家的 UI 会话。 */
    public static UiSession of(final UUID uuid) {
        return SESSIONS.computeIfAbsent(uuid, k -> new UiSession());
    }

    /** 丢弃某玩家的 UI 会话（含 Boss 血条）。 */
    public static void discard(final UUID uuid) {
        final UiSession s = SESSIONS.remove(uuid);
        if (s != null) {
            s.clearPendingText();
            s.hideBossBar();
        }
    }

    private Stage stage = Stage.NONE;

    /**
     * 铁砧里"最近一次收到的输入"。
     *
     * <p><b>为什么是缓冲而不是提交</b>：原版客户端 {@code AnvilScreen.onNameChanged} 在
     * <b>每一次按键</b>都会发 {@code ServerboundRenameItemPacket}，所以服务端的
     * {@code setItemName} 是"逐字回调"。若把它当提交信号，会提交半截密码。
     * 正确做法：这里只缓冲最新值，真正的提交时机是玩家点击<b>输出格（slot 2）</b>。
     */
    private String pendingText = "";

    /** 注册流程第一步暂存的密码（第二步确认后才会使用，用完即清）。 */
    private String registerFirstPass = "";

    /** 该玩家当前的未登录提示血条；null = 未展示。 */
    private ServerBossEvent bossBar = null;

    /** 血条当前挂在哪位玩家身上（BossEvent 不提供 getPlayers()，故自行记账）。 */
    private ServerPlayer bossBarOwner = null;

    /**
     * "提交后若仍失败就把界面还回来"的延迟计数。
     *
     * <p>-1 = 未布防。<b>为什么需要它</b>：UI 提交是异步的（PBKDF2 走池），
     * 失败时不会有任何人来通知 UI；若不补偿，玩家提交错一次后界面就没了，
     * 只能改用命令 —— 这是真机测试抓到的体验缺陷。
     * 延迟若干次轮询（每次 20 tick）再重开，是为了等异步结果先回来，
     * 避免"登录其实成功了却又弹一个菜单"。
     */
    private int retryPolls = -1;

    private UiSession() {
    }

    public Stage getStage() {
        return stage;
    }

    public void setStage(final Stage stage) {
        this.stage = stage == null ? Stage.NONE : stage;
    }

    /** 记录一次铁砧输入（逐字回调，仅缓冲）。 */
    public void bufferText(final String text) {
        this.pendingText = text == null ? "" : text;
    }

    /** 取出并清空缓冲。 */
    public String takePendingText() {
        final String t = pendingText;
        pendingText = "";
        return t == null ? "" : t;
    }

    /** 清空"逐字缓冲"（只清本步骤的输入缓冲，<b>不动</b>跨步骤暂存的注册首密码）。 */
    public void clearInputBuffer() {
        pendingText = "";
    }

    /** 清空全部临时明文（输入缓冲 + 注册首密码）——会话结束/登出时用。 */
    public void clearPendingText() {
        pendingText = "";
        registerFirstPass = "";
        retryPolls = -1;
    }

    // ==================== 提交失败后把界面还回来 ====================

    /** 布防：提交给认证入口后调用；若之后仍未登录，界面会自动重开。 */
    public void armRetry() {
        retryPolls = 2;
    }

    public void disarmRetry() {
        retryPolls = -1;
    }

    /**
     * 轮询一次：到点了就返回 true（调用方负责重开界面）。
     *
     * @return true = 现在应该把主菜单还给玩家
     */
    public boolean retryDue() {
        if (retryPolls < 0) {
            return false;
        }
        if (retryPolls > 0) {
            retryPolls--;
            return false;
        }
        retryPolls = -1;
        return true;
    }

    public void setRegisterFirstPass(final String password) {
        this.registerFirstPass = password == null ? "" : password;
    }

    /** 取出并清空"注册第一步"暂存的密码。 */
    public String takeRegisterFirstPass() {
        final String p = registerFirstPass;
        registerFirstPass = "";
        return p == null ? "" : p;
    }

    // ==================== 未登录提示血条 ====================

    /** 展示/刷新"未登录"提示血条（幂等）。 */
    public void showBossBar(final ServerPlayer player) {
        MCLoginMod mod = MCLoginMod.getInstance();
        if (bossBar == null) {
            bossBar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.BLUE,
                    BossEvent.BossBarOverlay.PROGRESS);
            bossBar.addPlayer(player);
            bossBarOwner = player;
        }
        String action = mod.getStorage().isLastLoadSuspect() ? "mclogin.ui.boss.unavailable"
                : mod.getPasswordReset().isPending(player.getUUID()) ? "mclogin.ui.boss.verify"
                : mod.getPasswordReset().isAuthorized(player) ? "mclogin.ui.boss.reset"
                : mod.getStorage().has(player.getUUID()) ? "mclogin.ui.boss.login" : "mclogin.ui.boss.register";
        int timeout = mod.getConfig().getLoginTimeoutSeconds();
        long remaining = Math.max(0, timeout - mod.getSessions().elapsedSinceJoinMillis(player.getUUID()) / 1000);
        Component name = MessageService.component(action);
        if (timeout > 0) name = name.copy().append(MessageService.component("mclogin.ui.boss.remaining", remaining));
        bossBar.setName(name);
        bossBar.setProgress(timeout <= 0 ? 1F : Math.min(1F, (float) remaining / timeout));
        bossBar.setColor(timeout > 0 && remaining <= 30 ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.BLUE);
    }

    /** 移除提示血条（幂等）。 */
    public void hideBossBar() {
        final ServerBossEvent bar = this.bossBar;
        final ServerPlayer owner = this.bossBarOwner;
        this.bossBar = null;
        this.bossBarOwner = null;
        if (bar != null && owner != null) {
            bar.removePlayer(owner);
        }
    }

    // ==================== 便捷动作 ====================

    /** 打开主菜单（箱子 GUI）。 */
    public void openMenu(final MCLoginMod mod, final ServerPlayer player) {
        clearPendingText();
        setStage(Stage.MENU);
        player.openMenu(new MainMenu.Provider(mod));
    }

    /**
     * 打开铁砧输入界面。
     *
     * @param mod   模组实例
     * @param player 目标玩家
     * @param stage  {@link Stage#LOGIN_INPUT} 或 {@link Stage#REGISTER_INPUT}/{@code REGISTER_CONFIRM}
     */
    public void openAnvil(final MCLoginMod mod, final ServerPlayer player, final Stage stage) {
        setStage(stage);
        // ★ 只清"本步骤的逐字缓冲"，绝不能清 registerFirstPass ——
        //   注册流程从 REGISTER_INPUT 走到 REGISTER_CONFIRM 时正要靠它带着首密码，
        //   早先版本这里调的是 clearPendingText()（两者都清），导致确认步骤永远读到空、
        //   在真机上表现为"确认后又被退回第一步"（2026-09-27 实测抓到并修复）。
        clearInputBuffer();
        player.openMenu(new AnvilInput.Provider(mod, stage));
        player.sendSystemMessage(promptFor(stage));
    }

    /** 该阶段对应的"请在这里输入"提示文案。 */
    public static Component promptFor(final Stage stage) {
        switch (stage) {
            case LOGIN_INPUT:
                return MessageService.component("mclogin.ui.anvil.login_prompt");
            case REGISTER_INPUT:
                return MessageService.component("mclogin.ui.anvil.register_prompt");
            case REGISTER_CONFIRM:
                return MessageService.component("mclogin.ui.anvil.confirm_prompt");
            case RESET_INPUT:
                return MessageService.component("mclogin.ui.anvil.reset_prompt");
            case RESET_CONFIRM:
                return MessageService.component("mclogin.ui.anvil.confirm_prompt");
            default:
                return MessageService.component("mclogin.ui.anvil.generic_prompt");
        }
    }
}
