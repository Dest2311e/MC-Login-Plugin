package com.dsh.mclogin.ui;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.data.PlayerAccount;
import com.dsh.mclogin.data.YamlStorage;
import com.dsh.mclogin.message.MessageService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 登录 UI 的<b>主菜单</b>：一个 3 行箱子界面，格子里放"按钮物品"。
 *
 * <p>为什么用箱子：本模组 {@code side="SERVER"}，玩家用原版客户端。
 * 箱子（{@code MenuType.GENERIC_9x3}）是原版界面，服务端开、原版客户端直接渲染，
 * <b>不需要客户端 mod</b>；点击会回到服务端的 {@link AbstractContainerMenu#clicked}，
 * 即"服务端按钮"。已在 D-UI-1 真机证实（见 记录.txt 第二十八节 · DSH 侧）。
 *
 * <p><b>箱子 GUI 不能输入文字</b>，所以"输密码"交给铁砧界面（{@link AnvilInput}）。
 */
public final class MainMenu {

    /** 登录按钮槽位。 */
    public static final int SLOT_LOGIN = 11;
    /** 注册按钮槽位。 */
    public static final int SLOT_REGISTER = 13;
    /** 查看状态按钮槽位。 */
    public static final int SLOT_STATUS = 15;

    private MainMenu() {
    }

    /** 菜单提供者。 */
    public static final class Provider implements MenuProvider {
        private final MCLoginMod mod;

        public Provider(final MCLoginMod mod) {
            this.mod = mod;
        }

        @Override
        public Component getDisplayName() {
            return MessageService.component("mclogin.ui.menu.title");
        }

        @Override
        public AbstractContainerMenu createMenu(final int id, final Inventory inv, final Player player) {
            // 容器由 Provider 建立并交给菜单持有：Java 要求 super(...) 在字段赋值之前，
            // 所以不能在本类构造器里现建再存，改为外部建好传进来。
            return new Menu(id, inv, mod, new SimpleContainer(27));
        }
    }

    /** 箱子菜单：按钮点击全部在 {@link #clicked} 里处理。 */
    public static final class Menu extends ChestMenu implements LoginUiMenu {
        private final MCLoginMod mod;
        private final SimpleContainer container;

        Menu(final int id, final Inventory inv, final MCLoginMod mod, final SimpleContainer container) {
            super(MenuType.GENERIC_9x3, id, inv, container, 3);
            this.mod = mod;
            this.container = container;
            installButtons();
        }

        /** 放好三个按钮物品。 */
        private void installButtons() {
            container.setItem(SLOT_LOGIN, button(Items.OAK_DOOR, "mclogin.ui.button.login"));
            container.setItem(SLOT_REGISTER, button(Items.WRITABLE_BOOK, "mclogin.ui.button.register"));
            container.setItem(SLOT_STATUS, button(Items.PAPER, "mclogin.ui.button.status"));
        }

        private static ItemStack button(final Item item, final String key) {
            final ItemStack stack = new ItemStack(item);
            stack.setHoverName(MessageService.component(key));
            return stack;
        }

        @Override
        public void clicked(final int slotId, final int button, final ClickType type, final Player player) {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            // 已登录就不再走 UI 流程（幂等；例如登录在别处完成后菜单还开着）
            if (mod.getSessions().isLoggedIn(serverPlayer.getUUID())) {
                serverPlayer.closeContainer();
                return;
            }
            final UiSession ui = UiSession.of(serverPlayer.getUUID());
            switch (slotId) {
                case SLOT_LOGIN:
                    // 按钮语义：不调用 super，避免物品被拿走；直接切换到铁砧输入
                    ui.openAnvil(mod, serverPlayer, UiSession.Stage.LOGIN_INPUT);
                    return;
                case SLOT_REGISTER:
                    final YamlStorage storage = mod.getStorage();
                    if (storage.has(serverPlayer.getUUID())) {
                        serverPlayer.sendSystemMessage(
                                MessageService.component("mclogin.register.already"));
                        return;
                    }
                    ui.openAnvil(mod, serverPlayer, UiSession.Stage.REGISTER_INPUT);
                    return;
                case SLOT_STATUS:
                    showStatus(serverPlayer);
                    return;
                default:
                    // 其余格子只是占位，不允许取出/放入（保持界面干净）
                    return;
            }
        }

        /** 状态按钮：只回显"是否已注册 / 是否已登录 / 服务端语言"，不泄露敏感信息。 */
        private void showStatus(final ServerPlayer player) {
            final PlayerAccount account = mod.getStorage().get(player.getUUID());
            player.sendSystemMessage(MessageService.component("mclogin.ui.status.header"));
            player.sendSystemMessage(MessageService.component(
                    account == null ? "mclogin.ui.status.unregistered" : "mclogin.ui.status.registered"));
            player.sendSystemMessage(MessageService.component("mclogin.ui.status.hint"));
        }

        @Override
        public boolean stillValid(final Player player) {
            // 不绑定任何方块位置：这是服务端自建界面，玩家站哪都有效
            return true;
        }
    }
}
