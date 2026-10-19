package com.dsh.mclogin.bukkit.api;

import com.dsh.mclogin.api.PasswordResetVerifier;
import java.util.UUID;

/** Published via Bukkit ServicesManager. Queries are thread-safe; provider changes require the main thread. */
public interface LoginService {
    boolean isAuthenticated(UUID playerId);
    boolean isRegistered(UUID playerId);
    /** At most one verifier; registration is revoked on owner disable. No authentication bypass API. */
    void registerResetVerifier(org.bukkit.plugin.Plugin owner, PasswordResetVerifier verifier);
    void unregisterResetVerifier(org.bukkit.plugin.Plugin owner);
}
