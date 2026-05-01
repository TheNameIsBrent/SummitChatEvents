package com.summit.summitchatevents.listeners;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.managers.EventManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Global chat listener registered permanently for the lifetime of the plugin.
 *
 * <p>Runs at {@link EventPriority#HIGH} so it sees messages after most other
 * plugins (e.g. chat-formatters) but before {@code HIGHEST}/{@code MONITOR}
 * listeners that only observe final state.
 *
 * <p>When an active {@link com.summit.summitchatevents.events.ChatEvent} is
 * running, per-event listeners (registered by the event itself) handle the
 * actual logic at {@link EventPriority#LOWEST}. This class provides a
 * centralised fallback guard — currently a no-op placeholder — so future
 * global chat rules (mute, cooldown, etc.) have a single home.
 */
public final class ChatListener implements Listener {

    private final SummitChatEventsPlugin plugin;

    public ChatListener(final SummitChatEventsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Global chat intercept. Individual event listeners registered by
     * {@link com.summit.summitchatevents.events.ChatEvent} subclasses run
     * first (LOWEST priority) and cancel the event themselves when active.
     * This handler runs afterwards at HIGH priority for any future global rules.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        final EventManager em = plugin.getEventManager();
        if (em == null || !em.isEventRunning()) {
            return; // No active Summit event — let chat pass through normally
        }

        // Active event chat listeners (LOWEST priority) already cancelled the
        // event. This handler is a hook point for future global-level rules.
        // Example: log chat attempts, rate-limit, apply global format, etc.
    }
}
