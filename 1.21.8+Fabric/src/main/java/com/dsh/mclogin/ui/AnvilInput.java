package com.dsh.mclogin.ui;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.command.LoginCommands;
import com.dsh.mclogin.message.MessageService;
import com.dsh.mclogin.message.SoundService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 登录 UI 的<b>文字输入</b>通道：铁砧改名栏。
 *
 * <p>这是"原版客户端 + 纯服务端模组"下唯一可靠的文字输入方式：
 * 服务端开一个原版铁砧界面（{@code MenuType.ANVIL}），玩家在**改名栏**打字，
 * 客户端会发 {@code ServerboundRenameItemPacket}，服务端
 * {@code ServerGamePacketListenerImpl} 收到后调用
 * {@code player.containerMenu.setItemName(name)} —— 命中本类的覆写。
 * 已在 D-UI-1 真机证实（{@code ANVIL-INPUT text='...'}）。
 *
 * <p><b>★ 两个必须注意的实现细节（都是实测/字节码查实的，不是推测）</b>：
 * <ol>
 *   <li><b>{@code setItemName} 是"逐字回调"，不能当提交信号。</b>
 *       反汇编 {@code AnvilScreen} 可见 {@code onNameChanged(String)} 在每次输入值变化时
 *       都会发 {@code ServerboundRenameItemPacket}。若把它当提交，会提交半截密码。
 *       因此这里只<b>缓冲</b>最新文本，真正提交时机是玩家点击<b>输出格</b>。</li>
 *   <li><b>必须往输入格放一个占位物品</b>，否则铁砧输出格为空、
 *       客户端点击不会发出（空格的点击在客户端就被吞掉），玩家永远"提交"不了。
 *       这里放一张改名纸作为载体。</li>
 * </ol>
 *
 * <p>安全：缓冲的明文只在内存中，提交后立即取走并清空；<b>任何分支都不写日志</b>。
 */
public final class AnvilInput {

    /** 铁砧的输出格索引（0/1 = 两个输入格，2 = 输出格）。 */
    public static final int RESULT_SLOT = 2;

    /** 输入格索引。 */
    public static final int INPUT_SLOT = 0;

    private AnvilInput() {
    }

    /** 菜单提供者。 */
    public static final class Provider implements MenuProvider {
        private final MCLoginMod mod;
        private final UiSession.Stage stage;

        public Provider(final MCLoginMod mod, final UiSession.Stage stage) {
            this.mod = mod;
            this.stage = stage;
        }

        @Override
        public Component getDisplayName() {
            return UiSession.promptFor(stage);
        }

        @Override
        public AbstractContainerMenu createMenu(final int id, final Inventory inv, final Player player) {
            return new Menu(id, inv, mod, stage);
        }
    }

    /** 铁砧菜单：缓冲输入 + 点输出格提交。 */
    public static final class Menu extends AnvilMenu implements LoginUiMenu {
        private final MCLoginMod mod;
        private final UiSession.Stage stage;

        /** action bar 提示的刷新间隔（tick）：50 tick = 2.5 秒。 */
        private static final int HINT_REFRESH_TICKS = 50;

        /** 距下次刷新 action bar 提示的 tick 计数。 */
        private int hintTicks = 0;

        Menu(final int id, final Inventory inv, final MCLoginMod mod, final UiSession.Stage stage) {
            super(id, inv, ContainerLevelAccess.NULL);
            this.mod = mod;
            this.stage = stage;
            // R10-A：carrier 物品名必须为**空**。原方案把提示字写进物品名（"← 在这里输入"），
            // 改名栏会预填该文本，而铁砧光标在末尾且不自动全选——玩家直接打字会**追加**
            // 到提示字后面（"← 在这里输入abc"），服务端收到的就是混合文本（用户实测指出）。
            // 现在改名栏初始为空白，玩家输入的只有密码；提示字改走 action bar（物理隔离）。
            final ItemStack carrier = new ItemStack(Items.PAPER);
            carrier.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(""));
            this.inputSlots.setItem(INPUT_SLOT, carrier);
            this.createResult();
            // 打开即发一次 action bar 提示（后续由 stillValid 周期刷新）
            if (this.player instanceof ServerPlayer serverPlayer) {
                sendHint(serverPlayer);
            }
        }

        /**
         * R10-A：向 action bar 发送本阶段的输入提示。
         *
         * <p>action bar 显示在屏幕底部、不在改名栏内、不参与输入、不会被玩家打字覆盖
         * ——与密码物理隔离。用 {@code displayClientMessage(component, true)}（第二参
         * true = action bar 语义，等价于发 {@code ClientboundSetActionBarTextPacket}）。
         */
        private void sendHint(final ServerPlayer player) {
            player.displayClientMessage(hintFor(stage), true);
        }

        /** 该阶段对应的 action bar 提示键。 */
        private static Component hintFor(final UiSession.Stage stage) {
            switch (stage) {
                case REGISTER_INPUT:
                    return MessageService.component("mclogin.ui.anvil.hint.register");
                case REGISTER_CONFIRM:
                case RESET_CONFIRM:
                    return MessageService.component("mclogin.ui.anvil.hint.confirm");
                case LOGIN_INPUT:
                    return MessageService.component("mclogin.ui.anvil.hint.login");
                case RESET_INPUT:
                    return MessageService.component("mclogin.ui.anvil.hint.changepassword");
                default:
                    // 预留：未来若 UI 增加改密阶段（changepassword），走 hint.changepassword；
                    // 现阶段其余阶段不会打开铁砧，兜底给登录提示
                    return MessageService.component("mclogin.ui.anvil.hint.login");
            }
        }

        /**
         * 逐字回调：<b>只缓冲，不提交</b>。
         *
         * <p>刻意不调用 {@code super.setItemName(...)}：父类会把名字写到物品上并重算结果，
         * 而我们要的只是"读到玩家敲了什么"。不调用 super 也就不会让名字出现在任何
         * 可被他人看到的地方。
         *
         * @param name 客户端当前改名栏内容
         * @return 恒为 false（父类语义是"是否改变了物品名"）
         */
        @Override
        public boolean setItemName(final String name) {
            if (this.player instanceof ServerPlayer serverPlayer) {
                UiSession.of(serverPlayer.getUUID()).bufferText(name);
            }
            return false;
        }

        /** A stable submit button; never echo the password into item names or NBT. */
        @Override
        public void createResult() {
            ItemStack submit = new ItemStack(Items.LIME_DYE);
            submit.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, MessageService.component("mclogin.ui.anvil.submit"));
            this.resultSlots.setItem(0, submit);
            ((com.dsh.mclogin.mixin.AnvilMenuAccess) (Object) this).mclogin$getCost().set(0);
            this.broadcastChanges();
        }

        @Override
        protected boolean mayPickup(Player player, boolean hasStack) {
            return true; // clicked() consumes the action; nothing is actually taken.
        }

        /** 点击：输出格 = 提交；其余格子一律吞掉（防止玩家从界面里拿走/塞入物品）。 */
        @Override
        public void clicked(final int slotId, final int button, final ClickType type, final Player player) {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (slotId != RESULT_SLOT || type != ClickType.PICKUP || button != 0) {
                return;
            }
            SoundService.play(serverPlayer, SoundService.UI_CLICK);
            submit(serverPlayer);
        }

        /** 取走缓冲并交给与 {@code /login} {@code /register} 完全相同的入口。 */
        private void submit(final ServerPlayer player) {
            final UiSession ui = UiSession.of(player.getUUID());
            final String text = ui.takePendingText();
            // Capture cross-step text before closeContainer clears sensitive UI buffers.
            final String first = (stage == UiSession.Stage.REGISTER_CONFIRM || stage == UiSession.Stage.RESET_CONFIRM)
                    ? ui.takeRegisterFirstPass() : "";
            final MinecraftServer server = player.level().getServer();
            if (server == null) {
                return;
            }
            // 先关界面再处理：避免异步验证回来时界面还挂着、也避免物品同步问题
            player.closeContainer();

            if (text.isEmpty()) {
                SoundService.play(player, SoundService.LOGIN_FAILED);
                ui.setRegisterFirstPass(first);
                player.sendSystemMessage(MessageService.component("mclogin.ui.anvil.empty"));
                ui.openAnvil(mod, player, stage);
                return;
            }

            switch (stage) {
                case LOGIN_INPUT:
                    // 布防：异步验证若失败，LoginUiListener 会把主菜单还回来（否则玩家被晾住）
                    ui.armRetry();
                    LoginCommands.loginWithPassword(mod, player, server, text);
                    return;
                case REGISTER_INPUT:
                    // 第一步只暂存，不落任何状态；立刻进入确认步骤（无需布防，界面马上就回来）
                    ui.setRegisterFirstPass(text);
                    ui.openAnvil(mod, player, UiSession.Stage.REGISTER_CONFIRM);
                    return;
                case REGISTER_CONFIRM:
                    if (first.isEmpty()) {
                        // 会话丢了（例如中途断了/被清），退回第一步重来
                        player.sendSystemMessage(MessageService.component("mclogin.ui.anvil.empty"));
                        ui.openAnvil(mod, player, UiSession.Stage.REGISTER_INPUT);
                        return;
                    }
                    ui.armRetry();
                    if (LoginCommands.registerWithPassword(mod, player, server, first, text) == 0) {
                        SoundService.play(player, SoundService.LOGIN_FAILED);
                    }
                    return;
                case RESET_INPUT:
                    if (!mod.getPasswordReset().isAuthorized(player)) {
                        player.sendSystemMessage(MessageService.component("mclogin.reset.expired"));
                        ui.openMenu(mod, player);
                        return;
                    }
                    ui.setRegisterFirstPass(text);
                    ui.openAnvil(mod, player, UiSession.Stage.RESET_CONFIRM);
                    return;
                case RESET_CONFIRM:
                    mod.getPasswordReset().reset(player, first, text);
                    return;
                default:
                    // 其它阶段不该走到这里
            }
        }

        @Override
        public void removed(final Player player) {
            // The paper is a UI carrier, not a reward returned to the inventory.
            this.inputSlots.clearContent();
            super.removed(player);
            // R10-A：界面关闭（提交/主动关闭/断线）时清掉 action bar 提示，
            // 避免残留的"请输入密码"在登录后还挂在屏幕底部
            if (player instanceof ServerPlayer serverPlayer) {
                UiSession.of(serverPlayer.getUUID()).clearPendingText();
                serverPlayer.displayClientMessage(Component.empty(), true);
            }
        }

        /**
         * 每 tick 被服务器调用（{@code ServerPlayer.tick} 会检查开放菜单的 stillValid）。
         *
         * <p>本菜单恒有效（return true）；R10-A 借此驱动 action bar 提示的周期刷新
         * （每 {@link #HINT_REFRESH_TICKS} tick 重发一次，玩家不会忘记要做什么）。
         * action bar 本身几秒后自动淡出，重发即"持续显示"效果；菜单关闭后
         * {@link #removed} 已清屏，无残留。
         */
        @Override
        public boolean stillValid(final Player player) {
            if (++hintTicks >= HINT_REFRESH_TICKS) {
                hintTicks = 0;
                if (player instanceof ServerPlayer serverPlayer) {
                    sendHint(serverPlayer);
                }
            }
            return true;
        }
    }
}
