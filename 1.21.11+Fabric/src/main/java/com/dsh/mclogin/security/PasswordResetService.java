package com.dsh.mclogin.security;

import com.dsh.mclogin.MCLoginMod;
import com.dsh.mclogin.api.PasswordResetVerifier;
import com.dsh.mclogin.command.LoginCommands;
import com.dsh.mclogin.data.PlayerAccount;
import com.dsh.mclogin.message.MessageService;
import com.dsh.mclogin.message.SoundService;
import com.dsh.mclogin.ui.UiSession;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;


import java.util.Objects;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** Optional recovery provider and server-owned authorization lifecycle. */
public final class PasswordResetService {
    private record Attempt(ServerPlayer player, PlayerAccount account,
                           PasswordResetVerifier verifier, String hash, String salt,
                           int iterations, int keyLength) { }

    private final MCLoginMod mod;
    private final ResetTickets<Attempt> tickets = new ResetTickets<>(
            System::nanoTime, TimeUnit.MINUTES.toNanos(2));
    private PasswordResetVerifier verifier;
    private final Map<UUID, Long> lastRequest = new HashMap<>();

    public PasswordResetService(MCLoginMod mod) { this.mod = mod; }

    /** Register during setup or on the server thread. One provider at a time. */
    public void registerVerifier(PasswordResetVerifier provider) {
        requireServerThread();
        Objects.requireNonNull(provider, "provider");
        if (verifier != null && verifier != provider) {
            throw new IllegalStateException("A password reset verifier is already registered");
        }
        verifier = provider;
    }

    /** Only the owning provider can unregister itself. Revokes all outstanding requests. */
    public void unregisterVerifier(PasswordResetVerifier provider) {
        requireServerThread();
        if (provider != null && verifier == provider) {
            verifier = null;
            clear();
        }
    }

    public boolean isAvailable() { return verifier != null && !mod.getStorage().isLastLoadSuspect(); }

    public boolean isPending(UUID uuid) {
        var ticket = tickets.get(uuid);
        return tickets.current(uuid, ticket) && !tickets.authorized(uuid, ticket);
    }

    public void begin(ServerPlayer player) {
        requireServerThread();
        tick();
        UUID uuid = player.getUUID();
        PlayerAccount account = mod.getStorage().get(uuid);
        if (!isAvailable() || account == null || mod.getSessions().isLoggedIn(uuid)) {
            tell(player, "mclogin.reset.unavailable");
            return;
        }
        long now = System.nanoTime();
        Long previous = lastRequest.get(uuid);
        if (previous != null && now - previous < TimeUnit.SECONDS.toNanos(10)) {
            tell(player, "mclogin.busy");
            return;
        }
        Attempt attempt = new Attempt(player, account, verifier, account.getHash(), account.getSalt(),
                account.getIterations(), account.getKeyLength());
        ResetTickets.Ticket<Attempt> ticket = tickets.begin(uuid, attempt);
        if (ticket == null) {
            tell(player, "mclogin.reset.pending");
            return;
        }
        lastRequest.put(uuid, now);
        UiSession.of(uuid).clearPendingText();
        player.closeContainer();
        tell(player, "mclogin.reset.verify");
        MinecraftServer server = player.level().getServer();
        try {
            CompletionStage<Boolean> result = Objects.requireNonNull(
                    attempt.verifier().verify(uuid, ticket.requestId()), "verification result");
            result.whenComplete((approved, error) -> server.execute(() -> {
                if (!valid(uuid, ticket)) {
                    finish(uuid, ticket);
                    return;
                }
                if (error != null || !Boolean.TRUE.equals(approved) || !tickets.authorize(uuid, ticket)) {
                    finish(uuid, ticket);
                    tell(player, "mclogin.reset.denied");
                    UiSession.of(uuid).openMenu(mod, player);
                    return;
                }
                tell(player, "mclogin.reset.verified");
                SoundService.play(player, SoundService.LOGIN_SUCCESS);
                UiSession.of(uuid).openAnvil(mod, player, UiSession.Stage.RESET_INPUT);
            }));
        } catch (RuntimeException ex) {
            finish(uuid, ticket);
            tell(player, "mclogin.reset.denied");
            UiSession.of(uuid).openMenu(mod, player);
            MCLoginMod.LOGGER.warn("[MCLogin] Password reset verifier failed for request {}", ticket.requestId());
        }
    }

    public boolean isAuthorized(ServerPlayer player) {
        ResetTickets.Ticket<Attempt> ticket = tickets.get(player.getUUID());
        return valid(player.getUUID(), ticket) && tickets.authorized(player.getUUID(), ticket);
    }

    public void reset(ServerPlayer player, String password, String confirm) {
        requireServerThread();
        UUID uuid = player.getUUID();
        ResetTickets.Ticket<Attempt> ticket = tickets.get(uuid);
        if (!isAuthorized(player)) {
            tell(player, "mclogin.reset.expired");
            UiSession.of(uuid).openMenu(mod, player);
            return;
        }
        Component policy = LoginCommands.passwordPolicyError(password, mod.getConfig());
        if (!Objects.equals(password, confirm) || policy != null) {
            SoundService.play(player, SoundService.LOGIN_FAILED);
            player.sendSystemMessage(policy != null ? policy : MessageService.component("mclogin.register.mismatch"));
            UiSession.of(uuid).openAnvil(mod, player, UiSession.Stage.RESET_INPUT);
            return;
        }
        if (!tickets.consume(uuid, ticket)) return;
        int iterations = mod.getConfig().getPbkdf2Iterations();
        MinecraftServer server = player.level().getServer();
        boolean submitted = mod.getHashExecutor().submit(uuid, () -> {
            try {
                String salt = mod.getHasher().generateSalt();
                String hash = mod.getHasher().hash(password, salt, iterations, PasswordHasher.DEFAULT_KEY_LENGTH);
                server.execute(() -> {
                    if (!valid(uuid, ticket)) {
                        finish(uuid, ticket);
                        return;
                    }
                    PlayerAccount account = ticket.context().account();
                    mod.getHasher().applyPrecomputed(account, salt, hash, iterations, PasswordHasher.DEFAULT_KEY_LENGTH);
                    account.setLastLoginAtMillis(0L);
                    mod.getGuard().reset(uuid, player.getIpAddress());
                    mod.getStorage().markDirty();
                    finish(uuid, ticket);
                    tell(player, "mclogin.reset.success");
                    UiSession.of(uuid).openAnvil(mod, player, UiSession.Stage.LOGIN_INPUT);
                });
            } catch (RuntimeException ex) {
                server.execute(() -> {
                    if (tickets.get(uuid) != ticket) return;
                    finish(uuid, ticket);
                    if (connected(player)) {
                        tell(player, "mclogin.internal_error");
                        UiSession.of(uuid).openMenu(mod, player);
                    }
                });
            }
        });
        if (!submitted) {
            finish(uuid, ticket);
            tell(player, "mclogin.busy");
            UiSession.of(uuid).openMenu(mod, player);
        } else {
            tell(player, "mclogin.verifying");
        }
    }

    private boolean valid(UUID uuid, ResetTickets.Ticket<Attempt> ticket) {
        if (!tickets.current(uuid, ticket)) return false;
        Attempt a = ticket.context();
        return isAvailable() && verifier == a.verifier() && connected(a.player())
                && !mod.getSessions().isLoggedIn(uuid) && mod.getStorage().get(uuid) == a.account()
                && Objects.equals(a.hash(), a.account().getHash())
                && Objects.equals(a.salt(), a.account().getSalt())
                && a.iterations() == a.account().getIterations() && a.keyLength() == a.account().getKeyLength();
    }

    public void tick() {
        for (var entry : tickets.snapshot().entrySet()) {
            if (!valid(entry.getKey(), entry.getValue())) {
                ServerPlayer player = entry.getValue().context().player();
                finish(entry.getKey(), entry.getValue());
                if (connected(player) && player.isAlive() && !mod.getSessions().isLoggedIn(entry.getKey())) {
                    tell(player, "mclogin.reset.expired");
                    UiSession.of(entry.getKey()).openMenu(mod, player);
                }
            }
        }
    }

    public void discard(UUID uuid) {
        lastRequest.remove(uuid);
        ResetTickets.Ticket<Attempt> ticket = tickets.get(uuid);
        if (ticket != null) finish(uuid, ticket);
    }

    public void clear() {
        lastRequest.clear();
        for (var entry : tickets.snapshot().entrySet()) finish(entry.getKey(), entry.getValue());
    }

    private void finish(UUID uuid, ResetTickets.Ticket<Attempt> ticket) {
        if (ticket == null || !tickets.remove(uuid, ticket)) return;
        UiSession.of(uuid).clearPendingText();
        try { ticket.context().verifier().cancel(ticket.requestId()); }
        catch (RuntimeException ex) {
            MCLoginMod.LOGGER.warn("[MCLogin] Password reset cleanup failed for request {}", ticket.requestId());
        }
    }

    private static boolean connected(ServerPlayer player) {
        return player.level().getServer() != null && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && !player.hasDisconnected();
    }

    private static void tell(ServerPlayer player, String key) {
        player.sendSystemMessage(MessageService.component(key));
        if (key.equals("mclogin.reset.denied") || key.equals("mclogin.reset.expired")) {
            SoundService.play(player, SoundService.LOGIN_FAILED);
        } else if (key.equals("mclogin.reset.success")) {
            SoundService.play(player, SoundService.REGISTER_SUCCESS);
        }
    }

    private static void requireServerThread() {
        MinecraftServer server = MCLoginMod.getInstance().getServer();
        if (server != null && !server.isSameThread()) throw new IllegalStateException("Use the server thread");
    }
}
