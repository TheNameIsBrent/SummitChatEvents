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
 * <h3>Flow (per round)</h3>
 * <ol>
 *   <li>A {@link WavelengthConfig.Scale} and prompt are chosen randomly from config.</li>
 *   <li>Players see the scale (min ↔ max) and the prompt, then type a number 1–100.</li>
 *   <li>A secret target is generated; the player closest wins the round.</li>
 *   <li>After all configured rounds, the player with the most points wins.</li>
 * </ol>
 *
 * <h3>Round durations</h3>
 * Each round has its own configured duration (e.g. round1=40s, round2=25s).
 * If a player sends a guess, a private ack is shown; the round closes when
 * the timer expires (not when all players have guessed).
 *
 * <h3>Thread safety</h3>
 * {@link AsyncPlayerChatEvent} fires async. All shared mutable state is guarded
 * by {@code synchronized(this)}. Main-thread Bukkit calls use {@code runTask()}.
 */
public final class WavelengthEvent extends ChatEvent implements Listener {

    // -----------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------

    private static final String PERM_PLAY     = "summitevents.play";
    private static final String PERM_OVERRIDE = "summitevents.overridechat";

    // -----------------------------------------------------------------------
    // Intro timing (ticks)
    // -----------------------------------------------------------------------

    private static final long T_RULES      = 60L;
    private static final long T_HERE_WE_GO = 120L;
    private static final long T_START      = 160L;

    // -----------------------------------------------------------------------
    // Game state — all guarded by synchronized(this) except volatile fields
    // -----------------------------------------------------------------------

    private final Set<UUID>           activePlayers = new HashSet<>();
    private final Map<UUID, Integer>  guesses       = new HashMap<>();
    private final Map<UUID, Integer>  points        = new HashMap<>();

    private int     currentRound  = 0;
    private int     target        = 0;
    private String  currentScaleMin = "";
    private String  currentScaleMax = "";
    private String  currentPrompt   = "";

    private volatile boolean live = false;

    private final List<BukkitTask> tasks = new ArrayList<>();
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
            points.clear();
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

        final WavelengthConfig wcfg = getPlugin().getPluginConfig().getWavelengthConfig();
        scheduleIntro(wcfg);
    }

    @Override
    protected void onStop() {
        live = false;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        HandlerList.unregisterAll(this);

        final WavelengthConfig wcfg      = getPlugin().getPluginConfig().getWavelengthConfig();
        @Nullable final UUID   winnerUuid = findOverallWinner();
        @Nullable final Player winner     = winnerUuid != null ? Bukkit.getPlayer(winnerUuid) : null;
        @Nullable final String winnerName = resolvePlayerName(winnerUuid, winner);

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
        target = 0;
    }

    // -----------------------------------------------------------------------
    // Intro — main thread
    // -----------------------------------------------------------------------

    private void scheduleIntro(final WavelengthConfig wcfg) {
        schedule(0L,           () -> broadcast(wcfg.getMsgAnnounce()));
        schedule(T_RULES,      () -> broadcast(wcfg.getMsgRules()));
        schedule(T_HERE_WE_GO, () -> broadcast(wcfg.getMsgHereWeGo()));
        schedule(T_START,      this::startRound);
    }

    // -----------------------------------------------------------------------
    // Round logic — main thread
    // -----------------------------------------------------------------------

    /**
     * Starts the next round.
     * Picks a random scale + prompt, generates a secret target, and broadcasts
     * the round-start, scale, and prompt messages before opening guessing.
     */
    private void startRound() {
        final WavelengthConfig       wcfg  = getPlugin().getPluginConfig().getWavelengthConfig();
        final WavelengthConfig.Scale scale = wcfg.randomScale(random);

        currentRound++;
        target          = 1 + random.nextInt(100);  // 1–100 inclusive
        currentScaleMin = scale.getMin();
        currentScaleMax = scale.getMax();
        currentPrompt   = scale.randomPrompt(random);

        synchronized (this) {
            guesses.clear();
        }
        live = true;

        // Broadcast round-start, scale, and prompt as three lines
        broadcast(WavelengthConfig.format(wcfg.getMsgRoundStart(),
                currentRound, null, -1, -1, null, null, null));
        broadcast(WavelengthConfig.format(wcfg.getMsgScale(),
                -1, null, -1, -1, currentScaleMin, currentScaleMax, null));
        broadcast(WavelengthConfig.format(wcfg.getMsgPrompt(),
                -1, null, -1, -1, null, null, currentPrompt));

        // Per-round timer from config
        final long durationTicks = wcfg.getRoundDurationTicks(currentRound);
        schedule(durationTicks, this::closeRound);

        getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                + " started. Prompt='" + currentPrompt + "' Target=" + target
                + " Duration=" + (durationTicks / 20) + "s");
    }

    /**
     * Closes the current round when the timer fires.
     * Determines the closest guesser, awards a point, then starts the next
     * round or ends the event.
     */
    private void closeRound() {
        live = false;

        final WavelengthConfig   wcfg     = getPlugin().getPluginConfig().getWavelengthConfig();
        final Map<UUID, Integer> snapshot;
        synchronized (this) {
            snapshot = new HashMap<>(guesses);
        }

        // Find closest guess — ties broken by whichever entry comes first in iteration
        @Nullable UUID roundWinnerUuid = null;
        int closestDelta = Integer.MAX_VALUE;
        for (final Map.Entry<UUID, Integer> entry : snapshot.entrySet()) {
            final int delta = Math.abs(entry.getValue() - target);
            if (delta < closestDelta) {
                closestDelta    = delta;
                roundWinnerUuid = entry.getKey();
            }
        }

        if (roundWinnerUuid != null) {
            synchronized (this) {
                points.merge(roundWinnerUuid, 1, Integer::sum);
            }
            final Player roundWinner = Bukkit.getPlayer(roundWinnerUuid);
            final String name        = resolvePlayerName(roundWinnerUuid, roundWinner);
            final int    winGuess    = snapshot.get(roundWinnerUuid);

            broadcast(WavelengthConfig.format(wcfg.getMsgRoundWinner(),
                    currentRound, name, winGuess, target, null, null, null));

            getPlugin().getLogger().info("[WavelengthEvent] Round " + currentRound
                    + " won by " + name + " with guess " + winGuess
                    + " (target=" + target + ")");
        } else {
            broadcast(getPlugin().getPluginConfig().getPrefix() + "&7No guesses this round.");
        }

        if (currentRound >= wcfg.getRoundCount()) {
            getPlugin().getLogger().info("[WavelengthEvent] All rounds complete.");
            // Small pause before announcing results
            Bukkit.getScheduler().runTaskLater(getPlugin(),
                    () -> getPlugin().getEventManager().stopCurrentEvent(), 40L);
        } else {
            schedule(60L, this::startRound); // 3-second gap between rounds
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

        // Override players: non-number chat passes through freely
        if (hasOverride && !isInteger(raw)) {
            approveMessage(player);
            event.setCancelled(false);
            return;
        }

        // Everyone else is already cancelled by ChatListener.LOWEST
        if (!canPlay || !live) return;
        if (!isInteger(raw))   return;

        final int guess;
        try {
            guess = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            return;
        }
        if (guess < 1 || guess > 100) return;

        // Only active players participate
        final boolean isActive;
        synchronized (this) {
            isActive = activePlayers.contains(player.getUniqueId());
        }
        if (!isActive) return;

        // One guess per round
        final boolean alreadyGuessed;
        synchronized (this) {
            alreadyGuessed = guesses.containsKey(player.getUniqueId());
            if (!alreadyGuessed) guesses.put(player.getUniqueId(), guess);
        }
        if (alreadyGuessed) return;

        // Private ack to the guesser
        final String ackTemplate = getPlugin().getPluginConfig().getWavelengthConfig().getMsgGuessAck();
        final String ack = WavelengthConfig.format(ackTemplate, -1, null, guess, -1, null, null, null);
        Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(ack));
    }

    // -----------------------------------------------------------------------
    // Helpers — main thread
    // -----------------------------------------------------------------------

    private void runRewardCommand(final WavelengthConfig wcfg, final String winnerName) {
        final String cmd = wcfg.getRewardCommand();
        if (cmd == null || cmd.isBlank()) return;
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    cmd.replace("%player%", winnerName));
            broadcast(WavelengthConfig.format(wcfg.getMsgReward(),
                    -1, winnerName, -1, -1, null, null, null));
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

    private static String resolvePlayerName(
            @Nullable final UUID uuid, @Nullable final Player player) {
        if (player != null) return player.getName();
        if (uuid   != null) return uuid.toString();
        return null;
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
    public int    getTarget()        { return target; }
    public String getCurrentPrompt() { return currentPrompt; }

    public Map<UUID, Integer> getGuessesSnapshot() {
        synchronized (this) { return Collections.unmodifiableMap(new HashMap<>(guesses)); }
    }
    public Set<UUID> getActivePlayersSnapshot() {
        synchronized (this) { return Collections.unmodifiableSet(new HashSet<>(activePlayers)); }
    }
}
