package com.dsh.mclogin;

import com.dsh.mclogin.command.LoginCommands;
import com.dsh.mclogin.config.LoginConfig;
import com.dsh.mclogin.data.YamlStorage;
import com.dsh.mclogin.listener.LoginRestrictionListener;
import com.dsh.mclogin.listener.PlayerConnectionListener;
import com.dsh.mclogin.security.*;
import com.dsh.mclogin.session.SessionManager;
import com.dsh.mclogin.ui.LoginUiListener;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** Fabric lifecycle adapter. Authentication, UI and persistence use the original services. */
public final class MCLoginMod implements DedicatedServerModInitializer {
    public static final String MOD_ID = "mclogin";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static MCLoginMod instance;
    private MinecraftServer server;
    private LoginConfig config = new LoginConfig();
    private YamlStorage storage = new YamlStorage();
    private SessionManager sessions = new SessionManager();
    private PasswordHasher hasher = new PasswordHasher();
    private BruteForceGuard guard = new BruteForceGuard();
    private HashExecutor hashExecutor = new HashExecutor();
    private PasswordResetService passwordReset = new PasswordResetService(this);

    @Override
    public void onInitializeServer() {
        instance = this;
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> LoginCommands.register(dispatcher, this));
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> !LoginRestrictionListener.blockChat(sender));
        PlayerConnectionListener connections = new PlayerConnectionListener(this);
        LoginUiListener ui = new LoginUiListener(this);
        LoginRestrictionListener restrictions = new LoginRestrictionListener(this);
        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            config.register();
            storage.init(FabricLoader.getInstance().getConfigDir());
            storage.load();
            com.dsh.mclogin.message.MessageService.ensureLanguage(config.getLanguage());
            LOGGER.info("[MCLogin] Fabric: loaded {} accounts; async PBKDF2 iterations={}", storage.getAccountCount(), config.getPbkdf2Iterations());
            if (!s.usesAuthentication() && config.isEnableSessionAutoLogin()) {
                LOGGER.warn("[MCLogin] online-mode=false: session auto-login is disabled.");
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            connections.onPlayerLoggedIn(handler.player);
            ui.onPlayerLoggedIn(handler.player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            connections.onPlayerLoggedOut(handler.player);
            ui.onPlayerLoggedOut(handler.player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> connections.onPlayerRespawn(newPlayer));
        ServerTickEvents.START_SERVER_TICK.register(restrictions::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(ui::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            passwordReset.clear();
            sessions.clear();
            guard.clearIpStates();
            hashExecutor.shutdown();
            if (storage.shutdown()) LOGGER.info("[MCLogin] Account data saved.");
            else LOGGER.error("[MCLogin] Account data could not be saved; inspect accounts.yml.failed-*.");
            server = null;
        });
    }

    public static MCLoginMod getInstance() { return instance; }
    public MinecraftServer getServer() { return server; }
    public LoginConfig getConfig() { return config; }
    public YamlStorage getStorage() { return storage; }
    public SessionManager getSessions() { return sessions; }
    public PasswordHasher getHasher() { return hasher; }
    public BruteForceGuard getGuard() { return guard; }
    public HashExecutor getHashExecutor() { return hashExecutor; }
    public PasswordResetService getPasswordReset() { return passwordReset; }
}
