package com.summit.summitchatevents.events;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base class for all Summit chat events.
 *
 * <p>Concrete events extend this class and override {@link #onStart()} and
 * {@link #onStop()} to implement their specific logic. The base class handles
 * the running-state guard so subclasses never need to worry about double-starts
 * or double-stops.
 *
 * <p>All methods must be called from the main server thread.
 *
 * <h3>Implementing a new event:</h3>
 * <pre>{@code
 * public final class MyEvent extends ChatEvent {
 *     public MyEvent(SummitChatEventsPlugin plugin) {
 *         super(plugin, "My Event");
 *     }
 *
 *     @Override
 *     protected void onStart() {
 *         // schedule tasks, register listeners, etc.
 *     }
 *
 *     @Override
 *     protected void onStop() {
 *         // cancel tasks, unregister listeners, etc.
 *     }
 * }
 * }</pre>
 */
public abstract class ChatEvent {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final SummitChatEventsPlugin plugin;
    private final String displayName;
    private boolean running;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param plugin      the owning plugin instance
     * @param displayName human-readable name shown in messages and logs
     */
    protected ChatEvent(
            final @NotNull SummitChatEventsPlugin plugin,
            final @NotNull String displayName
    ) {
        this.plugin      = plugin;
        this.displayName = displayName;
        this.running     = false;
    }

    // -----------------------------------------------------------------------
    // Public lifecycle API — called by EventManager
    // -----------------------------------------------------------------------

    /**
     * Starts this event. No-op (with a log warning) if already running.
     */
    public final void start() {
        if (running) {
            plugin.getLogger().warning(
                "Attempted to start '" + displayName + "' but it is already running."
            );
            return;
        }
        running = true;
        plugin.getLogger().info("Starting event: " + displayName);
        onStart();
    }

    /**
     * Stops this event. No-op (with a log warning) if not running.
     */
    public final void stop() {
        if (!running) {
            plugin.getLogger().warning(
                "Attempted to stop '" + displayName + "' but it is not running."
            );
            return;
        }
        running = false;
        plugin.getLogger().info("Stopping event: " + displayName);
        onStop();
    }

    // -----------------------------------------------------------------------
    // Template methods — implemented by subclasses
    // -----------------------------------------------------------------------

    /**
     * Called once when the event starts. Guaranteed to run only when the event
     * was previously stopped. Register listeners, start timers, etc. here.
     */
    protected abstract void onStart();

    /**
     * Called once when the event stops. Guaranteed to run only when the event
     * was previously running. Cancel timers, unregister listeners, etc. here.
     */
    protected abstract void onStop();

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** @return {@code true} if this event is currently active */
    public final boolean isRunning() {
        return running;
    }

    /** @return the human-readable display name of this event */
    public final @NotNull String getDisplayName() {
        return displayName;
    }

    /** @return the owning plugin instance */
    protected final @NotNull SummitChatEventsPlugin getPlugin() {
        return plugin;
    }

    /**
     * Called by the stop command before {@link #stop()} to signal that this
     * event was terminated by an administrator rather than finishing naturally.
     *
     * <p>Default implementation is a no-op. Subclasses that need to suppress
     * the result announcement (e.g. {@link com.summit.summitchatevents.events.impl.WavelengthEvent})
     * should override this method and set their own flag.
     */
    public void markStoppedByAdmin() {
        // No-op by default — override in subclasses that need it
    }
}
