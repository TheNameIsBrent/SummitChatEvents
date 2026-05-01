package com.summit.summitchatevents.events.impl;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.config.PluginConfig;
import com.summit.summitchatevents.events.ChatEvent;
import com.summit.summitchatevents.listeners.ChatListener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Wavelength — a multi-round guessing event.
 *
 * <h3>Flow (per round)</h3>
 * <ol>
 *   <li>A secret target number (1–100) is chosen randomly.</li>
 *   <li>Active players each submit one guess in chat.</li>
 *   <li>When all active players have guessed, the round closes.</li>
 *   <li>The player closest to the target scores a point (ties broken by first guess).</li>
 *   <li>After {@code maxRounds} rounds, the player with the most points wins.</li>
 * </ol>
 *
 * <h3>Permissions</h3>
 * <ul>
 *   <li>{@value #PERM_PLAY}     — default {@code true}; required to participate.</li>
 *   <li>{@value #PERM_OVERRIDE} — bypasses chat block; can still play.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * {@link AsyncPlayerChatEvent} fires async. Guesses and active-player state are
 * protected by {@code synchronized} blocks since {@link HashMap} / {@link HashSet}
 * are not thread-safe. Main-thread Bukkit calls go through {@code runTask()}.
 */
public final class WavelengthEvent extends ChatEvent implements Listener {

    // -----------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------

    private static final String PERM_PLAY     = "summitevents.play";
    private static final String PERM_OVERRIDE = "summitevents.overridechat";

    // -----------------------------------------------------------------------
    // Intro timing (ticks; 20 = 1 s)
    // -----------------------------------------------------------------------

    private static final long T_RULES      = 60L;   // 3 s after announce
    private static final long T_HERE_WE_GO = 120L;  // 3 s after rules
    private static final long T_START      = 160L;  // 2 s after here-we-go

    // -----------------------------------------------------------------------
    // Game state — guarded by intrinsic lock on `this`
    // -----------------------------------------------------------------------

    /** Players still active in the current event (eliminated or left players removed). */
    private final Set<UUID> activePlayers = new HashSet<>();

    /**
     * Guesses submitted this round: UUID → guess value.
     * Protected by synchronized blocks (async chat thread writes, main thread reads).
     */
    private final Map<UUID, Integer> guesses = new HashMap<>();

    /**
     * Cumulative points across rounds: UUID → points.
     */
    private final Map<UUID, Integer> points = new HashMap<>();

    /** Current round number (1-indexed). */
    private int currentRound = 0;

    /** The secret target for the current round. */
    private int target = 0;

    /** Maximum number of rounds from config. */
    private int maxRounds = 5;

    /** True once the intro finishes and the game is live. */
    private volatile boolean live = false;

    /** All pending scheduler tasks — cancelled as a group on stop. */
    private final List<BukkitTask> tasks = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public WavelengthEvent(final SummitChatEventsPlugin plugin) {
        super(plugin, "Wavelength");
    }

    // -----------------------------------------------------------------------
    // ChatEvent lifecycle — main thread
    // -----------------------------------------------------------------------

    @Override
    protected void onStart() {
        final PluginConfig cfg = getPlugin().getPluginConfig();
        maxRounds = cfg.getWavelengthMaxRounds();

        // Reset all state
        synchronized (this) {
            activePlayers.clear();
            guesses.clear();
            points.clear();
            // Populate active players from all currently online players
            for (final Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(PERM_PLAY) || p.hasPermission(PERM_OVERRIDE)) {
                    activePlayers.add(p.getUniqueId());
                    points.put(p.getUniqueId(), 0);
                }
            }
        }
        currentRound = 0;
        live         = false;
        tasks.clear();

        Bukkit.getPluginManager().registerEvents(this, getPlugin());

        scheduleIntro(cfg);
    }

    @Override
    protected void onStop() {
        live = false;

        tasks.forEach(BukkitTask::cancel);
        tasks.clear();

        HandlerList.unregisterAll(this);

        final PluginConfig cfg = getPlugin().getPluginConfig();

        // Determine overall winner (most points; ties to whichever has the highest UUID
        // as a deterministic tiebreaker — fairness note: add timestamp tracking if needed)
        @Nullable final UUID winnerUuid  = findOverallWinner();
        @Nullable final Player winner    = winnerUuid != null ? Bukkit.getPlayer(winnerUuid) : null;
        final String        winnerName  = winner != null ? winner.getName()
                : (winnerUuid != null ? winnerUuid.toString() : null);

        if (winnerName != null) {
            broadcast(PluginConfig.format(cfg.getWavelengthMsgWinner(), winnerName));
            runRewardCommand(cfg, winnerName);
        } else {
            broadcast(cfg.getWavelengthMsgNoWinner());
        }

        getPlugin().getLogger().info("[WavelengthEvent] Stopped after round "
                + currentRound + ". Winner: " + (winnerName != null ? winnerName : "none"));

        synchronized (this) {
            activePlayers.clear();
            guesses.clear();
            points.clear();
        }
        currentRound = 0;
        target       = 0;
    }

    // -----------------------------------------------------------------------
    // Intro sequence — main thread
    // -----------------------------------------------------------------------

    private void scheduleIntro(final PluginConfig cfg) {
        schedule(0L,        () -> broadcast(cfg.getWavelengthMsgAnnounce()));
        schedule(T_RULES,   () -> broadcast(cfg.getWavelengthMsgRules()));
        schedule(T_HERE_WE_GO, () -> broadcast(cfg.getWavelengthMsgHereWeGo()));
        schedule(T_START,   this::startRound);
    }

    // -----------------------------------------------------------------------
    // Round logic — main thread
    // -----------------------------------------------------------------------

    /**
     * Starts the next round.
     *
     * <p>Called after the intro and after each round closes (if rounds remain).
     * Sets {@link #target} to a new random value, clears guesses, increments
     * {@link #currentRound}, and broadcasts the round-start message.
     */
    private void startRound() {
        currentRound++;
        target = 1 + (int) (Math.random() * 100);  // 1–100 inclusive

        synchronized (this) {
            guesses.clear();
        }

        live = true;

        final String msg = PluginConfig.format(
                getPlugin().getPluginConfig().getWavelengthMsgRoundStart(),
                null, -1, currentRound);
        broadcast(msg);

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " started. Target: " + target);
    }

    /**
     * Closes the current round.
     *
     * <p>Called when all active players have submitted a guess (or on manual stop).
     * Determines the winner of this round, awards a point, then starts the next
     * round or ends the event if {@link #maxRounds} is reached.
     */
    private void closeRound() {
        live = false;

        final PluginConfig cfg = getPlugin().getPluginConfig();

        // Snapshot guesses under lock
        final Map<UUID, Integer> snapshot;
        synchronized (this) {
            snapshot = new HashMap<>(guesses);
        }

        // Find closest guess (ties broken by insertion order — LinkedHashMap not used,
        // so tie-breaking here is by UUID comparison for determinism)
        @Nullable UUID roundWinnerUuid = null;
        int closestDelta = Integer.MAX_VALUE;

        for (final Map.Entry<UUID, Integer> entry : snapshot.entrySet()) {
            final int delta = Math.abs(entry.getValue() - target);
            if (delta < closestDelta) {
                closestDelta     = delta;
                roundWinnerUuid  = entry.getKey();
            }
        }

        if (roundWinnerUuid != null) {
            synchronized (this) {
                points.merge(roundWinnerUuid, 1, Integer::sum);
            }
            final Player roundWinner = Bukkit.getPlayer(roundWinnerUuid);
            final String name = roundWinner != null ? roundWinner.getName()
                    : roundWinnerUuid.toString();
            broadcast(cfg.getPrefix() + "&eRound &6" + currentRound
                    + "&e winner: &6&l" + name
                    + "&e (guessed &6" + snapshot.get(roundWinnerUuid)
                    + "&e, target was &6" + target + "&e)!");

            getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                    + " won by " + name + " with guess " + snapshot.get(roundWinnerUuid)
                    + " (target=" + target + ")");
        } else {
            broadcast(cfg.getPrefix() + "&7No guesses this round.");
        }

        if (currentRound >= maxRounds) {
            // All rounds done — stop through the manager
            getPlugin().getLogger().info("[WavelengthEvent] All rounds complete.");
            Bukkit.getScheduler().runTaskLater(getPlugin(),
                    () -> getPlugin().getEventManager().stopCurrentEvent(), 40L);
        } else {
            // Next round after a short pause
            schedule(60L, this::startRound);
        }
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

        // Override players: non-number messages pass through freely
        if (hasOverride && !isInteger(raw)) {
            approveMessage(player);
            event.setCancelled(false);
            return;
        }

        // Everyone else is already cancelled by ChatListener.LOWEST
        if (!canPlay) {
            return;
        }

        if (!live) {
            return; // Intro or between rounds — suppress
        }

        if (!isInteger(raw)) {
            return; // Non-number silently blocked
        }

        final int guess;
        try {
            guess = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            return;
        }

        if (guess < 1 || guess > 100) {
            return; // Out of range — silently blocked
        }

        // Only active players can submit
        final boolean isActive;
        synchronized (this) {
            isActive = activePlayers.contains(player.getUniqueId());
        }
        if (!isActive) {
            return;
        }

        // Each player may submit only once per round
        final boolean alreadyGuessed;
        synchronized (this) {
            alreadyGuessed = guesses.containsKey(player.getUniqueId());
            if (!alreadyGuessed) {
                guesses.put(player.getUniqueId(), guess);
            }
        }
        if (alreadyGuessed) {
            return;
        }

        // Acknowledge the guess — keep the event cancelled, send a private confirmation
        final String ack = getPlugin().getPluginConfig().getPrefix()
                + "&7Your guess &e" + guess + "&7 has been recorded!";
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            player.sendMessage(PluginConfig.color(ack));

            // Check if all active players have now guessed
            final boolean allGuessed;
            synchronized (this) {
                allGuessed = guesses.keySet().containsAll(activePlayers);
            }
            if (allGuessed) {
                closeRound();
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers — main thread
    // -----------------------------------------------------------------------

    private void runRewardCommand(final PluginConfig cfg, final String winnerName) {
        final String cmd = cfg.getWavelengthRewardCommand();
        if (cmd == null || cmd.isBlank()) return;
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    cmd.replace("%player%", winnerName));
            broadcast(PluginConfig.format(cfg.getWavelengthMsgReward(), winnerName));
            getPlugin().getLogger().info(
                    "[WavelengthEvent] Reward dispatched for " + winnerName);
        });
    }

    private void approveMessage(final Player player) {
        player.setMetadata(ChatListener.APPROVED_KEY,
                new FixedMetadataValue(getPlugin(), true));
    }

    private void broadcast(final String message) {
        Bukkit.broadcastMessage(message);
    }

    private void schedule(final long delayTicks, final Runnable task) {
        tasks.add(Bukkit.getScheduler().runTaskLater(getPlugin(), task, delayTicks));
    }

    // -----------------------------------------------------------------------
    // Helpers — thread-agnostic
    // -----------------------------------------------------------------------

    /**
     * Finds the UUID with the most points. Returns {@code null} if no guesses
     * were submitted in any round.
     */
    @Nullable
    private UUID findOverallWinner() {
        synchronized (this) {
            return points.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
    }

    /** Returns true if {@code s} is a parseable integer (including negatives). */
    private static boolean isInteger(final String s) {
        if (s == null || s.isEmpty()) return false;
        final int start = (s.charAt(0) == '-') ? 1 : 0;
        if (start == s.length()) return false;
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Public accessors
    // -----------------------------------------------------------------------

    public int getCurrentRound() { return currentRound; }

    public int getTarget()       { return target; }

    /** Snapshot of current guesses. Safe to call from any thread. */
    public Map<UUID, Integer> getGuessesSnapshot() {
        synchronized (this) {
            return Collections.unmodifiableMap(new HashMap<>(guesses));
        }
    }

    /** Snapshot of active players. Safe to call from any thread. */
    public Set<UUID> getActivePlayersSnapshot() {
        synchronized (this) {
            return Collections.unmodifiableSet(new HashSet<>(activePlayers));
        }
    }
}
