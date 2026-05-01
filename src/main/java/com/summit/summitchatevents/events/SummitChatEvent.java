package com.summit.summitchatevents.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Custom plugin event fired after SummitChatEvents processes an incoming
 * player message.
 *
 * <p>Placeholder — fields, cancellability, and business logic will be
 * added once the event pipeline is designed.
 *
 * <p>Other plugins can listen to this event to react to processed chat
 * messages without coupling directly to Paper's own chat events.
 */
public final class SummitChatEvent extends Event {

    // Required by Bukkit's event system
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private String message;

    public SummitChatEvent(final Player player, final String message) {
        this.player = player;
        this.message = message;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public Player getPlayer() {
        return player;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(final String message) {
        this.message = message;
    }

    // -----------------------------------------------------------------------
    // Bukkit boilerplate
    // -----------------------------------------------------------------------

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
