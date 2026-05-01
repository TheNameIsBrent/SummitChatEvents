package com.summit.summitchatevents.listeners;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.managers.EventManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Global chat gate — runs at {@link EventPriority#HIGHEST} with
 * {@code ignoreCancelled = false} so it fires regardless of what any
 * other plugin has done.
 *
 * <p>While a Summit event is active this listener cancels every message
 * that has not already been approved by {@code CountUpEvent}'s own handler
 * (which runs at LOWEST and, for winning submissions, marks the event
 * un-cancelled). By running at HIGHEST we stomp over alert plugins, raffle
 * plugins, chat formatters — anything that runs at NORMAL or HIGH.
 *
 * <p>When no Summit event is running this handler is a no-op.
 */
public final class ChatListener implements Listener {

    private final SummitChatEventsPlugin plugin;

    public ChatListener(final SummitChatEventsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        final EventManager em = plugin.getEventManager();
        if (em == null || !em.isEventRunning()) {
            return; // Nothing active — normal chat
        }

        // If the LOWEST-priority CountUpEvent handler approved this message
        // (winning number from an eligible player) it set cancelled=false.
        // Any other message — including those from alert/raffle plugins that
        // were un-cancelled at NORMAL/HIGH — gets cancelled here.
        if (!event.isCancelled()) {
            // This message was explicitly approved by CountUpEvent — leave it.
            return;
        }

        // Everything else stays cancelled.
        event.setCancelled(true);
    }
}
