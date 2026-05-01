package com.summit.summitchatevents.managers;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * Central manager responsible for the lifecycle of Summit events.
 *
 * <p>Tracks whether an event is currently running and exposes control
 * methods used by command handlers and (in future) scheduled tasks.
 *
 * <p>All public methods are intended to be called from the main server
 * thread only.
 */
public final class EventManager {

    private final SummitChatEventsPlugin plugin;

    /** Name of the currently running event, or {@code null} if none is active. */
    @Nullable
    private String activeEventName;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public EventManager(final SummitChatEventsPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Initialise internal state. Called once during {@code onEnable()}.
     */
    public void init() {
        activeEventName = null;
        plugin.getLogger().info("EventManager initialised.");
    }

    /**
     * Release resources and reset state. Called once during {@code onDisable()}.
     */
    public void shutdown() {
        if (activeEventName != null) {
            plugin.getLogger().warning("Plugin disabled while event '" + activeEventName + "' was still running.");
            activeEventName = null;
        }
        plugin.getLogger().info("EventManager shut down.");
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if an event is currently active.
     */
    public boolean isEventRunning() {
        return activeEventName != null;
    }

    /**
     * Returns the name of the currently running event, or {@code null} if none.
     */
    @Nullable
    public String getActiveEventName() {
        return activeEventName;
    }

    /**
     * Attempts to start a new event with the given name.
     *
     * <p>Returns {@code false} — without starting anything — if another event
     * is already running. The caller is responsible for informing the player.
     *
     * @param name the display name of the event to start; must not be blank
     * @return {@code true} if the event was started, {@code false} if one is already running
     * @throws IllegalArgumentException if {@code name} is null or blank
     */
    public boolean startEvent(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Event name must not be null or blank.");
        }

        if (isEventRunning()) {
            return false;
        }

        activeEventName = name;
        plugin.getLogger().info("Event started: '" + name + "'");

        // TODO: implement actual event logic (scoreboard, timers, broadcasts, etc.)

        return true;
    }
}
