package com.summit.summitchatevents.events.impl;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.config.PluginConfig;
import com.summit.summitchatevents.config.WavelengthConfig;
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
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Wavelength — a multi-round number-placement event.
 *
 * <h3>How it works</h3>
 * Each round a random scale (e.g. "Worst mob ←→ Best mob") and a prompt
 * (e.g. "Creeper") are revealed. Players type a number 1–100 placing the
 * prompt on that scale. A hidden target is generated; the player whose guess
 * is closest to the target wins the round and earns a point. After all
 * configured rounds the player with the most points is the overall winner.
 *
 * <h3>Participation</h3>
 * Every online player is automatically added to {@link #activePlayers} when
 * the event starts — no permission required to participate. Players with
 * {@value #PERM_OVERRIDE} can bypass the chat block (their non-number chat
 * goes through normally) while still submitting guesses like anyone else.
 *
 * <h3>Chat pipeline</h3>
 * {@link ChatListener} cancels all chat at LOWEST priority. This handler also
 * runs at LOWEST (registered after ChatListener, so it fires second within the
 * same priority tier). Guesses are validated and stored privately — they are
 * never broadcast. Override players' non-number messages are approved via
 * metadata so {@link ChatListener}'s HIGHEST handler lets them through.
 *
 * <h3>Thread safety</h3>
 * {@link AsyncPlayerChatEvent} fires on async threads. All shared mutable
 * collections are guarded by {@code synchronized(this)}. The {@link #live}
 * flag is {@code volatile}. All Bukkit API calls that require the main thread
 * are dispatched via {@code runTask()} or are already on the main thread.
 */
public final class WavelengthEvent extends ChatEvent implements Listener {

    // -----------------------------------------------------------------------
    // Permission — override only (no play permission; everyone participates)
    // -----------------------------------------------------------------------

    /** Bypasses the chat block during the event; can still guess like everyone else. */
    private static final String PERM_OVERRIDE = "summitevents.overridechat";

    // -----------------------------------------------------------------------
    // Intro timing — matches CountUpEvent's dramatic pacing (ticks; 20t = 1s)
    // -----------------------------------------------------------------------

    private static final long T_RULES      = 60L;  // 3 s after announce
    private static final long T_HERE_WE_GO = 120L; // 3 s after rules
    private static final long T_START      = 160L; // 2 s after here-we-go

    // -----------------------------------------------------------------------
    // Round broadcast timing — staggered for dramatic effect
    // -----------------------------------------------------------------------

    /** Delay between round-start line and scale reveal (ticks). */
    private static final long T_SCALE_AFTER_ROUND = 30L;  // 1.5 s
    /** Delay between scale reveal and prompt reveal (ticks). */
    private static final long T_PROMPT_AFTER_SCALE = 30L; // 1.5 s

    // -----------------------------------------------------------------------
    // Game state
    // All collections guarded by synchronized(this).
    // currentRound, target, currentScale* are main-thread-only.
    // live is volatile — read on async thread, written on main thread.
    // -----------------------------------------------------------------------

    /** UUID of every player online when the event started. No permission required. */
    private final Set<UUID>          activePlayers = new HashSet<>();

    /**
     * Guesses for the current round: UUID → value (1–100).
     * Written from the async chat thread under lock; read from the main thread.
     */
    private final Map<UUID, Integer> guesses = new HashMap<>();

    /** Cumulative round-win points: UUID → points. Main-thread-only reads/writes. */
    private final Map<UUID, Integer> points  = new HashMap<>();

    private int    currentRound    = 0;
    private int    target          = 0;
    private String currentScaleMin = "";
    private String currentScaleMax = "";
    private String currentPrompt   = "";

    /** True once a round is open for guesses. False during intros and between rounds. */
    private volatile boolean live = false;

    /** All pending scheduler tasks. Cancelled together in {@link #onStop()}. */
    private final List<BukkitTask> tasks  = new ArrayList<>();
    private final Random           random = new Random();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public WavelengthEvent(final SummitChatEventsPlugin plugin) {
        super(plugin, "Wavelength");
    }

    // -----------------------------------------------------------------------
    // ChatEvent lifecycle — called on the main thread by EventManager
    // -----------------------------------------------------------------------

    @Override
    protected void onStart() {
        // Reset all state before anything else
        synchronized (this) {
            activePlayers.clear();
            guesses.clear();
            points.clear();
            // All online players join automatically — no permission check
            for (final Player p : Bukkit.getOnlinePlayers()) {
                activePlayers.add(p.getUniqueId());
                points.put(p.getUniqueId(), 0);
            }
        }
        currentRound    = 0;
        currentScaleMin = "";
        currentScaleMax = "";
        currentPrompt   = "";
        target          = 0;
        live            = false;
        tasks.clear();

        // Register before intro so chat is suppressed during the build-up
        Bukkit.getPluginManager().registerEvents(this, getPlugin());

        scheduleIntro();
    }

    @Override
    protected void onStop() {
        live = false;

        // Cancel every pending task (intro steps, round timers, between-round pauses)
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();

        HandlerList.unregisterAll(this);

        final WavelengthConfig    wcfg       = getPlugin().getPluginConfig().getWavelengthConfig();
        @Nullable final UUID      winnerUuid = findOverallWinner();
        @Nullable final String    winnerName = resolvePlayerName(winnerUuid);

        if (winnerName != null) {
            broadcast(WavelengthConfig.format(wcfg.getMsgWinner(),
                    -1, winnerName, -1, -1, null, null, null));
            runRewardCommand(wcfg, winnerName);
        } else {
            broadcast(wcfg.getMsgNoWinner());
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
    // Intro sequence — main thread, staggered for dramatic pacing
    // -----------------------------------------------------------------------

    private void scheduleIntro() {
        final WavelengthConfig wcfg = getPlugin().getPluginConfig().getWavelengthConfig();

        schedule(0L,           () -> broadcast(wcfg.getMsgAnnounce()));
        schedule(T_RULES,      () -> broadcast(wcfg.getMsgRules()));
        schedule(T_HERE_WE_GO, () -> broadcast(wcfg.getMsgHereWeGo()));
        schedule(T_START,      this::startRound);
    }

    // -----------------------------------------------------------------------
    // Round lifecycle — main thread
    // -----------------------------------------------------------------------

    /**
     * Starts the next round.
     *
     * <ol>
     *   <li>Increments {@link #currentRound}.</li>
     *   <li>Picks a random {@link WavelengthConfig.Scale} and prompt.</li>
     *   <li>Generates a secret target (1–100).</li>
     *   <li>Clears previous guesses.</li>
     *   <li>Broadcasts round number, then scale, then prompt — each staggered
     *       by {@link #T_SCALE_AFTER_ROUND} / {@link #T_PROMPT_AFTER_SCALE}
     *       ticks so they appear dramatically one at a time.</li>
     *   <li>After the full reveal delay, sets {@link #live} to {@code true}
     *       and schedules the round timer from config.</li>
     * </ol>
     */
    private void startRound() {
        final WavelengthConfig       wcfg  = getPlugin().getPluginConfig().getWavelengthConfig();
        final WavelengthConfig.Scale scale = wcfg.randomScale(random);

        currentRound++;
        target          = 1 + random.nextInt(100); // 1–100 inclusive
        currentScaleMin = scale.getMin();
        currentScaleMax = scale.getMax();
        currentPrompt   = scale.randomPrompt(random);

        synchronized (this) {
            guesses.clear();
        }
        live = false; // keep closed until reveal finishes

        // --- Staggered broadcast ---
        final String roundMsg = WavelengthConfig.format(wcfg.getMsgRoundStart(),
                currentRound, null, -1, -1, null, null, null);
        final String scaleMsg = WavelengthConfig.format(wcfg.getMsgScale(),
                -1, null, -1, -1, currentScaleMin, currentScaleMax, null);
        final String promptMsg = WavelengthConfig.format(wcfg.getMsgPrompt(),
                -1, null, -1, -1, null, null, currentPrompt);

        schedule(0L,                                    () -> broadcast(roundMsg));
        schedule(T_SCALE_AFTER_ROUND,                   () -> broadcast(scaleMsg));
        schedule(T_SCALE_AFTER_ROUND + T_PROMPT_AFTER_SCALE, () -> {
            broadcast(promptMsg);
            openRound(wcfg); // go live and schedule timer after full reveal
        });

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " starting. Scale='" + currentScaleMin + " <-> " + currentScaleMax
                + "' Prompt='" + currentPrompt + "' Target=" + target);
    }

    /**
     * Called once the reveal sequence completes — opens guessing and
     * schedules the per-round countdown timer.
     */
    private void openRound(final WavelengthConfig wcfg) {
        live = true;

        final long durationTicks = wcfg.getRoundDurationTicks(currentRound);
        schedule(durationTicks, this::endRound);

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " open — " + (durationTicks / 20L) + "s.");
    }

    /**
     * Called by the round timer when time expires. Closes guessing, reveals
     * the target, awards a point to the closest guesser, then either starts
     * the next round or ends the event.
     */
    private void endRound() {
        live = false;

        final WavelengthConfig   wcfg     = getPlugin().getPluginConfig().getWavelengthConfig();
        final Map<UUID, Integer> snapshot;
        synchronized (this) {
            snapshot = new HashMap<>(guesses);
        }

        // Find the closest guess — ties broken by natural iteration order of HashMap
        // (non-deterministic, but fair — a LinkedHashMap could add first-in-time priority)
        @Nullable UUID roundWinnerUuid = null;
        int            closestDelta    = Integer.MAX_VALUE;

        for (final Map.Entry<UUID, Integer> entry : snapshot.entrySet()) {
            final int delta = Math.abs(entry.getValue() - target);
            if (delta < closestDelta) {
                closestDelta    = delta;
                roundWinnerUuid = entry.getKey();
            }
        }

        if (roundWinnerUuid != null) {
            // Award the point on the main thread before broadcasting
            final UUID   finalWinner = roundWinnerUuid;
            synchronized (this) {
                points.merge(finalWinner, 1, Integer::sum);
            }
            final String winnerName = resolvePlayerName(finalWinner);
            final int    winGuess   = snapshot.get(finalWinner);

            broadcast(WavelengthConfig.format(wcfg.getMsgRoundWinner(),
                    currentRound, winnerName, winGuess, target, null, null, null));

            getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                    + " won by " + winnerName
                    + " (guess=" + winGuess + ", target=" + target + ")");
        } else {
            broadcast(PluginConfig.color(wcfg.getMsgNoWinner()
                    .replace("%round%", String.valueOf(currentRound))));
        }

        // Decide next action
        if (currentRound >= wcfg.getRoundCount()) {
            getPlugin().getLogger().info("[WavelengthEvent] All rounds complete.");
            // Brief pause so players can read the round result before the winner screen
            Bukkit.getScheduler().runTaskLater(getPlugin(),
                    () -> getPlugin().getEventManager().stopCurrentEvent(), 60L);
        } else {
            // Pause between rounds — 3 seconds — then next round
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
        final String  raw         = event.getMessage().trim();

        // ── Override players: non-number chat passes through freely ──────────
        if (hasOverride && !isInteger(raw)) {
            approveMessage(player);
            event.setCancelled(false);
            return;
        }

        // ── All other messages are already cancelled by ChatListener.LOWEST ──
        // Guessing is not yet open (intro or between rounds) — nothing to do
        if (!live) {
            return;
        }

        // ── Not a number ─────────────────────────────────────────────────────
        if (!isInteger(raw)) {
            // Send a private hint on the main thread, keep cancelled
            final String hint = getPlugin().getPluginConfig().getWavelengthConfig().getMsgGuessAck()
                    .replace("%guess%", "?")
                    .replace("has been recorded!", "");
            final String invalidMsg = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cPlease type a number between \u00a7e1\u00a7c and \u00a7e100\u00a7c.";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(invalidMsg));
            return;
        }

        // ── Parse ─────────────────────────────────────────────────────────────
        final int guess;
        try {
            guess = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            return; // Overflow — silently ignore
        }

        // ── Range check (0–100; 0 is the very bottom of any scale) ───────────
        if (guess < 0 || guess > 100) {
            final String rangeMsg = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cOut of range! Please type a number between \u00a7e0\u00a7c and \u00a7e100\u00a7c.";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(rangeMsg));
            return;
        }

        // ── Participation check — all online players are active ───────────────
        final boolean isActive;
        synchronized (this) {
            isActive = activePlayers.contains(player.getUniqueId());
        }
        if (!isActive) {
            // Player joined after the event started — inform them privately
            final String lateMsg = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cYou joined after the event started and cannot participate in this round.";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(lateMsg));
            return;
        }

        // ── One guess per round ───────────────────────────────────────────────
        final boolean alreadyGuessed;
        synchronized (this) {
            alreadyGuessed = guesses.containsKey(player.getUniqueId());
            if (!alreadyGuessed) {
                guesses.put(player.getUniqueId(), guess);
            }
        }

        if (alreadyGuessed) {
            final String alreadyMsg = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cYou have already submitted a guess for this round!";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(alreadyMsg));
            return;
        }

        // ── Accepted — private ack only, never broadcast ──────────────────────
        final WavelengthConfig wcfg = getPlugin().getPluginConfig().getWavelengthConfig();
        final String ack = WavelengthConfig.format(
                wcfg.getMsgGuessAck(), -1, null, guess, -1, null, null, null);
        Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(ack));
    }

    // -----------------------------------------------------------------------
    // Helpers — main thread
    // -----------------------------------------------------------------------

    private void runRewardCommand(final WavelengthConfig wcfg, final String winnerName) {
        final String cmd = wcfg.getRewardCommand();
        if (cmd == null || cmd.isBlank()) return;
        // Already on main thread (called from onStop which is main-thread)
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                cmd.replace("%player%", winnerName));
        broadcast(WavelengthConfig.format(wcfg.getMsgReward(),
                -1, winnerName, -1, -1, null, null, null));
        getPlugin().getLogger().info(
                "[WavelengthEvent] Reward dispatched for " + winnerName);
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
     * Finds the UUID with the highest cumulative points.
     * Returns {@code null} if nobody scored in any round.
     * Must be called from the main thread (points is main-thread-only after init).
     */
    @Nullable
    private UUID findOverallWinner() {
        return points.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Resolves a player's display name from their UUID.
     * Tries online player first; falls back to UUID string if they went offline.
     */
    @Nullable
    private static String resolvePlayerName(@Nullable final UUID uuid) {
        if (uuid == null) return null;
        final Player p = Bukkit.getPlayer(uuid);
        return p != null ? p.getName() : uuid.toString();
    }

    /**
     * Returns true if {@code s} is a valid integer string (including negative).
     * Does not validate range.
     */
    private static boolean isInteger(final String s) {
        if (s == null || s.isEmpty()) return false;
        final int start = s.charAt(0) == '-' ? 1 : 0;
        if (start == s.length()) return false;
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Public accessors
    // -----------------------------------------------------------------------

    /** @return the current round number (1-indexed), or 0 if not started */
    public int getCurrentRound()  { return currentRound; }

    /** @return the secret target for the current round (0 if not live) */
    public int getTarget()        { return target; }

    /** @return the prompt text for the current round */
    public String getCurrentPrompt() { return currentPrompt; }

    /** @return a snapshot of this round's guesses, safe to call from any thread */
    public Map<UUID, Integer> getGuessesSnapshot() {
        synchronized (this) {
            return Collections.unmodifiableMap(new HashMap<>(guesses));
        }
    }

    /** @return a snapshot of active players, safe to call from any thread */
    public Set<UUID> getActivePlayersSnapshot() {
        synchronized (this) {
            return Collections.unmodifiableSet(new HashSet<>(activePlayers));
        }
    }
}
