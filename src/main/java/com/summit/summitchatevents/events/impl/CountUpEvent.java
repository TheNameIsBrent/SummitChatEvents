package com.summit.summitchatevents.events.impl;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.config.PluginConfig;
import com.summit.summitchatevents.events.ChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chat event where players collectively count upward: 1, 2, 3 …
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Dramatic intro sequence (announcement → rules → "here we go" → styled 1).</li>
 *   <li>Chat handler accepts only the correct next integer.</li>
 *   <li>A player cannot send two numbers in a row.</li>
 *   <li>Players with {@value #PERM_OVERRIDE} bypass the chat block and can play.</li>
 *   <li>Players without {@value #PERM_PLAY} (and without override) are fully blocked.</li>
 *   <li>Correct numbers are sent in a true-colour hex + bold via Adventure API.</li>
 *   <li>All players hear XP-orb sound on each correct number.</li>
 *   <li>Random 30–90 s timer; winner gets reward command + broadcast.</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * {@link AsyncPlayerChatEvent} fires async. All shared state uses atomics.
 * Main-thread Bukkit calls go through {@code runTask()}.
 */
public final class CountUpEvent extends ChatEvent implements Listener {

    // -----------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------

    private static final String PERM_PLAY     = "summitevents.play";
    private static final String PERM_OVERRIDE = "summitevents.overridechat";

    // -----------------------------------------------------------------------
    // Sound
    // -----------------------------------------------------------------------

    private static final Sound CORRECT_SOUND = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
    private static final float SOUND_VOLUME  = 1.0f;
    private static final float SOUND_PITCH   = 1.0f;

    // -----------------------------------------------------------------------
    // Colour — minimum component value keeps colours bright on dark BG
    // -----------------------------------------------------------------------

    private static final int COLOR_MIN = 120;
    private static final int COLOR_RANGE = 255 - COLOR_MIN + 1;

    // -----------------------------------------------------------------------
    // Intro timing (ticks: 20t = 1s)
    // -----------------------------------------------------------------------

    private static final long DELAY_ANNOUNCEMENT_TO_RULES = 60L;  // 3 s
    private static final long DELAY_RULES_TO_HERE_WE_GO   = 60L;  // 3 s
    private static final long DELAY_HERE_WE_GO_TO_ONE     = 40L;  // 2 s

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /** Next number expected. Server broadcasts 1; players type from 2. */
    private final AtomicInteger currentNumber = new AtomicInteger(0);

    private final AtomicReference<UUID>   lastPlayerUuid = new AtomicReference<>(null);
    private final AtomicReference<String> lastPlayerName = new AtomicReference<>(null);

    /** All pending scheduler tasks — cancelled as a group on stop. */
    private final List<BukkitTask> tasks = new ArrayList<>();

    /** Set to true once the intro finishes and the game is actually live. */
    private volatile boolean live = false;

    private final Random random = new Random();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public CountUpEvent(final SummitChatEventsPlugin plugin) {
        super(plugin, "Count Up");
    }

    // -----------------------------------------------------------------------
    // ChatEvent lifecycle — main thread
    // -----------------------------------------------------------------------

    @Override
    protected void onStart() {
        // Reset
        currentNumber.set(0);
        lastPlayerUuid.set(null);
        lastPlayerName.set(null);
        live = false;
        tasks.clear();

        // Register listener immediately so chat is suppressed during the intro
        Bukkit.getPluginManager().registerEvents(this, getPlugin());

        scheduleIntro();
    }

    @Override
    protected void onStop() {
        live = false;

        // Cancel every pending task (intro steps + game timer)
        for (final BukkitTask t : tasks) {
            t.cancel();
        }
        tasks.clear();

        HandlerList.unregisterAll(this);

        final PluginConfig cfg     = getPlugin().getPluginConfig();
        final String       prefix  = cfg.getPrefix();
        final int          reached = Math.max(currentNumber.get() - 1, 0);
        final String       winner  = lastPlayerName.get();

        if (winner != null) {
            Bukkit.broadcastMessage(prefix + PluginConfig.format(cfg.getCountMsgWinner(), winner, reached));
            runRewardCommand(cfg, winner);
        } else {
            Bukkit.broadcastMessage(prefix + cfg.getCountMsgNoWinner());
        }

        getPlugin().getLogger().info(String.format(
                "[CountUpEvent] Stopped. Highest: %d. Winner: %s",
                reached, winner != null ? winner : "none"));

        currentNumber.set(0);
        lastPlayerUuid.set(null);
        lastPlayerName.set(null);
    }

    // -----------------------------------------------------------------------
    // Intro sequence — all scheduled on main thread
    // -----------------------------------------------------------------------

    private void scheduleIntro() {
        final PluginConfig cfg    = getPlugin().getPluginConfig();
        final String       prefix = cfg.getPrefix();

        // Step 1 — announcement (immediately)
        broadcast(prefix + cfg.getCountMsgAnnounce());

        // Step 2 — rules
        schedule(DELAY_ANNOUNCEMENT_TO_RULES,
                () -> broadcast(prefix + cfg.getCountMsgRules()));

        // Step 3 — here we go
        schedule(DELAY_ANNOUNCEMENT_TO_RULES + DELAY_RULES_TO_HERE_WE_GO,
                () -> broadcast(prefix + cfg.getCountMsgHereWeGo()));

        // Step 4 — the "1" and game goes live
        schedule(DELAY_ANNOUNCEMENT_TO_RULES + DELAY_RULES_TO_HERE_WE_GO + DELAY_HERE_WE_GO_TO_ONE,
                this::goLive);
    }

    /**
     * Called when the intro finishes — broadcasts "1" and starts the game timer.
     */
    private void goLive() {
        currentNumber.set(2);   // server sent 1; players must type 2 next
        live = true;

        // Broadcast "1" via Adventure so each player sees the true-colour bold number
        broadcastStyledNumber(1);

        // Schedule game timer
        final PluginConfig cfg = getPlugin().getPluginConfig();
        final int minSec = cfg.getCountMinDuration();
        final int maxSec = cfg.getCountMaxDuration();
        final int durSec = minSec + random.nextInt(Math.max(maxSec - minSec + 1, 1));

        schedule((long) durSec * 20L, () -> {
            getPlugin().getLogger().info("[CountUpEvent] Timer expired.");
            getPlugin().getEventManager().stopCurrentEvent();
        });

        getPlugin().getLogger().info("[CountUpEvent] Live — " + durSec + "s.");
    }

    // -----------------------------------------------------------------------
    // Chat handler — async thread
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        final Player  player      = event.getPlayer();
        final boolean hasOverride = player.hasPermission(PERM_OVERRIDE);
        final boolean canPlay     = hasOverride || player.hasPermission(PERM_PLAY);
        final String  raw         = event.getMessage().trim();

        // ── Players with override permission ────────────────────────────────
        // Their non-number chat passes through freely.
        // Their number submissions go to game logic (cancellation handled there).
        if (hasOverride && !isPositiveInteger(raw)) {
            return; // Normal chat for override players — leave untouched
        }

        // ── Everyone without override: suppress everything by default ────────
        if (!hasOverride) {
            event.setCancelled(true);
        }

        // Players with no play permission at all are done here
        if (!canPlay) {
            return;
        }

        // ── Game not yet live (intro in progress) ────────────────────────────
        if (!live) {
            // Keep cancelled; nothing to do
            return;
        }

        // ── Not a number ─────────────────────────────────────────────────────
        if (!isPositiveInteger(raw)) {
            return; // Already cancelled above for non-override; override returns at top
        }

        final int sent;
        try {
            sent = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            return;
        }

        // ── Wrong number ─────────────────────────────────────────────────────
        final int expected = currentNumber.get();
        if (sent != expected) {
            event.setCancelled(true);
            return;
        }

        // ── Consecutive send guard ───────────────────────────────────────────
        if (player.getUniqueId().equals(lastPlayerUuid.get())) {
            event.setCancelled(true);
            return;
        }

        // ── Race-safe claim ──────────────────────────────────────────────────
        if (!currentNumber.compareAndSet(expected, expected + 1)) {
            event.setCancelled(true);
            return;
        }

        // ── Accepted ─────────────────────────────────────────────────────────
        lastPlayerUuid.set(player.getUniqueId());
        lastPlayerName.set(player.getName());

        // Cancel the legacy chat event — we broadcast via Adventure per-player
        // to get true hex colour support without the unicode-escape warning.
        event.setCancelled(true);

        final int acceptedNumber = sent;
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            broadcastStyledNumber(acceptedNumber);
            playSoundForAll();
        });
    }

    // -----------------------------------------------------------------------
    // Main-thread helpers
    // -----------------------------------------------------------------------

    /**
     * Sends each online player the styled number as an Adventure component.
     * Adventure on Paper renders true hex colours without legacy § escapes,
     * eliminating the unicode warning that appeared when using setFormat().
     */
    private void broadcastStyledNumber(final int number) {
        final int r = COLOR_MIN + random.nextInt(COLOR_RANGE);
        final int g = COLOR_MIN + random.nextInt(COLOR_RANGE);
        final int b = COLOR_MIN + random.nextInt(COLOR_RANGE);

        final Component component = Component.text(String.valueOf(number))
                .color(TextColor.color(r, g, b))
                .decorate(TextDecoration.BOLD);

        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(component);
        }
    }

    private void playSoundForAll() {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), CORRECT_SOUND, SOUND_VOLUME, SOUND_PITCH);
        }
    }

    private void runRewardCommand(final PluginConfig cfg, final String winnerName) {
        final String cmd = cfg.getCountRewardCommand();
        if (cmd == null || cmd.isBlank()) return;
        final String resolved = cmd.replace("%player%", winnerName);
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            final String rewardMsg = cfg.getPrefix() + cfg.getCountMsgReward()
                    .replace("%player%", winnerName);
            Bukkit.broadcastMessage(rewardMsg);
            getPlugin().getLogger().info("[CountUpEvent] Reward dispatched for " + winnerName + ": " + resolved);
        });
    }

    private void broadcast(final String message) {
        Bukkit.broadcastMessage(message);
    }

    /**
     * Schedules a one-shot task on the main thread and tracks it for cleanup.
     */
    private void schedule(final long delayTicks, final Runnable task) {
        tasks.add(Bukkit.getScheduler().runTaskLater(getPlugin(), task, delayTicks));
    }

    // -----------------------------------------------------------------------
    // Helpers — thread-agnostic
    // -----------------------------------------------------------------------

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

    public int getCurrentNumber() { return currentNumber.get(); }

    @Nullable
    public String getLastPlayerName() { return lastPlayerName.get(); }
}
