package com.dsh.mclogin.listener;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.message.MessageService;
import com.dsh.mclogin.message.SoundService;
import com.dsh.mclogin.ui.LoginUiMenu;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.util.Locale;
import java.util.Set;

/** Server-side restrictions. Packet and entity mixins enforce actions before vanilla handles them. */
public final class LoginRestrictionListener {
    private static final Set<String> COMMANDS = Set.of("login", "register", "changepassword", "unregister", "mclogin");
    private final MCLoginMod mod;
    private int ticks;
    public LoginRestrictionListener(MCLoginMod mod) { this.mod = mod; }

    public static boolean unauthenticated(ServerPlayer player) {
        MCLoginMod mod = MCLoginMod.getInstance();
        return mod != null && !mod.getSessions().isLoggedIn(player.getUUID());
    }
    public static boolean blockInteraction(ServerPlayer player) {
        return unauthenticated(player) && !MCLoginMod.getInstance().getConfig().isAllowInteract();
    }
    public static boolean blockMovement(ServerPlayer player) {
        return unauthenticated(player) && !MCLoginMod.getInstance().getConfig().isAllowMove();
    }
    public static boolean blockChat(ServerPlayer player) {
        return unauthenticated(player) && !MCLoginMod.getInstance().getConfig().isAllowChat();
    }
    public static boolean blockCommand(ServerPlayer player, String input) {
        if (!unauthenticated(player)) return false;
        String command = input.strip();
        if (command.startsWith("/")) command = command.substring(1);
        int end = 0;
        while (end < command.length() && !Character.isWhitespace(command.charAt(end))) end++;
        String root = command.substring(0, end);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);
        return !COMMANDS.contains(root.toLowerCase(Locale.ROOT));
    }
    public void onServerTick(MinecraftServer server) {
        if (++ticks % 1200 == 0) mod.getGuard().purgeExpired();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) tick(player);
    }
    private void tick(ServerPlayer player) {
        var sessions = mod.getSessions();
        var uuid = player.getUUID();
        if (sessions.isLoggedIn(uuid)) return;
        int timeout = mod.getConfig().getLoginTimeoutSeconds();
        long elapsed = sessions.elapsedSinceJoinMillis(uuid);
        double[] frozen = sessions.getFrozenPosition(uuid);
        if (timeout > 0 && frozen != null && elapsed > timeout * 1000L) {
            player.connection.disconnect(MessageService.component("mclogin.timeout.kick", timeout));
            return;
        }
        if (timeout > 30 && frozen != null && elapsed >= (timeout - 30) * 1000L && sessions.markTimeoutWarnedIfFirst(uuid)) {
            SoundService.play(player, SoundService.TIMEOUT_WARNING);
            player.sendSystemMessage(MessageService.component("mclogin.timeout.warning", 30));
        }
        if (blockMovement(player) && frozen != null) {
            double dx = player.getX()-frozen[0], dy = player.getY()-frozen[1], dz = player.getZ()-frozen[2];
            if (dx*dx + dy*dy + dz*dz > 0.0001D) {
                player.connection.teleport(frozen[0], frozen[1], frozen[2], (float) frozen[3], (float) frozen[4]);
                player.setDeltaMovement(Vec3.ZERO);
            }
        }
        if (blockInteraction(player) && player.containerMenu != player.inventoryMenu && !(player.containerMenu instanceof LoginUiMenu)) player.closeContainer();
    }
}
