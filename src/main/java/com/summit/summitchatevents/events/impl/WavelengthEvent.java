package com.summit.summitchatevents.events.impl;

import com.summit.summitchatevents.SummitChatEventsPlugin;
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
import org.jetbrains.annotations.NotNull;
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
import java.util.stream.Collectors;

/**
 * Wavelength — a multi-round number-placement event.
 *
 * <h3>How it works</h3>
 * Each round a random scale (e.g. "Worst mob ←→ Best mob") and a unique
 * prompt (e.g. "Creeper") are revealed. Players type a number 0–100 to
 * place the prompt on that scale. When the timer expires, the exact average
 * of all guesses is computed (as a double); the player(s) closest to that
 * average advance.
 *
 * <h3>Average calculation</h3>
 * Deltas are computed against the <em>exact</em> double-precision average —
 * never against a rounded integer — so two players equidistant from the
 * midpoint always produce a perfect tie.
 *
 * <h3>End-round outcome</h3>
 * <ul>
 *   <li><b>0 guesses</b> — no winner, event ends.</li>
 *   <li><b>1–2 tied players</b> — they win; event ends.</li>
 *   <li><b>3+ tied, round &lt; 3</b> — tied players continue; others are
 *       eliminated. A "Round 2" or "Final Round" context message is shown
 *       before the next scale/prompt reveal.</li>
 *   <li><b>3+ tied, round == 3</b> — all tied players win together.</li>
 * </ul>
 *
 * <h3>Prompt deduplication</h3>
 * Each prompt used during the event is tracked. Subsequent rounds never
 * repeat a prompt from the same event session. If all prompts are exhausted
 * the tracking set is cleared and prompts may repeat.
 *
 * <h3>Thread safety</h3>
 * {@link AsyncPlayerChatEvent} fires async. {@link #guesses} and
 * {@link #activePlayers} are guarded by {@code synchronized(this)}.
 * {@link #live} is {@code volatile}. Main-thread Bukkit calls are dispatched
 * via the scheduler when invoked from async context.
 */
public final class WavelengthEvent extends ChatEvent implements Listener {

    // -----------------------------------------------------------------------
    // Permission
    // -----------------------------------------------------------------------

    private static final String PERM_OVERRIDE = "summitevents.overridechat";

    // -----------------------------------------------------------------------
    // Timing constants (ticks; 20t = 1 s)
    // -----------------------------------------------------------------------

    private static final long T_RULES          = 60L;  // intro: 3 s after announce
    private static final long T_HERE_WE_GO     = 120L; // intro: 3 s after rules
    private static final long T_START          = 160L; // intro: 2 s after here-we-go
    private static final long T_ROUND_CONTEXT  = 40L;  // 2 s between context msg and scale
    private static final long T_SCALE_TO_PROMPT = 30L; // 1.5 s between scale and prompt
    private static final long T_PROMPT_TO_OPEN  = 20L; // 1 s between prompt and guessing open
    private static final long T_RESULT_PAUSE   = 60L;  // 3 s after result before next action

    // -----------------------------------------------------------------------
    // Max tie-break rounds
    // -----------------------------------------------------------------------

    private static final int MAX_ROUNDS = 3;

    // -----------------------------------------------------------------------
    // Game state
    // Collections guarded by synchronized(this).
    // Scalar fields / String fields are main-thread-only.
    // live is volatile.
    // -----------------------------------------------------------------------

    /** Players eligible to guess this round. Shrinks after each tie round. */
    private final Set<UUID>          activePlayers = new HashSet<>();

    /**
     * Guesses for the current round: UUID → value (0–100).
     * Written async under lock; read on main thread via snapshot.
     */
    private final Map<UUID, Integer> guesses = new HashMap<>();

    /**
     * Tracks all "scaleMin|prompt" pairs used this event to prevent repeats.
     * Main-thread-only.
     */
    private final Set<String>        usedPrompts = new HashSet<>();

    /**
     * Set by {@link #concludeEvent} before triggering stop; read by
     * {@link #onStop()} to announce the result.
     */
    @Nullable
    private List<UUID> finalWinners = null;

    private int    currentRound    = 0;
    private String currentScaleMin = "";
    private String currentScaleMax = "";
    private String currentPrompt   = "";

    /** True only while a round is actively accepting guesses. */
    private volatile boolean live = false;

    /** All pending scheduler tasks — cancelled together in {@link #onStop()}. */
    private final List<BukkitTask> tasks  = new ArrayList<>();
    private final Random           random = new Random();

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
        synchronized (this) {
            activePlayers.clear();
            guesses.clear();
            for (final Player p : Bukkit.getOnlinePlayers()) {
                activePlayers.add(p.getUniqueId());
            }
        }
        usedPrompts.clear();
        currentRound    = 0;
        currentScaleMin = "";
        currentScaleMax = "";
        currentPrompt   = "";
        finalWinners    = null;
        live            = false;
        tasks.clear();

        Bukkit.getPluginManager().registerEvents(this, getPlugin());
        scheduleIntro();
    }

    @Override
    protected void onStop() {
        live = false;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        HandlerList.unregisterAll(this);

        final WavelengthConfig wcfg    = getPlugin().getPluginConfig().getWavelengthConfig();
        final List<UUID>       winners = finalWinners;

        if (winners == null || winners.isEmpty()) {
            broadcast(wcfg.getMsgNoWinner());
        } else if (winners.size() == 1) {
            // Single clear winner
            final String name = resolvePlayerName(winners.get(0));
            broadcast(WavelengthConfig.format(wcfg.getMsgWinner(),
                    -1, name, -1, -1, null, null, null));
            runRewardCommands(wcfg, winners);
        } else {
            // 2+ tied winners — use the multiple-winners message
            final String playerList = buildNameList(winners);
            broadcast(WavelengthConfig.format(wcfg.getMsgMultipleWinners(),
                    -1, null, -1, -1, null, null, null, null, playerList));
            runRewardCommands(wcfg, winners);
        }

        getPlugin().getLogger().info("[WavelengthEvent] Ended after round "
                + currentRound + ". Winners: "
                + (winners != null && !winners.isEmpty() ? buildNameList(winners) : "none"));

        synchronized (this) {
            activePlayers.clear();
            guesses.clear();
        }
        usedPrompts.clear();
        finalWinners = null;
        currentRound = 0;
    }

    // -----------------------------------------------------------------------
    // Intro sequence — main thread
    // -----------------------------------------------------------------------

    private void scheduleIntro() {
        final WavelengthConfig wcfg = getPlugin().getPluginConfig().getWavelengthConfig();
        schedule(0L,           () -> broadcast(wcfg.getMsgAnnounce()));
        schedule(T_RULES,      () -> broadcast(wcfg.getMsgRules()));
        schedule(T_HERE_WE_GO, () -> broadcast(wcfg.getMsgHereWeGo()));
        schedule(T_START,      () -> startRound(null)); // no context message for round 1
    }

    // -----------------------------------------------------------------------
    // Round lifecycle — main thread
    // -----------------------------------------------------------------------

    /**
     * Starts the next round.
     *
     * @param contextMessage an optional message shown before the scale reveal
     *                       (e.g. "Round 2 — only tied players!"), or
     *                       {@code null} for the first round.
     */
    private void startRound(@Nullable final String contextMessage) {
        currentRound++;

        // ── Pick a unique prompt ─────────────────────────────────────────────
        final WavelengthConfig wcfg = getPlugin().getPluginConfig().getWavelengthConfig();
        final String[] picked = pickUniquePrompt(wcfg);
        currentScaleMin = picked[0];
        currentScaleMax = picked[1];
        currentPrompt   = picked[2];
        usedPrompts.add(currentScaleMin + "|" + currentPrompt);

        // ── Reset guesses ────────────────────────────────────────────────────
        synchronized (this) {
            guesses.clear();
        }
        live = false;

        // ── Build staggered message sequence ─────────────────────────────────
        final String scaleMsg  = WavelengthConfig.format(wcfg.getMsgScale(),
                -1, null, -1, -1, currentScaleMin, currentScaleMax, null);
        final String promptMsg = WavelengthConfig.format(wcfg.getMsgPrompt(),
                -1, null, -1, -1, null, null, currentPrompt);

        if (contextMessage != null) {
            // Context message → pause → scale → pause → prompt → open
            schedule(0L,                                              () -> broadcast(contextMessage));
            schedule(T_ROUND_CONTEXT,                                 () -> broadcast(scaleMsg));
            schedule(T_ROUND_CONTEXT + T_SCALE_TO_PROMPT,             () -> broadcast(promptMsg));
            schedule(T_ROUND_CONTEXT + T_SCALE_TO_PROMPT + T_PROMPT_TO_OPEN, () -> openRound(wcfg));
        } else {
            // No context — scale → pause → prompt → open (same as intro flow)
            schedule(0L,                    () -> broadcast(scaleMsg));
            schedule(T_SCALE_TO_PROMPT,     () -> broadcast(promptMsg));
            schedule(T_SCALE_TO_PROMPT + T_PROMPT_TO_OPEN, () -> openRound(wcfg));
        }

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " — '" + currentScaleMin + " <-> " + currentScaleMax
                + "' / '" + currentPrompt + "'");
    }

    /**
     * Selects a unique (scale, prompt) pair not yet used this event.
     * Falls back to resetting the used set if all prompts are exhausted.
     *
     * @return {@code String[]{scaleMin, scaleMax, prompt}}
     */
    private String[] pickUniquePrompt(final WavelengthConfig wcfg) {
        final List<WavelengthConfig.Scale> scales = wcfg.getScales();

        // Build the full pool of available (scale, prompt) pairs
        final List<String[]> pool = new ArrayList<>();
        for (final WavelengthConfig.Scale scale : scales) {
            for (final String prompt : scale.getPrompts()) {
                final String key = scale.getMin() + "|" + prompt;
                if (!usedPrompts.contains(key)) {
                    pool.add(new String[]{scale.getMin(), scale.getMax(), prompt});
                }
            }
        }

        // If exhausted, reset and use the full pool
        if (pool.isEmpty()) {
            usedPrompts.clear();
            getPlugin().getLogger().warning(
                    "[WavelengthEvent] All prompts used — resetting pool.");
            for (final WavelengthConfig.Scale scale : scales) {
                for (final String prompt : scale.getPrompts()) {
                    pool.add(new String[]{scale.getMin(), scale.getMax(), prompt});
                }
            }
        }

        return pool.get(random.nextInt(pool.size()));
    }

    /** Opens guessing, schedules countdown warnings, and starts the round timer. */
    private void openRound(final WavelengthConfig wcfg) {
        live = true;
        final long ticks    = wcfg.getRoundDurationTicks(currentRound);
        final long seconds  = ticks / 20L;

        // Countdown warnings at 30, 10, 5, 4, 3, 2, 1 seconds remaining
        final long[] warningSeconds = {30L, 10L, 5L, 4L, 3L, 2L, 1L};
        for (final long w : warningSeconds) {
            if (seconds > w) {
                final long fireTicks = ticks - (w * 20L);
                final String msg = wcfg.getMsgCountdown().replace("%seconds%", String.valueOf(w));
                schedule(fireTicks, () -> broadcast(msg));
            }
        }

        // Round timer — fires endRound
        schedule(ticks, this::endRound);

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " open — " + seconds + "s.");
    }

    /**
     * Called when the round timer fires.
     *
     * <h3>Average logic</h3>
     * The exact double-precision average is computed. Deltas are computed
     * against this exact value — so two players equidistant from the midpoint
     * always produce equal deltas and always tie, regardless of rounding.
     *
     * <h3>Display average</h3>
     * The average shown to players is rounded to one decimal place for
     * readability while still using the exact value for comparison.
     */
    private void endRound() {
        live = false;

        final WavelengthConfig   wcfg     = getPlugin().getPluginConfig().getWavelengthConfig();
        final Map<UUID, Integer> snapshot;
        synchronized (this) {
            snapshot = new HashMap<>(guesses);
        }

        // ── No guesses ───────────────────────────────────────────────────────
        if (snapshot.isEmpty()) {
            broadcast(wcfg.getMsgNoWinner());
            getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound + " — no guesses.");
            concludeEvent(Collections.emptyList());
            return;
        }

        // ── Exact double average ─────────────────────────────────────────────
        final double exactAverage = snapshot.values().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);

        // Display as one decimal place (e.g. 50.0 or 47.5)
        final String avgDisplay = String.format("%.1f", exactAverage);

        // ── Compute deltas against exact average ─────────────────────────────
        double minDelta = Double.MAX_VALUE;
        for (final int guess : snapshot.values()) {
            final double delta = Math.abs(guess - exactAverage);
            if (delta < minDelta) minDelta = delta;
        }

        // ── Collect all players tied at minDelta ─────────────────────────────
        // Use a small epsilon for floating-point equality
        final double epsilon = 1e-9;
        final List<UUID> tied = new ArrayList<>();
        for (final Map.Entry<UUID, Integer> entry : snapshot.entrySet()) {
            if (Math.abs(Math.abs(entry.getValue() - exactAverage) - minDelta) < epsilon) {
                tied.add(entry.getKey());
            }
        }

        // ── Dramatic build-up, then result ───────────────────────────────────
        final String repName  = resolvePlayerName(tied.get(0));
        final int    repGuess = snapshot.get(tied.get(0));
        final String resultMsg = WavelengthConfig.format(wcfg.getMsgRoundResult(),
                -1, repName, repGuess, -1, null, null, null, avgDisplay, null);

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " — exactAvg=" + exactAverage
                + " minDelta=" + minDelta
                + " tied=" + tied.size());

        // Dramatic message first, then result after a short pause
        broadcast(wcfg.getMsgGuessingOver());
        schedule(60L, () -> broadcast(resultMsg)); // 3 s of suspense

        // ── Apply outcome rules ───────────────────────────────────────────────
        final int tiedCount = tied.size();

        if (tiedCount <= 2) {
            // 1 or 2 players — clear winner(s), end event
            schedule(T_RESULT_PAUSE + 60L, () -> concludeEvent(tied));

        } else if (currentRound < MAX_ROUNDS) {
            // 3+ tied, more rounds available — narrow down to tied players only
            final String playerList = buildNameList(tied);

            // Build the round-context message for the next round
            final String nextContextMsg;
            if (currentRound + 1 == MAX_ROUNDS) {
                // Next round is the final
                nextContextMsg = WavelengthConfig.format(wcfg.getMsgFinalRound(),
                        -1, null, -1, -1, null, null, null, null, playerList);
            } else {
                // Next round is round 2
                nextContextMsg = WavelengthConfig.format(wcfg.getMsgRound2Start(),
                        -1, null, -1, -1, null, null, null, null, playerList);
            }

            // Send private DMs
            final Set<UUID> tiedSet = new HashSet<>(tied);
            for (final UUID uuid : new HashSet<>(activePlayers)) { // snapshot to avoid CME
                final Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                if (tiedSet.contains(uuid)) {
                    p.sendMessage(wcfg.getMsgTieAdvanced());
                } else {
                    p.sendMessage(wcfg.getMsgTieEliminated());
                }
            }

            // Narrow active players
            synchronized (this) {
                activePlayers.clear();
                activePlayers.addAll(tied);
            }

            // After a brief pause, start the next round with the context message
            schedule(T_RESULT_PAUSE + 60L, () -> startRound(nextContextMsg));

        } else {
            // 3+ tied on the final round — everyone ties wins
            schedule(T_RESULT_PAUSE + 60L, () -> concludeEvent(tied));
        }
    }

    /**
     * Stores the final winners and triggers the manager to stop the event.
     * {@link #onStop()} will read {@link #finalWinners} to announce results.
     */
    private void concludeEvent(final @NotNull List<UUID> winners) {
        finalWinners = new ArrayList<>(winners);
        Bukkit.getScheduler().runTaskLater(getPlugin(),
                () -> getPlugin().getEventManager().stopCurrentEvent(), T_RESULT_PAUSE);
    }

    // -----------------------------------------------------------------------
    // Chat handler — async thread
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        final Player  player      = event.getPlayer();
        final boolean hasOverride = player.hasPermission(PERM_OVERRIDE);
        final String  raw         = event.getMessage().trim();

        // Override players: non-number chat passes through freely
        if (hasOverride && !isInteger(raw)) {
            approveMessage(player);
            event.setCancelled(false);
            return;
        }

        // Round not open — keep cancelled, no feedback during intros
        if (!live) return;

        // Not a number
        if (!isInteger(raw)) {
            final String hint = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cPlease type a number between \u00a7e0\u00a7c and \u00a7e100\u00a7c.";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(hint));
            return;
        }

        final int guess;
        try {
            guess = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            return;
        }

        // Range check
        if (guess < 0 || guess > 100) {
            final String rangeMsg = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cOut of range! Type a number between \u00a7e0\u00a7c and \u00a7e100\u00a7c.";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(rangeMsg));
            return;
        }

        // Active-player check — only players in the current round's active set can guess
        final boolean isActive;
        synchronized (this) {
            isActive = activePlayers.contains(player.getUniqueId());
        }
        if (!isActive) {
            // Silently cancel — they can see the chat is blocked, no extra noise needed
            return;
        }

        // One guess per round — atomic check-and-store
        final boolean alreadyGuessed;
        synchronized (this) {
            alreadyGuessed = guesses.containsKey(player.getUniqueId());
            if (!alreadyGuessed) guesses.put(player.getUniqueId(), guess);
        }

        if (alreadyGuessed) {
            final String alreadyMsg = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cYou have already submitted a guess this round!";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(alreadyMsg));
            return;
        }

        // Accepted — private ack, never broadcast
        final String ack = WavelengthConfig.format(
                getPlugin().getPluginConfig().getWavelengthConfig().getMsgGuessAck(),
                -1, null, guess, -1, null, null, null);
        Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(ack));
    }

    // -----------------------------------------------------------------------
    // Helpers — main thread
    // -----------------------------------------------------------------------

    /**
     * Runs the reward command for every winner in the list, then broadcasts
     * a single combined reward message.
     *
     * <ul>
     *   <li>One winner  → uses the single {@code reward} message with {@code %player%}.</li>
     *   <li>Two+ winners → uses {@code reward-multiple} with {@code %players%}.</li>
     * </ul>
     */
    private void runRewardCommands(final WavelengthConfig wcfg, final List<UUID> winners) {
        final String cmd = wcfg.getRewardCommand();
        if (cmd != null && !cmd.isBlank()) {
            for (final UUID uuid : winners) {
                final String name = resolvePlayerName(uuid);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        cmd.replace("%player%", name));
                getPlugin().getLogger().info(
                        "[WavelengthEvent] Reward dispatched for " + name);
            }
        }

        // One combined broadcast after all commands have run
        if (winners.size() == 1) {
            final String name = resolvePlayerName(winners.get(0));
            broadcast(WavelengthConfig.format(wcfg.getMsgReward(),
                    -1, name, -1, -1, null, null, null));
        } else {
            final String playerList = buildNameList(winners);
            broadcast(wcfg.getMsgRewardMultiple().replace("%players%", playerList));
        }
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

    private static String buildNameList(final List<UUID> uuids) {
        return uuids.stream()
                .map(WavelengthEvent::resolvePlayerName)
                .collect(Collectors.joining(", "));
    }

    // -----------------------------------------------------------------------
    // Helpers — thread-agnostic
    // -----------------------------------------------------------------------

    @NotNull
    private static String resolvePlayerName(@Nullable final UUID uuid) {
        if (uuid == null) return "Unknown";
        final Player p = Bukkit.getPlayer(uuid);
        return p != null ? p.getName() : uuid.toString();
    }

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

    public int    getCurrentRound()  { return currentRound; }
    public String getCurrentPrompt() { return currentPrompt; }

    public Map<UUID, Integer> getGuessesSnapshot() {
        synchronized (this) {
            return Collections.unmodifiableMap(new HashMap<>(guesses));
        }
    }

    public Set<UUID> getActivePlayersSnapshot() {
        synchronized (this) {
            return Collections.unmodifiableSet(new HashSet<>(activePlayers));
        }
    }
}
