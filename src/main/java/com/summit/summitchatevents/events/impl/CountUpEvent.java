package com.summit.summitchatevents.events.impl;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.config.PluginConfig;
import com.summit.summitchatevents.events.ChatEvent;
import com.summit.summitchatevents.listeners.ChatListener;
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
import org.bukkit.metadata.FixedMetadataValue;
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
 * <h3>Permissions</h3>
 * <ul>
 *   <li>{@value #PERM_PLAY} — default {@code true}; required to submit numbers.</li>
 *   <li>{@value #PERM_OVERRIDE} — default {@code op}; chat bypass + can play.</li>
 * </ul>
 *
 * <h3>Chat pipeline</h3>
 * {@link ChatListener#onChatLowest} cancels everything first.
 * This handler runs at LOWEST (same priority, registered after ChatListener
 * so it fires second) and either leaves the message cancelled or sets the
 * {@link ChatListener#APPROVED_KEY} metadata so the HIGHEST guard lets it through.
 * Approved messages are fully cancelled too — we send via Adventure per-player
 * to display the number with the player's name and true hex colour.
 *
 * <h3>Thread safety</h3>
 * {@link AsyncPlayerChatEvent} fires async. All shared mutable state uses
 * atomics or volatile. Main-thread Bukkit calls go through {@code runTask()}.
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
    // Colour — floor keeps numbers readable on dark backgrounds
    // -----------------------------------------------------------------------

    private static final int COLOR_FLOOR = 120;
    private static final int COLOR_RANGE = 256 - COLOR_FLOOR;

    // -----------------------------------------------------------------------
    // Intro timing (ticks; 20 = 1 s)
    // -----------------------------------------------------------------------

    private static final long T_RULES      = 60L;   // 3 s after announce
    private static final long T_HERE_WE_GO = 120L;  // 3 s after rules
    private static final long T_GO_LIVE    = 160L;  // 2 s after here-we-go

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    /** Next number a player must type. 0 = not live yet. */
    private final AtomicInteger currentNumber = new AtomicInteger(0);

    private final AtomicReference<UUID>   lastPlayerUuid = new AtomicReference<>(null);
    private final AtomicReference<String> lastPlayerName = new AtomicReference<>(null);

    /** All pending scheduler tasks tracked for clean cancellation. */
    private final List<BukkitTask> tasks = new ArrayList<>();

    /** True once the intro finishes and the counting game is live. */
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
        live = false;
        currentNumber.set(0);
        lastPlayerUuid.set(null);
        lastPlayerName.set(null);
        tasks.clear();

        // Register listener before intro so chat is suppressed immediately
        Bukkit.getPluginManager().registerEvents(this, getPlugin());

        scheduleIntro();
    }

    @Override
    protected void onStop() {
        live = false;

        tasks.forEach(BukkitTask::cancel);
        tasks.clear();

        HandlerList.unregisterAll(this);

        final PluginConfig cfg    = getPlugin().getPluginConfig();
        final int          count  = Math.max(currentNumber.get() - 1, 0);
        final String       winner = lastPlayerName.get();

        if (winner != null) {
            broadcast(PluginConfig.format(cfg.getCountMsgWinner(), winner, count));
            runRewardCommand(cfg, winner);
        } else {
            broadcast(cfg.getCountMsgNoWinner());
        }

        getPlugin().getLogger().info(String.format(
                "[CountUpEvent] Stopped. Highest: %d. Winner: %s",
                count, winner != null ? winner : "none"));

        currentNumber.set(0);
        lastPlayerUuid.set(null);
        lastPlayerName.set(null);
    }

    // -----------------------------------------------------------------------
    // Intro sequence
    // -----------------------------------------------------------------------

    private void scheduleIntro() {
        final PluginConfig cfg = getPlugin().getPluginConfig();

        // Big centred banner
        schedule(0L, () -> {
            broadcast(cfg.getCountMsgBannerTop());
            broadcast(cfg.getCountMsgAnnounce());
            broadcast(cfg.getCountMsgBannerBottom());
        });
        schedule(T_RULES,      () -> broadcast(cfg.getCountMsgRules()));
        schedule(T_HERE_WE_GO, () -> broadcast(cfg.getCountMsgHereWeGo()));
        schedule(T_GO_LIVE,    this::goLive);
    }

    private void goLive() {
        currentNumber.set(2);  // server broadcasts 1; players type from 2
        live = true;

        broadcastStyledNumber(1);  // null sender = server

        final PluginConfig cfg    = getPlugin().getPluginConfig();
        final int          minSec = cfg.getCountMinDuration();
        final int          maxSec = cfg.getCountMaxDuration();
        final int          durSec = minSec + random.nextInt(Math.max(maxSec - minSec + 1, 1));

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

        // Override players: non-number messages pass through freely.
        // Set the approval flag so ChatListener.HIGHEST lets them through.
        if (hasOverride && !isPositiveInteger(raw)) {
            approveMessage(player);
            event.setCancelled(false);
            return;
        }

        // Everyone without override is already cancelled by ChatListener.LOWEST.
        // Players with no play permission can't participate.
        if (!canPlay) {
            return;
        }

        // Game not yet live — keep cancelled
        if (!live) {
            return;
        }

        // Non-numeric message from a play-permission player — silently block
        if (!isPositiveInteger(raw)) {
            return;
        }

        final int sent;
        try {
            sent = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            return;
        }

        // Wrong number
        final int expected = currentNumber.get();
        if (sent != expected) {
            return;
        }

        // Consecutive send guard
        if (player.getUniqueId().equals(lastPlayerUuid.get())) {
            return;
        }

        // Race-safe claim
        if (!currentNumber.compareAndSet(expected, expected + 1)) {
            return;
        }

        // --- Accepted ---
        lastPlayerUuid.set(player.getUniqueId());
        lastPlayerName.set(player.getName());

        // Keep the event cancelled — we send the styled message manually below
        // so we control the exact format (player name + coloured number).
        // No need to set the approval flag; we handle the broadcast ourselves.

        final int acceptedNum = sent;
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            broadcastStyledNumber(acceptedNum);
            playSoundForAll();
        });
    }

    // -----------------------------------------------------------------------
    // Main-thread helpers
    // -----------------------------------------------------------------------

    /**
     * Broadcasts a bold, random-hex-coloured number to all online players
     * via the Adventure API (no legacy § escapes, no unicode warnings).
     */
    private void broadcastStyledNumber(final int number) {
        final int r = COLOR_FLOOR + random.nextInt(COLOR_RANGE);
        final int g = COLOR_FLOOR + random.nextInt(COLOR_RANGE);
        final int b = COLOR_FLOOR + random.nextInt(COLOR_RANGE);
        final TextColor colour = TextColor.color(r, g, b);

        final Component line = Component.text(String.valueOf(number))
                .color(colour)
                .decorate(TextDecoration.BOLD);

        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(line);
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
            broadcast(cfg.getCountMsgReward().replace("%player%", winnerName));
            getPlugin().getLogger().info(
                    "[CountUpEvent] Reward dispatched for " + winnerName + ": " + resolved);
        });
    }

    /**
     * Sets the approval metadata flag on {@code player} so
     * {@link ChatListener#onChatHighest} knows this message was explicitly
     * allowed (used for override-permission players' non-number chat).
     */
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

    private static boolean isPositiveInteger(final String s) {
        if (s == null || s.isEmpty() || s.length() > 10) return false;
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
