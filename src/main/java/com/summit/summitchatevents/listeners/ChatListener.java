package com.summit.summitchatevents.listeners;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import org.bukkit.event.Listener;

/**
 * Listens for chat-related Bukkit/Paper events.
 *
 * <p>This class is a scaffold — event handler methods will be added in
 * future milestones once the event pipeline is designed.
 *
 * <p>To activate, uncomment the registration call in
 * {@link SummitChatEventsPlugin#registerListeners()}.
 */
public final class ChatListener implements Listener {

    @SuppressWarnings("unused")
    private final SummitChatEventsPlugin plugin;

    public ChatListener(final SummitChatEventsPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Future event handlers — examples:
    //
    // @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    // public void onAsyncChat(final AsyncChatEvent event) { ... }
    //
    // @EventHandler
    // public void onPlayerChat(final PlayerChatEvent event) { ... }
    // -----------------------------------------------------------------------
}
