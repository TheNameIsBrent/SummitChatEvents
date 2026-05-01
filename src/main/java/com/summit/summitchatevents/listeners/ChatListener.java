package com.summit.summitchatevents.listeners;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.managers.EventManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Global chat gate that runs at two priority levels to guarantee no other
 * plugin can push a message through while a Summit event is active.
 *
 * <h3>Why two handlers?</h3>
 * <ul>
 *   <li>{@code LOWEST} — runs first, before every other plugin. Marks approved
 *       messages with a metadata flag so the {@code HIGHEST} handler can
 *       distinguish them from messages other plugins may have un-cancelled.</li>
 *   <li>{@code HIGHEST} — runs last (before MONITOR). Forces every message that
 *       was not explicitly approved by CountUpEvent back to cancelled, stomping
 *       over alert, raffle, and chat-format plugins that run at NORMAL/HIGH.</li>
 * </ul>
 *
 * <p>When no Summit event is running, both handlers are no-ops.
 */
public final class ChatListener implements Listener {

    /**
     * Metadata key set on AsyncPlayerChatEvent to mark a message as approved
     * by CountUpEvent. Using a key on the event object is safe across async
     * threads since Bukkit serialises handler invocation per event instance.
     */
    public static final String APPROVED_KEY = "summit_approved";

    private final SummitChatEventsPlugin plugin;

    public ChatListener(final SummitChatEventsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * LOWEST — runs before everything else.
     * Suppresses all chat when an event is active.
     * CountUpEvent (also LOWEST, registered later) will un-cancel winning
     * submissions and set the APPROVED_KEY metadata.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChatLowest(final AsyncPlayerChatEvent event) {
        if (!isEventActive()) return;
        event.setCancelled(true);
    }

    /**
     * HIGHEST — runs after every other plugin.
     * Re-cancels any message that was not explicitly approved by CountUpEvent,
     * regardless of what other plugins did at NORMAL or HIGH priority.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onChatHighest(final AsyncPlayerChatEvent event) {
        if (!isEventActive()) return;

        // If CountUpEvent approved this message it set this metadata flag.
        // Every other message — including those un-cancelled by other plugins —
        // gets cancelled here.
        if (Boolean.TRUE.equals(event.getPlayer().getMetadata(APPROVED_KEY)
                .stream()
                .filter(m -> m.getOwningPlugin() == plugin)
                .findFirst()
                .map(m -> m.asBoolean())
                .orElse(false))) {
            // Approved — clean up the metadata and leave it un-cancelled
            event.getPlayer().removeMetadata(APPROVED_KEY, plugin);
            return;
        }

        event.setCancelled(true);
    }

    private boolean isEventActive() {
        final EventManager em = plugin.getEventManager();
        return em != null && em.isEventRunning();
    }
}
