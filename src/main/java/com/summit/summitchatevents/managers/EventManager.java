package com.summit.summitchatevents.managers;

import com.summit.summitchatevents.SummitChatEventsPlugin;

/**
 * Central manager that will coordinate all chat-related event processing.
 *
 * <p>Placeholder implementation — logic will be added in future milestones.
 */
public final class EventManager {

    private final SummitChatEventsPlugin plugin;

    public EventManager(final SummitChatEventsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Initialise internal state. Called once during {@code onEnable()}.
     */
    public void init() {
        plugin.getLogger().info("EventManager initialised.");
        // TODO: load event pipelines, filters, handlers, etc.
    }

    /**
     * Release resources. Called once during {@code onDisable()}.
     */
    public void shutdown() {
        plugin.getLogger().info("EventManager shut down.");
        // TODO: flush queues, cancel tasks, etc.
    }
}
