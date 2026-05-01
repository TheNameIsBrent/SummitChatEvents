package com.summit.summitchatevents.events.impl;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.events.ChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chat event where players collectively count upward: 1, 2, 3 …
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>The server broadcasts "1" to open; players continue from 2.</li>
 *   <li>All chat is suppressed while the event is active, except for players
 *       holding the {@value #PERM_OVERRIDE} permission.</li>
 *   <li>A player cannot send two consecutive correct numbers — another player
 *       must submit in between.</li>
 *   <li>The first player to send the correct number wins the race (CAS-safe).</li>
 *   <li>Correct numbers are styled bold with a random HTML hex colour.</li>
 *   <li>Every correct submission plays the XP orb sound for all online players.</li>
 *   <li>The event runs for a random 30–90 second duration, then announces winner.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * {@link AsyncPlayerChatEvent} fires on async threads. All mutable state uses
 * atomics. Main-thread-only Bukkit calls are dispatched via {@code runTask()}.
 */
public final class CountUpEvent extends ChatEvent implements Listener {

    // -----------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------

    /** Players with this permission bypass the chat block during the event. */
    private static final String PERM_OVERRIDE = "summitevents.overridechat";

    // -----------------------------------------------------------------------
    // Timing
    // -----------------------------------------------------------------------

    private static final int MIN_DURATION_SECONDS = 30;
    private static final int MAX_DURATION_SECONDS = 90;
    private static final int TICKS_PER_SECOND     = 20;

    // -----------------------------------------------------------------------
    // Sound
    // -----------------------------------------------------------------------

    private static final Sound  CORRECT_SOUND        = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
    private static final float  CORRECT_SOUND_VOLUME = 1.0f;
    private static final float  CORRECT_SOUND_PITCH  = 1.0f;

    // -----------------------------------------------------------------------
    // Messages (§-coded for legacy broadcastMessage; winner uses Adventure)
    // -----------------------------------------------------------------------

    private static final String PREFIX     = "\u00a76[\u00a7eCount Up\u00a76] \u00a7r";
    private static final String START_MSG  =
            PREFIX + "\u00a7aA counting event has started! The count begins now.";
    private static final String WINNER_MSG =
            PREFIX + "\u00a7aThe event is over! \u00a7eWinner: \u00a76%s \u00a7awith the last number \u00a7e%d\u00a7a!";
    private static final String NO_WIN_MSG =
            PREFIX + "\u00a7cThe event ended \u2014 nobody scored!";
    private static final String STOP_LOG   =
            "[CountUpEvent] Stopped. Highest: %d. Winner: %s";

    // -----------------------------------------------------------------------
    // State — all mutable fields are atomic for async-thread safety
    // -----------------------------------------------------------------------

    /**
     * Next number players must type. Starts at 2 (server sends 1), resets to 0 on stop.
     */
    private final AtomicInteger currentNumber = new AtomicInteger(0);

    /**
     * UUID of the last player who sent a correct number.
     * Using UUID (not Player) avoids holding a stale entity reference.
     * {@code null} if no one has scored yet.
     */
    private final AtomicReference<UUID> lastPlayerUuid = new AtomicReference<>(null);

    /**
     * Cached display name of the last player who scored (set alongside UUID).
     */
    private final AtomicReference<String> lastPlayerName = new AtomicReference<>(null);

    /** Scheduler task that fires when the event timer expires. */
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
    // ChatEvent lifecycle — called on the main thread
    // -----------------------------------------------------------------------

    @Override
    protected void onStart() {
        currentNumber.set(2);       // server posts 1; players start from 2
        lastPlayerUuid.set(null);
        lastPlayerName.set(null);

        Bukkit.getPluginManager().registerEvents(this, getPlugin());

        Bukkit.broadcastMessage(START_MSG);
        broadcastStyledNumber(1);   // server-originated opening "1"

        // Random timer
        final int  durationSec   = MIN_DURATION_SECONDS
                + random.nextInt(MAX_DURATION_SECONDS - MIN_DURATION_SECONDS + 1);
        final long durationTicks = (long) durationSec * TICKS_PER_SECOND;
        timerTask = Bukkit.getScheduler().runTaskLater(getPlugin(), this::onTimerExpired, durationTicks);

        getPlugin().getLogger().info(
                "[CountUpEvent] Started — duration: " + durationSec + "s.");
    }

    @Override
    protected void onStop() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }

        HandlerList.unregisterAll(this);

        final int    reached    = Math.max(currentNumber.get() - 1, 0);
        final String winnerName = lastPlayerName.get();

        if (winnerName != null) {
            Bukkit.broadcastMessage(String.format(WINNER_MSG, winnerName, reached));
        } else {
            Bukkit.broadcastMessage(NO_WIN_MSG);
        }

        getPlugin().getLogger().info(String.format(STOP_LOG, reached,
                winnerName != null ? winnerName : "none"));

        // Reset for clean restart
        currentNumber.set(0);
        lastPlayerUuid.set(null);
        lastPlayerName.set(null);
    }

    // -----------------------------------------------------------------------
    // Timer callback — guaranteed on the main thread via runTaskLater
    // -----------------------------------------------------------------------

    private void onTimerExpired() {
        timerTask = null;
        getPlugin().getLogger().info("[CountUpEvent] Timer expired — stopping.");
        getPlugin().getEventManager().stopCurrentEvent();
    }

    // -----------------------------------------------------------------------
    // Chat handler — runs on an ASYNC thread
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        final Player player = event.getPlayer();

        // Players with override permission pass through unrestricted
        if (player.hasPermission(PERM_OVERRIDE)) {
            return;
        }

        final String raw = event.getMessage().trim();

        // Suppress all chat by default
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

        // Prevent the same player from sending two consecutive numbers
        if (player.getUniqueId().equals(lastPlayerUuid.get())) {
            return; // Consecutive send — silently blocked
        }

        // Race-safe claim: only the first thread to CAS succeeds
        if (!currentNumber.compareAndSet(expected, expected + 1)) {
            return; // Another thread beat us — silently blocked
        }

        // Record winner of this round
        lastPlayerUuid.set(player.getUniqueId());
        lastPlayerName.set(player.getName());

        // Style the message in-place and un-cancel so it comes from the player
        final String styled = buildStyledString(sent);
        event.setMessage(styled);
        event.setFormat(styled);    // drops name prefix — pure number in chat
        event.setCancelled(false);

        // Play sound and update live display on the main thread
        Bukkit.getScheduler().runTask(getPlugin(), () -> playSoundForAll());
    }

    // -----------------------------------------------------------------------
    // Helpers — main-thread side
    // -----------------------------------------------------------------------

    /**
     * Broadcasts a styled number as a server message (used for the opening "1").
     * Must be called from the main thread.
     */
    private void broadcastStyledNumber(final int number) {
        Bukkit.broadcastMessage(buildStyledString(number));
    }

    /**
     * Plays the XP-orb pickup sound at full volume for every online player.
     * Must be called from the main thread.
     */
    private void playSoundForAll() {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), CORRECT_SOUND, CORRECT_SOUND_VOLUME, CORRECT_SOUND_PITCH);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers — thread-agnostic
    // -----------------------------------------------------------------------

    /**
     * Returns a legacy-format string for {@code number} styled bold in a
     * random HTML hex colour using Adventure's {@link TextColor#color(int)}.
     *
     * <p>The resulting string is compatible with {@link AsyncPlayerChatEvent#setFormat}
     * which still uses the legacy § system. We build an Adventure {@link Component},
     * then serialise it back to a legacy string.
     */
    private String buildStyledString(final int number) {
        // Generate a random RGB colour with minimum brightness so it's readable
        final int r = 80 + random.nextInt(176);   // 80–255
        final int g = 80 + random.nextInt(176);
        final int b = 80 + random.nextInt(176);

        final Component component = Component.text(String.valueOf(number))
                .color(TextColor.color(r, g, b))
                .decorate(TextDecoration.BOLD);

        // Serialise to §x§r§g§b hex format understood by the legacy chat system
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    /**
     * Fast positive-integer check with no allocation.
     * Rejects empty strings, leading zeros, and strings longer than 10 chars
     * (which cannot fit in a signed 32-bit integer).
     */
    private static boolean isPositiveInteger(final String s) {
        if (s.isEmpty() || s.length() > 10) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return s.charAt(0) != '0';
    }

    // -----------------------------------------------------------------------
    // Public accessors
    // -----------------------------------------------------------------------

    /** @return the next number players must type, or 0 if the event is not running */
    public int getCurrentNumber() {
        return currentNumber.get();
    }

    /** @return the name of the last player who scored, or {@code null} if none yet */
    @Nullable
    public String getLastPlayerName() {
        return lastPlayerName.get();
    }
}
