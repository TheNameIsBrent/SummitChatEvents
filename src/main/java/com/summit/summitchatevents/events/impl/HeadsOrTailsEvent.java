package com.summit.summitchatevents.events.impl;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.config.PluginConfig;
import com.summit.summitchatevents.config.HeadsOrTailsConfig;
import com.summit.summitchatevents.events.ChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Heads or Tails — an elimination event where players choose heads or tails
 * each round. Players who pick the wrong side are eliminated. Last one
 * standing wins.
 *
 * <h3>Round flow (scaffold — chat logic to be added)</h3>
 * <ol>
 *   <li>Broadcast the banner and rules.</li>
 *   <li>Each round: tell players to choose heads or tails.</li>
 *   <li>Flip a coin; eliminate players on the losing side.</li>
 *   <li>Repeat until 1 player remains or rounds are exhausted.</li>
 * </ol>
 *
 * <h3>Participation</h3>
 * Every online player is added at start — no permission required.
 *
 * <h3>Thread safety</h3>
 * {@link AsyncPlayerChatEvent} fires async. {@link #activePlayers} and
 * {@link #choices} are guarded by {@code synchronized(this)}.
 * {@link #live} is {@code volatile}.
 */
public final class HeadsOrTailsEvent extends ChatEvent implements Listener {

    // -----------------------------------------------------------------------
    // Permission
    // -----------------------------------------------------------------------

    private static final String PERM_OVERRIDE = "summitevents.overridechat";

    // -----------------------------------------------------------------------
    // Timing (ticks; 20t = 1 s)
    // -----------------------------------------------------------------------

    private static final long T_RULES      = 60L;
    private static final long T_HERE_WE_GO = 120L;
    private static final long T_START      = 160L;

    // -----------------------------------------------------------------------
    // State — collections guarded by synchronized(this)
    // -----------------------------------------------------------------------

    /** Players still in the game. All online players added on start. */
    private final Set<UUID>          activePlayers = new HashSet<>();

    /**
     * Choices for the current round: UUID → "heads" or "tails".
     * Written from async chat thread under lock; read on main thread.
     */
    private final Map<UUID, String>  choices       = new HashMap<>();

    private int    currentRound = 0;
    private int    maxRounds    = 10;

    private volatile boolean      live         = false;
    private volatile boolean      stoppedByAdmin = false;
    private final    List<BukkitTask> tasks    = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public HeadsOrTailsEvent(final SummitChatEventsPlugin plugin) {
        super(plugin, "Heads or Tails");
    }

    // -----------------------------------------------------------------------
    // ChatEvent lifecycle — main thread
    // -----------------------------------------------------------------------

    @Override
    public int getMinPlayers() {
        return getPlugin().getPluginConfig().getHeadsOrTailsConfig().getMinPlayers();
    }

    @Override
    public void markStoppedByAdmin() {
        stoppedByAdmin = true;
    }

    @Override
    protected void onStart() {
        final HeadsOrTailsConfig cfg = getPlugin().getPluginConfig().getHeadsOrTailsConfig();
        maxRounds      = cfg.getMaxRounds();
        currentRound   = 0;
        live           = false;
        stoppedByAdmin = false;
        tasks.clear();

        synchronized (this) {
            activePlayers.clear();
            choices.clear();
            for (final Player p : Bukkit.getOnlinePlayers()) {
                activePlayers.add(p.getUniqueId());
            }
        }

        Bukkit.getPluginManager().registerEvents(this, getPlugin());
        scheduleIntro(cfg);
    }

    @Override
    protected void onStop() {
        live = false;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        HandlerList.unregisterAll(this);

        if (!stoppedByAdmin) {
            // TODO: announce winner / no-winner once round logic is implemented
            final HeadsOrTailsConfig cfg = getPlugin().getPluginConfig().getHeadsOrTailsConfig();
            final Set<UUID> remaining;
            synchronized (this) { remaining = new HashSet<>(activePlayers); }

            if (remaining.size() == 1) {
                final UUID    winnerUuid = remaining.iterator().next();
                final Player  winner     = Bukkit.getPlayer(winnerUuid);
                final String  name       = winner != null ? winner.getName() : winnerUuid.toString();
                announceWinner(cfg, name);
                runRewardCommand(cfg, name);
            } else {
                Bukkit.broadcastMessage(cfg.getMsgNoWinner());
            }
        }

        getPlugin().getLogger().info("[HeadsOrTailsEvent] Stopped after round " + currentRound + ".");

        synchronized (this) {
            activePlayers.clear();
            choices.clear();
        }
        currentRound   = 0;
        stoppedByAdmin = false;
    }

    // -----------------------------------------------------------------------
    // Intro — main thread
    // -----------------------------------------------------------------------

    private void scheduleIntro(final HeadsOrTailsConfig cfg) {
        schedule(0L, () -> {
            Bukkit.broadcastMessage(PluginConfig.broadcast(cfg.getMsgBannerTop()));
            Bukkit.broadcastMessage(PluginConfig.broadcast(cfg.getMsgAnnounce()));
            Bukkit.broadcastMessage(PluginConfig.broadcast(cfg.getMsgBannerBottom()));
        });
        schedule(T_RULES,      () -> Bukkit.broadcastMessage(cfg.getMsgRules()));
        schedule(T_HERE_WE_GO, () -> Bukkit.broadcastMessage(cfg.getMsgHereWeGo()));
        schedule(T_START,      this::startRound);
    }

    // -----------------------------------------------------------------------
    // Round lifecycle — main thread (scaffold)
    // -----------------------------------------------------------------------

    /**
     * Starts the next round.
     *
     * <p>Increments {@link #currentRound}, clears previous choices, tells
     * players to choose, and opens the round for submissions.
     *
     * <p>Chat logic (accepting choices, flipping the coin, eliminating losers)
     * will be implemented in a future milestone.
     */
    private void startRound() {
        currentRound++;
        synchronized (this) { choices.clear(); }
        live = true;

        final HeadsOrTailsConfig cfg = getPlugin().getPluginConfig().getHeadsOrTailsConfig();
        Bukkit.broadcastMessage(PluginConfig.broadcast(cfg.getMsgChoose()));

        getPlugin().getLogger().info("[HeadsOrTailsEvent] Round " + currentRound + " started.");

        // TODO: schedule round timer → call endRound() when time expires
    }

    /**
     * Ends the current round.
     *
     * <p>Scaffold — flipping logic and elimination will be added in the next
     * implementation milestone.
     */
    private void endRound() {
        live = false;
        getPlugin().getLogger().info("[HeadsOrTailsEvent] Round " + currentRound + " ended.");

        // TODO:
        //   1. Pick random result ("heads" or "tails")
        //   2. Eliminate players who chose the wrong side
        //   3. Broadcast result + DMs to eliminated/surviving players
        //   4. If 1 player remains → concludeEvent([winner])
        //   5. If 0 players remain → concludeEvent([])
        //   6. If maxRounds reached → concludeEvent(activePlayers)
        //   7. Otherwise → startRound()
    }

    // -----------------------------------------------------------------------
    // Chat handler — async thread (scaffold)
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        final Player  player      = event.getPlayer();
        final boolean hasOverride = player.hasPermission(PERM_OVERRIDE);
        final String  raw         = event.getMessage().trim().toLowerCase();

        // Override players: non-choice messages pass through
        if (hasOverride && !raw.equals("heads") && !raw.equals("tails")) {
            // TODO: approve message via ChatListener.APPROVED_KEY
            event.setCancelled(false);
            return;
        }

        if (!live) return;

        // Only active players can submit
        final boolean isActive;
        synchronized (this) { isActive = activePlayers.contains(player.getUniqueId()); }
        if (!isActive) return;

        // Must be "heads" or "tails"
        if (!raw.equals("heads") && !raw.equals("tails")) {
            final String hint = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cType \u00a7aheads \u00a7cor \u00a7ctails\u00a7c only!";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(hint));
            return;
        }

        // One choice per round — atomic check-and-store
        final boolean alreadyChose;
        synchronized (this) {
            alreadyChose = choices.containsKey(player.getUniqueId());
            if (!alreadyChose) choices.put(player.getUniqueId(), raw);
        }

        if (alreadyChose) {
            final String msg = getPlugin().getPluginConfig().getPrefix()
                    + "\u00a7cYou have already chosen this round!";
            Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(msg));
            return;
        }

        // Private ack
        final String ack = getPlugin().getPluginConfig().getPrefix()
                + "\u00a77Your choice \u00a7e" + raw + "\u00a77 has been locked in!";
        Bukkit.getScheduler().runTask(getPlugin(), () -> player.sendMessage(ack));
    }

    // -----------------------------------------------------------------------
    // Player disconnect — remove from active set
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        synchronized (this) {
            activePlayers.remove(uuid);
            choices.remove(uuid);
        }

        if (live) {
            final boolean empty;
            synchronized (this) { empty = activePlayers.isEmpty(); }
            if (empty) {
                Bukkit.getScheduler().runTask(getPlugin(), () -> {
                    if (live) getPlugin().getEventManager().stopCurrentEvent();
                });
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers — main thread
    // -----------------------------------------------------------------------

    private void announceWinner(final HeadsOrTailsConfig cfg, final String winnerName) {
        final String prize = cfg.getRewardDisplayName();
        Bukkit.broadcastMessage(PluginConfig.broadcast(cfg.getMsgWinnerBannerTop()));
        Bukkit.broadcastMessage(PluginConfig.broadcast(cfg.getMsgWinnerLine().replace("%player%", winnerName)));
        Bukkit.broadcastMessage(PluginConfig.broadcast(cfg.getMsgWinnerPrizeLine().replace("%prize%", prize)));
        Bukkit.broadcastMessage(PluginConfig.broadcast(cfg.getMsgWinnerBannerBottom()));
    }

    private void runRewardCommand(final HeadsOrTailsConfig cfg, final String winnerName) {
        final String cmd = cfg.getRewardCommand();
        if (cmd != null && !cmd.isBlank()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", winnerName));
            getPlugin().getLogger().info("[HeadsOrTailsEvent] Reward dispatched for " + winnerName);
        }
        final Player wp = Bukkit.getPlayerExact(winnerName);
        if (wp != null) {
            wp.sendMessage(cfg.getMsgRewardPrivate().replace("%prize%", cfg.getRewardDisplayName()));
        }
    }

    private void schedule(final long delayTicks, final Runnable task) {
        tasks.add(Bukkit.getScheduler().runTaskLater(getPlugin(), task, delayTicks));
    }

    // -----------------------------------------------------------------------
    // Public accessors
    // -----------------------------------------------------------------------

    public int getCurrentRound() { return currentRound; }

    public Map<UUID, String> getChoicesSnapshot() {
        synchronized (this) { return Collections.unmodifiableMap(new HashMap<>(choices)); }
    }

    public Set<UUID> getActivePlayersSnapshot() {
        synchronized (this) { return Collections.unmodifiableSet(new HashSet<>(activePlayers)); }
    }
}
