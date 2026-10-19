package com.dsh.mclogin.security;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Server-thread-only, expiring, single-use recovery authorizations. */
public final class ResetTickets<C> {
    private enum Phase { VERIFYING, AUTHORIZED, HASHING }

    public static final class Ticket<C> {
        private final UUID requestId = UUID.randomUUID();
        private final C context;
        private long expires;
        private Phase phase = Phase.VERIFYING;
        private Ticket(C context, long expires) { this.context = context; this.expires = expires; }
        public UUID requestId() { return requestId; }
        public C context() { return context; }
    }

    private final Map<UUID, Ticket<C>> tickets = new HashMap<>();
    private final LongSupplier clock;
    private final long ttl;

    public ResetTickets(LongSupplier clock, long ttl) {
        if (ttl <= 0) throw new IllegalArgumentException("ttl must be positive");
        this.clock = clock;
        this.ttl = ttl;
    }

    public Ticket<C> begin(UUID playerId, C context) {
        if (tickets.containsKey(playerId)) return null;
        Ticket<C> ticket = new Ticket<>(context, clock.getAsLong() + ttl);
        tickets.put(playerId, ticket);
        return ticket;
    }

    public Ticket<C> get(UUID playerId) { return tickets.get(playerId); }

    public boolean current(UUID playerId, Ticket<C> ticket) {
        return ticket != null && tickets.get(playerId) == ticket
                && clock.getAsLong() - ticket.expires < 0;
    }

    public boolean authorize(UUID playerId, Ticket<C> ticket) {
        if (!current(playerId, ticket) || ticket.phase != Phase.VERIFYING) return false;
        ticket.phase = Phase.AUTHORIZED;
        ticket.expires = clock.getAsLong() + ttl;
        return true;
    }

    public boolean authorized(UUID playerId, Ticket<C> ticket) {
        return current(playerId, ticket) && ticket.phase == Phase.AUTHORIZED;
    }

    public boolean consume(UUID playerId, Ticket<C> ticket) {
        if (!authorized(playerId, ticket)) return false;
        ticket.phase = Phase.HASHING;
        return true;
    }

    public boolean remove(UUID playerId, Ticket<C> ticket) {
        return tickets.remove(playerId, ticket);
    }

    public Map<UUID, Ticket<C>> snapshot() { return Map.copyOf(tickets); }
}
