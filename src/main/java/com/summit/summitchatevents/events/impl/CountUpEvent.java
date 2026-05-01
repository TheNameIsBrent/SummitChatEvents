package com.summit.summitchatevents.events.impl;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.events.ChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chat event where players collectively count upward: 1, 2, 3 …
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>The server broadcasts "1" to start; players must continue from 2.</li>
 *   <li>All chat is suppressed while the event is active.</li>
 *   <li>Only a message that exactly equals the next integer is accepted.</li>
 *   <li>The correct message is allowed through as-is from the player — it is
 *       styled (bold + random colour) via the Bukkit chat format, not re-sent
 *       by the server.</li>
 *   <li>The first player to send the correct number wins the race; all later
 *       duplicates in the same tick are silently cancelled.</li>
 *   <li>The event runs for a random duration between 30 and 90 seconds, then
 *       stops automatically and announces the winner.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * {@link AsyncPlayerChatEvent} fires on async threads. {@link #currentNumber}
 * and {@link #lastPlayer} are atomic so the compare-and-set is race-condition
 * safe. All Bukkit API calls that require the main thread are dispatched via
 * {@code runTask()}.
 */
public final class CountUpEvent extends ChatEvent implements Listener {

    // -----------------------------------------------------------------------
    // Timing constants
    // -----------------------------------------------------------------------

    private static final int MIN_DURATION_SECONDS = 30;
    private static final int MAX_DURATION_SECONDS = 90;
    private static final int TICKS_PER_SECOND     = 20;

    // -----------------------------------------------------------------------
    // Chat colours used for correct-number styling (bold + random colour)
    // -----------------------------------------------------------------------

    private static final ChatColor[] COLORS = {
        ChatColor.RED, ChatColor.GOLD, ChatColor.YELLOW, ChatColor.GREEN,
        ChatColor.AQUA, ChatColor.LIGHT_PURPLE, ChatColor.WHITE
    };

    // -----------------------------------------------------------------------
    // Messages
    // -----------------------------------------------------------------------

    private static final String PREFIX     = "\u00a76[\u00a7eCount Up\u00a76] \u00a7r";
    private static final String START_MSG  =
            PREFIX + "\u00a7aA counting event has started! The count begins now.";
    private static final String WINNER_MSG =
            PREFIX + "\u00a7aThe event is over! \u00a7eWinner: \u00a76%s \u00a7awith the last number \u00a7e%d\u00a7a!";
    private static final String NO_WIN_MSG =
            PREFIX + "\u00a7cThe event ended — nobody scored!";
    private static final String STOP_LOG   =
            "[CountUpEvent] Stopped. Highest number: %d. Winner: %s";

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /**
     * The number players must send next (starts at 2 — the server sends 1).
     * All reads/writes from the async chat thread go through this atomic.
     */
    private final AtomicInteger currentNumber = new AtomicInteger(0);

    /**
     * The last player who sent a correct number.
     */
    private final AtomicReference<Player> lastPlayer = new AtomicReference<>(null);

    /** Scheduler task that fires when the event timer expires. Nullable until started. */
    @Nullable
    private BukkitTask timerTask;

    private final Random random = new Random();

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
        currentNumber.set(2);  // server sends 1, players continue from 2
        lastPlayer.set(null);

        // Register this instance as a Bukkit listener
        Bukkit.getPluginManager().registerEvents(this, getPlugin());

        // Broadcast start and the opening number "1"
        final String styledOne = styledNumber(1);
        Bukkit.broadcastMessage(START_MSG);
        Bukkit.broadcastMessage(styledOne);

        // Schedule the random-duration timer
        final int durationSeconds = MIN_DURATION_SECONDS
                + random.nextInt(MAX_DURATION_SECONDS - MIN_DURATION_SECONDS + 1);
        final long durationTicks = (long) durationSeconds * TICKS_PER_SECOND;

        timerTask = Bukkit.getScheduler().runTaskLater(getPlugin(), this::onTimerExpired, durationTicks);

        getPlugin().getLogger().info(
            "[CountUpEvent] Started — duration: " + durationSeconds + "s (" + durationTicks + " ticks)."
        );
    }

    @Override
    protected void onStop() {
        // Cancel the timer if it hasn't fired yet (e.g. manual /stop)
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }

        // Unregister this instance's Bukkit listeners
        HandlerList.unregisterAll(this);

        final int reached    = currentNumber.get() - 1;
        final Player winner  = lastPlayer.get();

        if (winner != null) {
            Bukkit.broadcastMessage(String.format(WINNER_MSG, winner.getName(), reached));
        } else {
            Bukkit.broadcastMessage(NO_WIN_MSG);
        }

        getPlugin().getLogger().info(String.format(
            STOP_LOG,
            Math.max(reached, 0),
            winner != null ? winner.getName() : "none"
        ));

        // Reset for potential restart
        currentNumber.set(0);
        lastPlayer.set(null);
    }

    // -----------------------------------------------------------------------
    // Timer callback — main thread (runTaskLater guarantees this)
    // -----------------------------------------------------------------------

    /**
     * Called on the main thread when the event's random duration expires.
     * Delegates to {@link com.summit.summitchatevents.managers.EventManager}
     * via the plugin so the manager's state (activeEvent) is also cleared.
     */
    private void onTimerExpired() {
        timerTask = null; // already fired — nothing to cancel
        getPlugin().getLogger().info("[CountUpEvent] Timer expired — stopping event.");
        // Stop through the manager so its activeEvent reference is cleared
        getPlugin().getEventManager().stopCurrentEvent();
    }

    // -----------------------------------------------------------------------
    // Chat handler — runs on an ASYNC thread
    // -----------------------------------------------------------------------

    /**
     * Intercepts all player chat while the event is active.
     *
     * <p>Priority {@code LOWEST} so we run first. {@code ignoreCancelled = false}
     * ensures we suppress chat even if another plugin already cancelled it.
     *
     * <p>When the correct number is received, the message is <em>allowed through</em>
     * with a styled format (bold + random colour) instead of being re-sent by the
     * server. This preserves the player as the sender in chat.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        final String raw = event.getMessage().trim();

        // Always cancel first — we un-cancel only for the winning submission
        event.setCancelled(true);

        if (!isPositiveInteger(raw)) {
            return; // Non-numeric — silently blocked
        }

        final int sent;
        try {
            sent = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            return; // Overflow — silently blocked
        }

        final int expected = currentNumber.get();
        if (sent != expected) {
            return; // Wrong number — silently blocked
        }

        // Race-condition safe: only the first thread to CAS succeeds
        if (!currentNumber.compareAndSet(expected, expected + 1)) {
            return; // Another thread already claimed this number
        }

        // Record the winner of this round
        lastPlayer.set(event.getPlayer());

        // Style the message and un-cancel so it goes through from the player
        final String styled = styledNumber(sent);
        event.setMessage(styled);
        event.setFormat(styled); // replaces the entire chat line (no name prefix)
        event.setCancelled(false);
    }

    // -----------------------------------------------------------------------
    // Public accessors
    // -----------------------------------------------------------------------

    /** @return the next number players must type, or {@code 0} if not running */
    public int getCurrentNumber() {
        return currentNumber.get();
    }

    /** @return the last player who sent a correct number, or {@code null} if none yet */
    @Nullable
    public Player getLastPlayer() {
        return lastPlayer.get();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Formats a number as bold with a random chat colour.
     * Called from both the main thread (for "1") and async threads (for player
     * submissions) — {@link Random} is not thread-safe, but a race here only
     * affects colour choice, not correctness, so it is acceptable.
     */
    private String styledNumber(final int number) {
        final ChatColor colour = COLORS[random.nextInt(COLORS.length)];
        return colour.toString() + ChatColor.BOLD + number;
    }

    /**
     * Fast check: is {@code s} a positive integer string with no leading zeros?
     * Avoids allocation; rejects anything that would overflow {@code int}.
     */
    private static boolean isPositiveInteger(final String s) {
        if (s.isEmpty() || s.length() > 10) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return s.charAt(0) != '0';
    }
}
