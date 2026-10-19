package com.dsh.mclogin.bukkit.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Synchronous notification after the state transition; never contains credentials. */
public final class AuthenticationEvent extends Event {
    public enum State { REQUIRED, AUTHENTICATED, LOGGED_OUT }
    public enum Reason { JOIN, REGISTER, LOGIN, LOGOUT, UNREGISTER, QUIT, SHUTDOWN }
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final State state;
    private final Reason reason;
    public AuthenticationEvent(Player player, State state, Reason reason) {
        this.player=player;this.state=state;this.reason=reason;
    }
    public Player getPlayer(){return player;}
    public State getState(){return state;}
    public Reason getReason(){return reason;}
    @Override public HandlerList getHandlers(){return HANDLERS;}
    public static HandlerList getHandlerList(){return HANDLERS;}
}
