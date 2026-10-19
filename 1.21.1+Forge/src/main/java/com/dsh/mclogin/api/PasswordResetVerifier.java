package com.dsh.mclogin.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Optional, trusted server-side account-recovery integration.
 * Implementations must return promptly and verify ownership through a previously
 * bound external identity (for example a bound email). UUID/name/IP alone is not proof.
 * Called on the server thread; the result may complete on any thread. Only Boolean.TRUE
 * authorizes this exact request. Never put passwords in this interface.
 */
public interface PasswordResetVerifier {
    CompletionStage<Boolean> verify(UUID playerId, UUID requestId);

    /** Nonblocking cleanup for expired, disconnected or completed requests. */
    default void cancel(UUID requestId) { }
}
