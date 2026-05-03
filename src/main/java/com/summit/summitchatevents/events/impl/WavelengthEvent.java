package com.summit.summitchatevents.events.impl;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.config.PluginConfig;
import com.summit.summitchatevents.config.WavelengthConfig;
import com.summit.summitchatevents.events.ChatEvent;
import com.summit.summitchatevents.listeners.ChatListener;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
 * Wavelength — multi-round number-placement event.
 *
 * <h3>Round flow</h3>
 * <ol>
 *   <li>Big banner is broadcast to all players.</li>
 *   <li>Scale and prompt are revealed with staggered timing.</li>
 *   <li>Players type a number 0–100 (private ack, never broadcast).</li>
 *   <li>When the timer expires the exact-double average is computed; the
 *       player(s) closest to it are the round winners.</li>
 *   <li>1–2 closest → event ends; 3+ → tied players continue to next round.</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * {@code AsyncPlayerChatEvent} fires async. {@link #guesses} and
 * {@link #activePlayers} are guarded by {@code synchronized(this)}.
 * {@link #live} is {@code volatile}. All Bukkit API calls that require the
 * main thread are dispatched via {@code runTask()} from async context.
 */
public final class WavelengthEvent extends ChatEvent implements Listener {

    // -----------------------------------------------------------------------
    // Permission
    // -----------------------------------------------------------------------

    private static final String PERM_OVERRIDE = "summitevents.overridechat";

    // -----------------------------------------------------------------------
    // Timing (ticks; 20t = 1 s)
    // -----------------------------------------------------------------------

    private static final long T_RULES           = 60L;  // 3 s after announce
    private static final long T_HERE_WE_GO      = 120L; // 3 s after rules
    private static final long T_START           = 160L; // 2 s after here-we-go
    private static final long T_ROUND_CONTEXT   = 40L;  // 2 s between context and scale
    private static final long T_SCALE_TO_PROMPT = 30L;  // 1.5 s
    private static final long T_PROMPT_TO_OPEN  = 20L;  // 1 s
    private static final long T_REVEAL_BUILDUP  = 60L;  // 3 s between "here comes..." and result
    private static final long T_BETWEEN_ROUNDS  = 60L;  // 3 s gap between rounds

    private static final int  MAX_ROUNDS        = 3;

    // -----------------------------------------------------------------------
    // State — collections guarded by synchronized(this)
    // -----------------------------------------------------------------------

    private final Set<UUID>          activePlayers = new HashSet<>();
    private final Map<UUID, Integer> guesses       = new HashMap<>();
    private final Set<String>        usedPrompts   = new HashSet<>();

    @Nullable private List<UUID>   finalWinners  = null;
    @Nullable private String       finalAverage  = null; // average from the deciding round
    /** Set to true by the stop command — suppresses result announcement. */
    private volatile boolean      stoppedByAdmin = false;

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
    public int getMinPlayers() {
        return getPlugin().getPluginConfig().getWavelengthConfig().getMinPlayers();
    }

    @Override
    protected void onStart() {
        // Reset everything before registering anything
        live           = false;
        currentRound   = 0;
        finalWinners   = null;
        stoppedByAdmin = false;
        currentScaleMin = currentScaleMax = currentPrompt = "";
        tasks.clear();
        usedPrompts.clear();

        synchronized (this) {
            activePlayers.clear();
            guesses.clear();
            for (final Player p : Bukkit.getOnlinePlayers()) {
                activePlayers.add(p.getUniqueId());
            }
        }

        Bukkit.getPluginManager().registerEvents(this, getPlugin());
        scheduleIntro();
    }

    @Override
    protected void onStop() {
        // 1. Freeze game state immediately
        live = false;

        // 2. Cancel all pending tasks (timers, countdowns, transitions)
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();

        // 3. Unregister listeners
        HandlerList.unregisterAll(this);

        // 4. Announce result
        final WavelengthConfig wcfg    = getPlugin().getPluginConfig().getWavelengthConfig();
        final List<UUID>       winners = finalWinners;

        if (stoppedByAdmin) {
            // Event was force-stopped — no result announced (stop command already broadcast the message)
        } else if (winners == null || winners.isEmpty()) {
            broadcast(wcfg.getMsgNoWinner());
        } else if (winners.size() == 1) {
            announceWinners(wcfg, winners);
            runRewardCommands(wcfg, winners);
        } else {
            announceWinners(wcfg, winners);
            runRewardCommands(wcfg, winners);
        }

        getPlugin().getLogger().info("[WavelengthEvent] Ended after round " + currentRound
                + ". Winners: " + (winners != null && !winners.isEmpty()
                    ? buildNameList(winners) : "none"));

        // 5. Reset state completely
        synchronized (this) {
            activePlayers.clear();
            guesses.clear();
        }
        usedPrompts.clear();
        finalWinners    = null;
        finalAverage    = null;
        stoppedByAdmin  = false;
        currentRound    = 0;
        currentScaleMin = currentScaleMax = currentPrompt = "";
    }

    // -----------------------------------------------------------------------
    // Intro sequence — main thread
    // -----------------------------------------------------------------------

    private void scheduleIntro() {
        final WavelengthConfig wcfg = getPlugin().getPluginConfig().getWavelengthConfig();

        schedule(0L, () -> {
            broadcast(wcfg.getMsgBannerTop());
            broadcast(wcfg.getMsgAnnounce());
            final String prize = wcfg.getRewardDisplayName();
            broadcast(wcfg.getMsgPrizeLine().replace("%prize%", prize));
            broadcast(wcfg.getMsgBannerBottom());
        });
        schedule(T_RULES,  () -> broadcast(wcfg.getMsgRules()));
        schedule(T_START,  () -> startRound(null));
    }

    // -----------------------------------------------------------------------
    // Round lifecycle — main thread
    // -----------------------------------------------------------------------

    /**
     * Starts the next round, optionally preceded by a context message
     * (e.g. "Tie! Round 2 — only these players continue…").
     */
    private void startRound(@Nullable final String contextMessage) {
        currentRound++;

        final WavelengthConfig       wcfg  = getPlugin().getPluginConfig().getWavelengthConfig();
        final String[]               pick  = pickUniquePrompt(wcfg);
        currentScaleMin = pick[0];
        currentScaleMax = pick[1];
        currentPrompt   = pick[2];
        usedPrompts.add(currentScaleMin + "|" + currentPrompt);

        synchronized (this) { guesses.clear(); }
        live = false;

        final String scaleMsg  = WavelengthConfig.format(wcfg.getMsgScale(),
                -1, null, -1, -1, currentScaleMin, currentScaleMax, null);
        final String promptMsg = WavelengthConfig.format(wcfg.getMsgPrompt(),
                -1, null, -1, -1, null, null, currentPrompt);

        // T_HERE_WE_GO_ROUND: pause between prompt reveal and "Here we go!" before opening
        final long T_HERE_WE_GO_ROUND = 30L; // 1.5 s
        final String hereWeGoMsg = wcfg.getMsgHereWeGo();

        if (contextMessage != null) {
            // context → scale → prompt → here-we-go → open
            final long s0 = 0L;
            final long s1 = s0 + T_ROUND_CONTEXT;
            final long s2 = s1 + T_SCALE_TO_PROMPT;
            final String areYouReadyMsg2 = wcfg.getMsgAreYouReady();
            final long s3 = s2 + T_HERE_WE_GO_ROUND;
            final long s4 = s3 + T_HERE_WE_GO_ROUND;
            final long s5 = s4 + T_HERE_WE_GO_ROUND;
            schedule(s0, () -> broadcast(contextMessage));
            schedule(s1, () -> broadcast(scaleMsg));
            schedule(s2, () -> broadcast(promptMsg));
            schedule(s3, () -> broadcast(areYouReadyMsg2));
            schedule(s4, () -> broadcast(hereWeGoMsg));
            schedule(s5, () -> openRound(wcfg));
        } else {
            // scale → prompt → are-you-ready → here-we-go → open
            final String areYouReadyMsg = wcfg.getMsgAreYouReady();
            final long s0 = 0L;
            final long s1 = s0 + T_SCALE_TO_PROMPT;
            final long s2 = s1 + T_HERE_WE_GO_ROUND;
            final long s3 = s2 + T_HERE_WE_GO_ROUND;
            final long s4 = s3 + T_HERE_WE_GO_ROUND;
            schedule(s0, () -> broadcast(scaleMsg));
            schedule(s1, () -> broadcast(promptMsg));
            schedule(s2, () -> broadcast(areYouReadyMsg));
            schedule(s3, () -> broadcast(hereWeGoMsg));
            schedule(s4, () -> openRound(wcfg));
        }

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " — '" + currentScaleMin + " <-> " + currentScaleMax
                + "' Prompt: '" + currentPrompt + "'");
    }

    /** Opens guessing, schedules countdown warnings, and starts the round timer. */
    private void openRound(final WavelengthConfig wcfg) {
        live = true;
        final long roundTicks = wcfg.getRoundDurationTicks(currentRound);
        final long roundSecs  = roundTicks / 20L;

        // Countdown warnings — only schedule those that fit within the round
        for (final long w : new long[]{30L, 10L, 5L, 4L, 3L, 2L, 1L}) {
            if (roundSecs > w) {
                final String msg = wcfg.getMsgCountdown().replace("%seconds%", String.valueOf(w));
                schedule(roundTicks - w * 20L, () -> broadcast(msg));
            }
        }

        schedule(roundTicks, this::endRound);

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " open — " + roundSecs + "s.");
    }

    /** Called when the round timer fires. Computes result and applies outcome logic. */
    private void endRound() {
        live = false;

        // Play end-of-round sound on the main thread
        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.8f);
        }

        final WavelengthConfig   wcfg = getPlugin().getPluginConfig().getWavelengthConfig();
        final Map<UUID, Integer> snap;
        synchronized (this) { snap = new HashMap<>(guesses); }

        // ── No guesses ───────────────────────────────────────────────────────
        if (snap.isEmpty()) {
            getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound + " — no guesses.");
            concludeEvent(Collections.emptyList(), null); // onStop will broadcast no-winner
            return;
        }

        // ── Exact double average (never rounded for comparison) ───────────────
        final double exactAvg = snap.values().stream()
                .mapToInt(Integer::intValue).average().orElse(0.0);
        final String avgDisplay = String.format("%.1f", exactAvg);

        // ── Find minimum delta with epsilon guard ─────────────────────────────
        double minDelta = Double.MAX_VALUE;
        for (final int g : snap.values()) {
            final double d = Math.abs(g - exactAvg);
            if (d < minDelta) minDelta = d;
        }

        final double epsilon = 1e-9;
        final List<UUID> tied = new ArrayList<>();
        final Map<UUID, Integer> tiedGuesses = new HashMap<>();
        for (final Map.Entry<UUID, Integer> e : snap.entrySet()) {
            if (Math.abs(Math.abs(e.getValue() - exactAvg) - minDelta) < epsilon) {
                tied.add(e.getKey());
                tiedGuesses.put(e.getKey(), e.getValue());
            }
        }

        // ── Build result message ─────────────────────────────────────────────
        // Tie player list uses § codes directly so they survive inside a pre-translated string
        final String resultMsg;
        if (tied.size() == 1) {
            final UUID u = tied.get(0);
            resultMsg = WavelengthConfig.format(wcfg.getMsgRoundResultSingle(),
                    -1, resolvePlayerName(u), -1, -1,
                    null, null, null, avgDisplay, null);
        } else {
            // Build tied-players string using § codes (string is already colour-translated)
            final String tiedStr = tied.stream()
                    .map(WavelengthEvent::resolvePlayerName)
                    .collect(Collectors.joining("\u00a7e, "));
            resultMsg = WavelengthConfig.format(wcfg.getMsgRoundResultTie(),
                    -1, null, -1, -1, null, null, null, avgDisplay, tiedStr);
        }

        // Dramatic build-up — show result only for tie-continue cases
        // For game-ending (tied.size() <= 2), the announceWinners banner handles it
        broadcast(wcfg.getMsgGuessingOver());
        final boolean isContinuing = tied.size() > 2 && currentRound < MAX_ROUNDS;
        if (isContinuing) {
            schedule(T_REVEAL_BUILDUP, () -> broadcast(resultMsg));
        }

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " — avg=" + exactAvg + " minDelta=" + minDelta + " tied=" + tied.size());

        // ── Outcome logic ─────────────────────────────────────────────────────
        final long baseDelay = (tied.size() > 2 && currentRound < MAX_ROUNDS)
                ? T_REVEAL_BUILDUP + 20L  // after result message (tie-continue)
                : T_REVEAL_BUILDUP;       // just after "here comes..." (game-ending)

        if (tied.size() <= 2) {
            // For single winner or 2-player tie ending the game:
            // pass avgDisplay so the final banner can show it
            final String capturedAvg = avgDisplay;
            schedule(baseDelay, () -> concludeEvent(tied, capturedAvg));

        } else if (currentRound < MAX_ROUNDS) {
            // 3+ tied — narrow to tied players, continue
            final String playerList = buildNameList(tied);
            final String nextCtx = (currentRound + 1 == MAX_ROUNDS)
                    ? WavelengthConfig.format(wcfg.getMsgFinalRound(),
                            -1, null, -1, -1, null, null, null, null, playerList)
                    : WavelengthConfig.format(wcfg.getMsgRound2Start(),
                            -1, null, -1, -1, null, null, null, null, playerList);

            // Snapshot before scheduling — collections must not be read on another thread
            final Set<UUID> tiedSet   = new HashSet<>(tied);
            final Set<UUID> allActive;
            synchronized (this) { allActive = new HashSet<>(activePlayers); }

            // Send private DMs and start next round AFTER the tie result message is visible
            schedule(baseDelay, () -> {
                for (final UUID uuid : allActive) {
                    final Player p = Bukkit.getPlayer(uuid);
                    if (p == null) continue;
                    p.sendMessage(tiedSet.contains(uuid)
                            ? wcfg.getMsgTieAdvanced()
                            : wcfg.getMsgTieEliminated());
                }
                synchronized (this) {
                    activePlayers.clear();
                    activePlayers.addAll(tiedSet);
                }
            });

            schedule(baseDelay + T_BETWEEN_ROUNDS, () -> startRound(nextCtx));

        } else {
            // Final round, 3+ tied — all win together
            final String capturedAvg2 = avgDisplay;
            schedule(baseDelay, () -> concludeEvent(tied, capturedAvg2));
        }
    }

    /** Sets final winners and average, then schedules the manager stop. */
    private void concludeEvent(final @NotNull List<UUID> winners, final @Nullable String average) {
        finalWinners = new ArrayList<>(winners);
        finalAverage = average;
        Bukkit.getScheduler().runTaskLater(getPlugin(),
                () -> getPlugin().getEventManager().stopCurrentEvent(), 40L);
    }

    // -----------------------------------------------------------------------
    // Prompt selection — main thread
    // -----------------------------------------------------------------------

    private String[] pickUniquePrompt(final WavelengthConfig wcfg) {
        final List<WavelengthConfig.Scale> scales = wcfg.getScales();
        final List<String[]> pool = new ArrayList<>();
        for (final WavelengthConfig.Scale s : scales) {
            for (final String p : s.getPrompts()) {
                if (!usedPrompts.contains(s.getMin() + "|" + p)) {
                    pool.add(new String[]{s.getMin(), s.getMax(), p});
                }
            }
        }
        if (pool.isEmpty()) {
            usedPrompts.clear();
            getPlugin().getLogger().warning("[WavelengthEvent] All prompts used — resetting pool.");
            for (final WavelengthConfig.Scale s : scales) {
                for (final String p : s.getPrompts()) {
                    pool.add(new String[]{s.getMin(), s.getMax(), p});
                }
            }
        }
        return pool.get(random.nextInt(pool.size()));
    }

    // -----------------------------------------------------------------------
    // Chat handler — async thread
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        final Player  player      = event.getPlayer();
        final boolean hasOverride = player.hasPermission(PERM_OVERRIDE);
        final String  raw         = event.getMessage().trim();

        // Override: non-number chat passes through
        if (hasOverride && !isInteger(raw)) {
            approveMessage(player);
            event.setCancelled(false);
            return;
        }

        if (!live) return;

        if (!isInteger(raw)) {
            final String hint = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cType a number between \u00a7e0\u00a7c and \u00a7e100\u00a7c.";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(hint));
            return;
        }

        final int guess;
        try { guess = Integer.parseInt(raw); }
        catch (final NumberFormatException e) { return; }

        if (guess < 0 || guess > 100) {
            final String msg = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cOut of range — type \u00a7e0\u00a7c to \u00a7e100\u00a7c.";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(msg));
            return;
        }

        final boolean isActive;
        synchronized (this) { isActive = activePlayers.contains(player.getUniqueId()); }
        if (!isActive) return; // silently cancel for eliminated/late players

        final boolean alreadyGuessed;
        synchronized (this) {
            alreadyGuessed = guesses.containsKey(player.getUniqueId());
            if (!alreadyGuessed) guesses.put(player.getUniqueId(), guess);
        }
        if (alreadyGuessed) {
            final String msg = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cYou already submitted a guess this round!";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(msg));
            return;
        }

        final String ack = WavelengthConfig.format(
                getPlugin().getPluginConfig().getWavelengthConfig().getMsgGuessAck(),
                -1, null, guess, -1, null, null, null);
        Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(PluginConfig.broadcast(ack)));
    }

    // -----------------------------------------------------------------------
    // Player disconnect — remove from active set
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        synchronized (this) {
            activePlayers.remove(uuid);
            guesses.remove(uuid);
        }

        // If no active players remain and the round is live, end it early
        if (live) {
            final boolean empty;
            synchronized (this) { empty = activePlayers.isEmpty(); }
            if (empty) {
                Bukkit.getScheduler().runTask(getPlugin(), () -> {
                    if (live) { // re-check on main thread
                        getPlugin().getLogger().info(
                                "[WavelengthEvent] All players left — ending event.");
                        getPlugin().getEventManager().stopCurrentEvent();
                    }
                });
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers — main thread
    // -----------------------------------------------------------------------

    private void announceWinners(final WavelengthConfig wcfg, final List<UUID> winners) {
        final String prize   = wcfg.getRewardDisplayName();
        final String average = finalAverage != null ? finalAverage : "";
        broadcast(wcfg.getMsgWinnerBannerTop());
        if (winners.size() == 1) {
            broadcast(wcfg.getMsgWinnerLine().replace("%player%", resolvePlayerName(winners.get(0))));
        } else {
            broadcast(wcfg.getMsgWinnerMultiLine().replace("%players%", buildNameList(winners)));
        }
        if (!average.isEmpty()) {
            broadcast(wcfg.getMsgWinnerAvgLine().replace("%average%", average));
        }
        broadcast(wcfg.getMsgWinnerPrizeLine().replace("%prize%", prize));
        broadcast(wcfg.getMsgWinnerBannerBottom());
    }

    private void runRewardCommands(final WavelengthConfig wcfg, final List<UUID> winners) {
        final String cmd   = wcfg.getRewardCommand();
        final String prize = wcfg.getRewardDisplayName();
        for (final UUID uuid : winners) {
            final String name = resolvePlayerName(uuid);
            if (cmd != null && !cmd.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", name));
                getPlugin().getLogger().info("[WavelengthEvent] Reward dispatched for " + name);
            }
            final Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(PluginConfig.broadcast(wcfg.getMsgRewardPrivate().replace("%prize%", prize)));
            }
        }
    }

    private void approveMessage(final Player player) {
        player.setMetadata(ChatListener.APPROVED_KEY,
                new FixedMetadataValue(getPlugin(), true));
    }

    private void broadcast(final String message) {
        Bukkit.broadcastMessage(PluginConfig.broadcast(message));
    }

    private void schedule(final long delayTicks, final Runnable task) {
        tasks.add(Bukkit.getScheduler().runTaskLater(getPlugin(), task, delayTicks));
    }

    private static String buildNameList(final List<UUID> uuids) {
        return uuids.stream().map(WavelengthEvent::resolvePlayerName)
                .collect(Collectors.joining(", "));
    }

    // -----------------------------------------------------------------------
    // Helpers — thread-agnostic
    // -----------------------------------------------------------------------

    @NotNull
    private static String resolvePlayerName(@Nullable final UUID uuid) {
        if (uuid == null) return "Unknown";
        final Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        // Player went offline — try OfflinePlayer for cached name
        final org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        final String name = op.getName();
        return name != null ? name : "Unknown Player";
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

    @Override
    public void markStoppedByAdmin() {
        stoppedByAdmin = true;
    }

    public int    getCurrentRound()  { return currentRound; }
    public String getCurrentPrompt() { return currentPrompt; }

    public Map<UUID, Integer> getGuessesSnapshot() {
        synchronized (this) { return Collections.unmodifiableMap(new HashMap<>(guesses)); }
    }
    public Set<UUID> getActivePlayersSnapshot() {
        synchronized (this) { return Collections.unmodifiableSet(new HashSet<>(activePlayers)); }
    }
}
