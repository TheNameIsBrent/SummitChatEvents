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
 * Each round a random scale (e.g. "Worst mob ←→ Best mob") and a prompt
 * (e.g. "Creeper") are revealed. Players type a number 0–100 to place the
 * prompt on that scale. When the round timer expires, the average of all
 * guesses is computed; the player(s) closest to that average advance.
 *
 * <h3>End-round outcome</h3>
 * <ul>
 *   <li><b>0 guesses</b> — no winner, event ends.</li>
 *   <li><b>1–2 tied players</b> — they win; event ends.</li>
 *   <li><b>3+ tied players, round &lt; 3</b> — tied players only continue to
 *       the next round. Eliminated players receive a private message.
 *       Advancing players receive a private message. No round number is
 *       broadcast (just the scale and prompt for the next round).</li>
 *   <li><b>3+ tied players, round == 3</b> — all tied players win together.</li>
 * </ul>
 *
 * <h3>Participation</h3>
 * Every online player is added to {@link #activePlayers} on start — no
 * permission required. Players with {@value #PERM_OVERRIDE} can bypass the
 * chat block while still participating normally.
 *
 * <h3>Thread safety</h3>
 * {@link AsyncPlayerChatEvent} fires async. {@link #guesses} and
 * {@link #activePlayers} are guarded by {@code synchronized(this)}.
 * {@link #live} is {@code volatile}. All Bukkit calls that require the main
 * thread are dispatched via {@code runTask()}.
 */
public final class WavelengthEvent extends ChatEvent implements Listener {

    // -----------------------------------------------------------------------
    // Permission
    // -----------------------------------------------------------------------

    private static final String PERM_OVERRIDE = "summitevents.overridechat";

    // -----------------------------------------------------------------------
    // Intro timing (ticks; 20t = 1 s)
    // -----------------------------------------------------------------------

    private static final long T_RULES       = 60L;  // 3 s after announce
    private static final long T_HERE_WE_GO  = 120L; // 3 s after rules
    private static final long T_START       = 160L; // 2 s after here-we-go

    // -----------------------------------------------------------------------
    // Round reveal timing — staggered for dramatic effect
    // -----------------------------------------------------------------------

    /** Delay between scale reveal and prompt reveal (ticks). */
    private static final long T_SCALE_TO_PROMPT = 30L;  // 1.5 s
    /** Delay after prompt before guessing opens (ticks). */
    private static final long T_PROMPT_TO_OPEN  = 20L;  // 1 s

    // -----------------------------------------------------------------------
    // Max tie-break rounds
    // -----------------------------------------------------------------------

    private static final int MAX_ROUNDS = 3;

    // -----------------------------------------------------------------------
    // Game state
    // Collections guarded by synchronized(this).
    // Scalar fields and currentScale*/currentPrompt are main-thread-only.
    // live is volatile — written on main thread, read on async.
    // -----------------------------------------------------------------------

    /** Players still eligible to guess this round. Replaced after each tie. */
    private final Set<UUID>          activePlayers = new HashSet<>();

    /**
     * Guesses for the current round: UUID → value (0–100).
     * Written from async chat thread under lock; snapshot taken on main thread.
     */
    private final Map<UUID, Integer> guesses       = new HashMap<>();

    /**
     * Final winners populated by {@link #endRound()} before the event stops.
     * Null until the event concludes. Used by {@link #onStop()} to announce.
     */
    @Nullable
    private List<UUID> finalWinners = null;

    private int    currentRound    = 0;
    private String currentScaleMin = "";
    private String currentScaleMax = "";
    private String currentPrompt   = "";

    private volatile boolean live = false;

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
            final String name = resolvePlayerName(winners.get(0));
            broadcast(WavelengthConfig.format(wcfg.getMsgWinner(),
                    -1, name, -1, -1, null, null, null));
            runRewardCommand(wcfg, name);
        } else {
            // Multiple winners
            final String playerList = buildNameList(winners);
            broadcast(WavelengthConfig.format(wcfg.getMsgMultipleWinners(),
                    -1, null, -1, -1, null, null, null, null, playerList));
            for (final UUID uuid : winners) {
                runRewardCommand(wcfg, resolvePlayerName(uuid));
            }
        }

        getPlugin().getLogger().info("[WavelengthEvent] Event ended after round "
                + currentRound + ". Winners: "
                + (winners != null ? buildNameList(winners) : "none"));

        synchronized (this) {
            activePlayers.clear();
            guesses.clear();
        }
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
        schedule(T_START,      this::startRound);
    }

    // -----------------------------------------------------------------------
    // Round lifecycle — main thread
    // -----------------------------------------------------------------------

    /**
     * Starts the next round.
     *
     * <p>Picks a random scale and prompt, clears guesses, then broadcasts
     * the scale and (after a stagger) the prompt — no round number is shown.
     * After the full reveal, {@link #openRound(WavelengthConfig)} is called
     * to set {@link #live} and schedule the round timer.
     */
    private void startRound() {
        currentRound++;
        final WavelengthConfig       wcfg  = getPlugin().getPluginConfig().getWavelengthConfig();
        final WavelengthConfig.Scale scale = wcfg.randomScale(random);

        currentScaleMin = scale.getMin();
        currentScaleMax = scale.getMax();
        currentPrompt   = scale.randomPrompt(random);

        synchronized (this) {
            guesses.clear();
        }
        live = false;

        final String scaleMsg  = WavelengthConfig.format(wcfg.getMsgScale(),
                -1, null, -1, -1, currentScaleMin, currentScaleMax, null);
        final String promptMsg = WavelengthConfig.format(wcfg.getMsgPrompt(),
                -1, null, -1, -1, null, null, currentPrompt);

        schedule(0L,                    () -> broadcast(scaleMsg));
        schedule(T_SCALE_TO_PROMPT,     () -> broadcast(promptMsg));
        schedule(T_SCALE_TO_PROMPT + T_PROMPT_TO_OPEN, () -> openRound(wcfg));

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " — Scale: '" + currentScaleMin + " <-> " + currentScaleMax
                + "' Prompt: '" + currentPrompt + "'");
    }

    /** Opens the round for guesses and starts the countdown timer. */
    private void openRound(final WavelengthConfig wcfg) {
        live = true;
        final long ticks = wcfg.getRoundDurationTicks(currentRound);
        schedule(ticks, this::endRound);
        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " open — " + (ticks / 20L) + "s.");
    }

    /**
     * Called when the round timer expires.
     *
     * <h3>Steps</h3>
     * <ol>
     *   <li>Snapshot guesses.</li>
     *   <li>Compute the average (sum / count), rounded to nearest integer.</li>
     *   <li>Compute each player's {@code |guess − average|}.</li>
     *   <li>Find the minimum delta and collect all players tied at that delta.</li>
     *   <li>Broadcast the average and the closest player's guess.</li>
     *   <li>Apply outcome rules based on tie count and round number.</li>
     * </ol>
     */
    private void endRound() {
        live = false;

        final WavelengthConfig   wcfg     = getPlugin().getPluginConfig().getWavelengthConfig();
        final Map<UUID, Integer> snapshot;
        synchronized (this) {
            snapshot = new HashMap<>(guesses);
        }

        // ── No guesses at all ────────────────────────────────────────────────
        if (snapshot.isEmpty()) {
            broadcast(wcfg.getMsgNoWinner());
            getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                    + " — no guesses.");
            concludeEvent(Collections.emptyList());
            return;
        }

        // ── Compute average ──────────────────────────────────────────────────
        final int sum     = snapshot.values().stream().mapToInt(Integer::intValue).sum();
        final int count   = snapshot.size();
        final int average = (int) Math.round((double) sum / count);

        // ── Compute deltas and find minimum ──────────────────────────────────
        int minDelta = Integer.MAX_VALUE;
        for (final int guess : snapshot.values()) {
            final int delta = Math.abs(guess - average);
            if (delta < minDelta) minDelta = delta;
        }

        // ── Collect all players tied at minimum delta ─────────────────────────
        final List<UUID> tied = new ArrayList<>();
        for (final Map.Entry<UUID, Integer> entry : snapshot.entrySet()) {
            if (Math.abs(entry.getValue() - average) == minDelta) {
                tied.add(entry.getKey());
            }
        }

        // ── Broadcast result — average + closest player ───────────────────────
        // Use the first tied player as the representative for the round-result message
        final UUID   repUuid = tied.get(0);
        final String repName = resolvePlayerName(repUuid);
        final int    repGuess = snapshot.get(repUuid);

        broadcast(WavelengthConfig.format(wcfg.getMsgRoundResult(),
                -1, repName, repGuess, -1, null, null, null, String.valueOf(average), null));

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " — average=" + average + " minDelta=" + minDelta
                + " tiedPlayers=" + tied.size());

        // ── Apply outcome rules ───────────────────────────────────────────────
        final int tiedCount = tied.size();

        if (tiedCount <= 2) {
            // Case 1: 1 or 2 players — event ends, they win
            concludeEvent(tied);

        } else if (currentRound < MAX_ROUNDS) {
            // Case 2: 3+ tied AND more rounds available — only tied players continue
            final String playerList = buildNameList(tied);
            broadcast(WavelengthConfig.format(wcfg.getMsgTieContinues(),
                    -1, null, -1, -1, null, null, null, null, playerList));

            // Private messages
            final Set<UUID> tiedSet = new HashSet<>(tied);
            for (final UUID uuid : activePlayers) {
                final Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                if (tiedSet.contains(uuid)) {
                    p.sendMessage(wcfg.getMsgTieAdvanced());
                } else {
                    p.sendMessage(wcfg.getMsgTieEliminated());
                }
            }

            // Replace active players with only the tied players
            synchronized (this) {
                activePlayers.clear();
                activePlayers.addAll(tied);
            }

            // Start next round after a short pause
            schedule(80L, this::startRound); // 4-second gap

        } else {
            // Case 3: 3+ tied AND round == MAX_ROUNDS — all tied players win
            concludeEvent(tied);
        }
    }

    /**
     * Sets {@link #finalWinners} and delegates to the manager to call
     * {@link #onStop()}, which reads {@code finalWinners} to announce results.
     *
     * @param winners the UUIDs who won; empty list means no winner
     */
    private void concludeEvent(final @NotNull List<UUID> winners) {
        finalWinners = new ArrayList<>(winners);
        // Small pause so players can read the round result before the outro
        Bukkit.getScheduler().runTaskLater(getPlugin(),
                () -> getPlugin().getEventManager().stopCurrentEvent(), 60L);
    }

    // -----------------------------------------------------------------------
    // Chat handler — async thread
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        final Player  player      = event.getPlayer();
        final boolean hasOverride = player.hasPermission(PERM_OVERRIDE);
        final String  raw         = event.getMessage().trim();

        // Override players: non-number chat passes through
        if (hasOverride && !isInteger(raw)) {
            approveMessage(player);
            event.setCancelled(false);
            return;
        }

        // Game not open — keep cancelled (no message needed during intros)
        if (!live) return;

        // Not a number — hint the player
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
                    + "\u00a7cOut of range! Please type a number between \u00a7e0\u00a7c and \u00a7e100\u00a7c.";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(rangeMsg));
            return;
        }

        // Active-player check
        final boolean isActive;
        synchronized (this) {
            isActive = activePlayers.contains(player.getUniqueId());
        }
        if (!isActive) {
            final String lateMsg = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cYou are not participating in this round.";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(lateMsg));
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
                    + "\u00a7cYou have already submitted a guess for this round!";
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

    private void runRewardCommand(final WavelengthConfig wcfg, final String winnerName) {
        final String cmd = wcfg.getRewardCommand();
        if (cmd == null || cmd.isBlank()) return;
        // onStop() is already on the main thread
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

    /**
     * Builds a comma-separated list of player names from a collection of UUIDs.
     * Resolves online names; falls back to UUID string for offline players.
     */
    private static String buildNameList(final List<UUID> uuids) {
        return uuids.stream()
                .map(WavelengthEvent::resolvePlayerName)
                .collect(Collectors.joining(", "));
    }

    // -----------------------------------------------------------------------
    // Helpers — thread-agnostic
    // -----------------------------------------------------------------------

    @Nullable
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
