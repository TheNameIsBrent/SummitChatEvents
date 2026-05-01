package com.summit.summitchatevents.events.impl;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.events.ChatEvent;

/**
 * A chat event where players count upward in chat (1, 2, 3 …).
 *
 * <p>This is a scaffold — chat-listening logic will be added in a future
 * milestone once the listener pipeline is wired up.
 *
 * <p>Registered in {@link com.summit.summitchatevents.managers.EventManager}
 * under the key {@code "count"}.
 */
public final class CountUpEvent extends ChatEvent {

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public CountUpEvent(final SummitChatEventsPlugin plugin) {
        super(plugin, "Count Up");
    }

    // -----------------------------------------------------------------------
    // ChatEvent implementation
    // -----------------------------------------------------------------------

    @Override
    protected void onStart() {
        // TODO: broadcast start message to all online players
        // TODO: register a chat listener that validates sequential numbers
        // TODO: track current count and the last player who typed
        getPlugin().getLogger().info("[CountUpEvent] Started — waiting for players to count.");
    }

    @Override
    protected void onStop() {
        // TODO: broadcast stop message
        // TODO: unregister the chat listener
        // TODO: reset internal count state
        getPlugin().getLogger().info("[CountUpEvent] Stopped.");
    }
}
