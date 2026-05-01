package com.summit.summitchatevents.events.impl;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.events.ChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chat event where players collectively count upward: 1, 2, 3 …
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>All chat is suppressed while the event is active.</li>
 *   <li>Only a message that exactly equals the next integer is accepted.</li>
 *   <li>The first player to send the correct number wins the race; all later
 *       duplicates in the same tick are silently cancelled.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * {@link AsyncPlayerChatEvent} fires on async threads. {@link #currentNumber}
 * and {@link #lastPlayer} are both {@link java.util.concurrent.atomic atomic}
 * references so the compare-and-set is race-condition safe without holding
 * the server lock. Broadcasts are scheduled back onto the main thread.
 */
public final class CountUpEvent extends ChatEvent implements Listener {

    // -----------------------------------------------------------------------
    // Messages (hardcoded — config support added in a future milestone)
    // -----------------------------------------------------------------------

    private static final String PREFIX   = "\u00a76[\u00a7eCount Up\u00a76] \u00a7r";
    private static final String START_MSG =
            PREFIX + "\u00a7aThe counting event has started! Type \u00a7e1\u00a7a in chat to begin.";
    private static final String STOP_MSG  =
            PREFIX + "\u00a7cThe counting event has ended. Last number reached: \u00a7e%d\u00a7c.";
    private static final String COUNT_MSG =
            PREFIX + "\u00a7b%s \u00a7fsent \u00a7e%d\u00a7f!";

    // -----------------------------------------------------------------------
    // State — all mutable fields are atomic for async safety
    // -----------------------------------------------------------------------

    /**
     * The number players must send next.
     * Starts at 1 on event start and increments with each correct answer.
     * Reads and CAS operations from the async chat thread are safe.
     */
    private final AtomicInteger currentNumber = new AtomicInteger(0);

    /**
     * The last player who sent a correct number.
     * Updated atomically alongside {@link #currentNumber}.
     */
    private final AtomicReference<Player> lastPlayer = new AtomicReference<>(null);

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public CountUpEvent(final SummitChatEventsPlugin plugin) {
        super(plugin, "Count Up");
    }

    // -----------------------------------------------------------------------
    // ChatEvent lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onStart() {
        // Reset state
        currentNumber.set(1);
        lastPlayer.set(null);

        // Register this class as a Bukkit listener
        Bukkit.getPluginManager().registerEvents(this, getPlugin());

        // Broadcast start message on the main thread (we're already on it)
        Bukkit.broadcastMessage(START_MSG);
        getPlugin().getLogger().info("[CountUpEvent] Started — current number: 1");
    }

    @Override
    protected void onStop() {
        // Unregister all handlers registered by this listener instance
        HandlerList.unregisterAll(this);

        final int reached = currentNumber.get() - 1;
        Bukkit.broadcastMessage(String.format(STOP_MSG, Math.max(reached, 0)));
        getPlugin().getLogger().info("[CountUpEvent] Stopped — highest number reached: " + reached);

        // Reset state so the event can be restarted cleanly
        currentNumber.set(0);
        lastPlayer.set(null);
    }

    // -----------------------------------------------------------------------
    // Chat handler — runs on an ASYNC thread
    // -----------------------------------------------------------------------

    /**
     * Intercepts all player chat while the event is active.
     *
     * <p>Priority {@code LOWEST} ensures we see the message before other plugins
     * process it, and {@code ignoreCancelled = false} means we still run even if
     * another plugin already cancelled the event (so we can enforce suppression).
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        // Always cancel — nothing goes to chat unless we re-broadcast it ourselves
        event.setCancelled(true);

        final String raw = event.getMessage().trim();

        // Ignore non-numeric messages silently
        if (!isPositiveInteger(raw)) {
            return;
        }

        final int sent;
        try {
            sent = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            // Too large to be a valid int — ignore
            return;
        }

        // Atomically claim the current number.
        // compareAndSet returns true only for the FIRST thread that sees the
        // expected value — all racing duplicates get false and are dropped.
        final int expected = currentNumber.get();
        if (sent != expected) {
            return; // Wrong number — silent cancel
        }

        if (!currentNumber.compareAndSet(expected, expected + 1)) {
            return; // Another thread already advanced the counter this tick
        }

        // We won the race — record last player and broadcast on the main thread
        final Player player = event.getPlayer();
        lastPlayer.set(player);

        final String broadcastMsg = String.format(COUNT_MSG, player.getName(), sent);

        // Schedule the broadcast on the main thread; Bukkit.broadcastMessage is
        // technically safe from async, but scheduling keeps this consistent and
        // avoids potential issues with Paper's stricter threading checks.
        Bukkit.getScheduler().runTask(getPlugin(), () -> Bukkit.broadcastMessage(broadcastMsg));
    }

    // -----------------------------------------------------------------------
    // Public accessors
    // -----------------------------------------------------------------------

    /**
     * @return the number players must type next, or {@code 0} if the event is not running
     */
    public int getCurrentNumber() {
        return currentNumber.get();
    }

    /**
     * @return the last player who sent a correct number, or {@code null} if none yet
     */
    @Nullable
    public Player getLastPlayer() {
        return lastPlayer.get();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code s} consists entirely of ASCII digits
     * with no leading zeros (i.e. looks like a positive integer).
     * Avoids parsing to keep the hot-path allocation-free.
     */
    private static boolean isPositiveInteger(final String s) {
        if (s.isEmpty() || s.length() > 10) return false; // > 10 digits can't fit in int
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        // Reject "0", "00", "007", etc.
        return s.charAt(0) != '0';
    }
}
