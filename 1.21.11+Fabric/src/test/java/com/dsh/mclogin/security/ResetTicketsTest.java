package com.dsh.mclogin.security;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Dependency-free security regression checks, run by Gradle securityChecks. */
public final class ResetTicketsTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        AtomicLong now = new AtomicLong(1000);
        ResetTickets<Object> tickets = new ResetTickets<>(now::get, 100);
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        Object connection = new Object();
        var a = tickets.begin(alice, connection);
        check(a.context() == connection, "bind to original context");
        check(!tickets.consume(alice, a), "cannot reset before approval");
        check(!tickets.authorize(bob, a), "cannot approve another UUID");
        check(tickets.begin(alice, new Object()) == null, "deduplicate pending verification");
        check(tickets.authorize(alice, a), "accept valid approval");
        check(!tickets.authorize(alice, a), "reject duplicate approval");
        check(tickets.consume(alice, a), "consume authorization once");
        check(!tickets.consume(alice, a), "reject replay during hashing");
        check(tickets.current(alice, a), "allow in-flight result for same request");
        check(tickets.remove(alice, a), "disconnect revokes request");
        var replacement = tickets.begin(alice, new Object());
        check(!replacement.requestId().equals(a.requestId()), "new connection gets fresh request ID");
        check(!tickets.authorize(alice, a), "late approval cannot authorize reconnect");
        check(!tickets.remove(alice, a), "old callback cannot remove new request");
        check(tickets.current(alice, replacement), "new request survives old callback");
        now.addAndGet(100);
        check(!tickets.authorize(alice, replacement), "reject verification at expiry boundary");
        tickets.remove(alice, replacement);
        var b = tickets.begin(alice, connection);
        now.addAndGet(90);
        check(tickets.authorize(alice, b), "verification before deadline succeeds");
        now.addAndGet(99);
        check(tickets.authorized(alice, b), "fresh password-entry deadline");
        now.incrementAndGet();
        check(!tickets.consume(alice, b), "expired password-entry grant cannot reset");
        tickets.remove(alice, b);
        var c = tickets.begin(alice, connection);
        tickets.authorize(alice, c);
        tickets.consume(alice, c);
        now.addAndGet(100);
        check(!tickets.current(alice, c), "hash result after expiry must be rejected");
        for (var entry : tickets.snapshot().entrySet()) tickets.remove(entry.getKey(), entry.getValue());
        check(!tickets.current(alice, c), "provider removal revokes in-flight result");
        System.out.println("ResetTickets: " + checks + " checks passed");
        StorageRegressionTest.run();
    }
}
