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
 * <p>All public methods must be called from the main server thread.
 */
public final class EventManager {

    private final SummitChatEventsPlugin plugin;

    /** Ordered registry: event key -> event instance. */
    private final Map<String, ChatEvent> registry = new LinkedHashMap<>();

    @Nullable
    private ChatEvent activeEvent;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public EventManager(final SummitChatEventsPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public void init() {
        activeEvent = null;
        registerBuiltInEvents();
        plugin.getLogger().info("EventManager initialised with "
                + registry.size() + " event(s): " + registry.keySet());
    }

    public void shutdown() {
        if (activeEvent != null) {
            plugin.getLogger().warning("Shutting down while '"
                    + activeEvent.getDisplayName() + "' was running. Forcing stop.");
            safeStop(activeEvent);
            activeEvent = null;
        }
        registry.clear();
        plugin.getLogger().info("EventManager shut down.");
    }

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    private void registerBuiltInEvents() {
        register("count", new CountUpEvent(plugin));
    }

    public void register(final @NotNull String key, final @NotNull ChatEvent event) {
        final String k = normalise(key);
        if (k.isEmpty()) throw new IllegalArgumentException("Event key must not be blank.");
        if (registry.containsKey(k)) throw new IllegalArgumentException("Key already registered: " + k);
        registry.put(k, event);
    }

    // -----------------------------------------------------------------------
    // Result type
    // -----------------------------------------------------------------------

    public enum StartResult { SUCCESS, ALREADY_RUNNING, NOT_FOUND }

    // -----------------------------------------------------------------------
    // Control API
    // -----------------------------------------------------------------------

    /**
     * Starts the event registered under {@code key}.
     *
     * <p>The race condition is avoided by setting {@code activeEvent} only
     * <em>after</em> {@link ChatEvent#start()} returns successfully.
     * If {@code start()} throws, {@code activeEvent} remains {@code null}.
     */
    public @NotNull StartResult startEvent(final @NotNull String key) {
        if (key.isBlank()) throw new IllegalArgumentException("Event key must not be blank.");
        if (activeEvent != null) return StartResult.ALREADY_RUNNING;

        final ChatEvent event = registry.get(normalise(key));
        if (event == null) return StartResult.NOT_FOUND;

        // Start first — set active only on success
        event.start();
        activeEvent = event;   // only reached if start() did not throw
        return StartResult.SUCCESS;
    }

    /**
     * Stops the currently active event, if any.
     *
     * @return {@code true} if an event was stopped
     */
    public boolean stopCurrentEvent() {
        if (activeEvent == null) return false;
        final ChatEvent toStop = activeEvent;
        activeEvent = null;          // clear first so isEventRunning() returns false
        safeStop(toStop);            // during stop, event cannot re-start itself
        return true;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public boolean isEventRunning() { return activeEvent != null; }

    @Nullable
    public ChatEvent getActiveEvent() { return activeEvent; }

    @Nullable
    public String getActiveEventName() {
        return activeEvent == null ? null : activeEvent.getDisplayName();
    }

    public @NotNull Set<String> getRegisteredEventKeys() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static String normalise(final String key) {
        return key == null ? "" : key.toLowerCase().trim();
    }

    /** Calls {@code stop()} and swallows any exception so shutdown always completes. */
    private void safeStop(final ChatEvent event) {
        try {
            event.stop();
        } catch (final Exception e) {
            plugin.getLogger().severe("Exception while stopping event '"
                    + event.getDisplayName() + "': " + e.getMessage());
        }
    }
}
