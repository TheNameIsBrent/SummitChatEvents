package com.summit.summitchatevents.managers;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.events.ChatEvent;
import com.summit.summitchatevents.events.impl.CountUpEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Central manager responsible for registering and controlling {@link ChatEvent} instances.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Maintains a registry mapping event keys (e.g. {@code "count"}) to event instances.</li>
 *   <li>Ensures only one event can be active at a time.</li>
 *   <li>Provides {@link #startEvent(String)} and {@link #stopCurrentEvent()} as the
 *       single source of truth for event lifecycle.</li>
 * </ul>
 *
 * <p>All public methods must be called from the main server thread.
 */
public final class EventManager {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final SummitChatEventsPlugin plugin;

    /** Ordered map of registered event key -> event instance. */
    private final Map<String, ChatEvent> registry = new LinkedHashMap<>();

    /** The currently active event, or {@code null} if none is running. */
    @Nullable
    private ChatEvent activeEvent;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public EventManager(final SummitChatEventsPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Lifecycle (called by SummitChatEventsPlugin)
    // -----------------------------------------------------------------------

    /**
     * Registers all built-in events and prepares internal state.
     * Called once during {@code onEnable()}.
     */
    public void init() {
        activeEvent = null;
        registerBuiltInEvents();
        plugin.getLogger().info("EventManager initialised with "
                + registry.size() + " registered event(s): " + registry.keySet());
    }

    /**
     * Stops any running event and clears state.
     * Called once during {@code onDisable()}.
     */
    public void shutdown() {
        if (activeEvent != null) {
            plugin.getLogger().warning(
                "Plugin disabled while '" + activeEvent.getDisplayName() + "' was still running. Forcing stop."
            );
            activeEvent.stop();
            activeEvent = null;
        }
        registry.clear();
        plugin.getLogger().info("EventManager shut down.");
    }

    // -----------------------------------------------------------------------
    // Event registration
    // -----------------------------------------------------------------------

    /**
     * Registers all built-in events. Add new events here as the plugin grows.
     */
    private void registerBuiltInEvents() {
        register("count", new CountUpEvent(plugin));
    }

    /**
     * Registers a named event in the registry.
     *
     * @param key   the lowercase lookup key players type (e.g. {@code "count"})
     * @param event the event instance to associate with that key
     * @throws IllegalArgumentException if the key is blank or already registered
     */
    public void register(final @NotNull String key, final @NotNull ChatEvent event) {
        final String normalised = key.toLowerCase().trim();
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException("Event key must not be blank.");
        }
        if (registry.containsKey(normalised)) {
            throw new IllegalArgumentException("An event is already registered under key: " + normalised);
        }
        registry.put(normalised, event);
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Result type returned by {@link #startEvent(String)} to give the caller
     * enough context to form a player-facing message without coupling the
     * manager to chat formatting.
     */
    public enum StartResult {
        /** The event was started successfully. */
        SUCCESS,
        /** Another event is already running. */
        ALREADY_RUNNING,
        /** No event is registered under the given name. */
        NOT_FOUND
    }

    /**
     * Attempts to start the event registered under {@code key}.
     *
     * @param key the event key (case-insensitive)
     * @return a {@link StartResult} describing the outcome
     */
    public @NotNull StartResult startEvent(final @NotNull String key) {
        if (key.isBlank()) {
            throw new IllegalArgumentException("Event key must not be blank.");
        }

        if (activeEvent != null) {
            return StartResult.ALREADY_RUNNING;
        }

        final ChatEvent event = registry.get(key.toLowerCase().trim());
        if (event == null) {
            return StartResult.NOT_FOUND;
        }

        activeEvent = event;
        activeEvent.start();
        return StartResult.SUCCESS;
    }

    /**
     * Stops the currently active event, if any.
     *
     * @return {@code true} if an event was stopped, {@code false} if nothing was running
     */
    public boolean stopCurrentEvent() {
        if (activeEvent == null) {
            return false;
        }
        activeEvent.stop();
        activeEvent = null;
        return true;
    }

    /**
     * @return {@code true} if an event is currently active
     */
    public boolean isEventRunning() {
        return activeEvent != null;
    }

    /**
     * @return the active {@link ChatEvent}, or {@code null} if none is running
     */
    @Nullable
    public ChatEvent getActiveEvent() {
        return activeEvent;
    }

    /**
     * @return the display name of the active event, or {@code null} if none is running
     */
    @Nullable
    public String getActiveEventName() {
        return activeEvent == null ? null : activeEvent.getDisplayName();
    }

    /**
     * @return an unmodifiable view of all registered event keys
     */
    public @NotNull Set<String> getRegisteredEventKeys() {
        return Collections.unmodifiableSet(registry.keySet());
    }
}
