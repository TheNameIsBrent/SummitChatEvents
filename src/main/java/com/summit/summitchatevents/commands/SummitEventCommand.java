package com.summit.summitchatevents.commands;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.managers.EventManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Handles the {@code /summitevent} command and all of its sub-commands.
 *
 * <p>Registered sub-commands:
 * <ul>
 *   <li>{@code start <event-name>} — starts a named event (requires {@code summitevents.start})</li>
 * </ul>
 *
 * <p>Register in {@link SummitChatEventsPlugin#onEnable()} via:
 * <pre>{@code
 *   getCommand("summitevent").setExecutor(new SummitEventCommand(this));
 * }</pre>
 */
public final class SummitEventCommand implements CommandExecutor, TabCompleter {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String SUB_START      = "start";
    private static final String PERM_START     = "summitevents.start";
    private static final String PREFIX_SUCCESS = "§a[SummitEvents] §r";
    private static final String PREFIX_ERROR   = "§c[SummitEvents] §r";
    private static final String PREFIX_INFO    = "§e[SummitEvents] §r";

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final SummitChatEventsPlugin plugin;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public SummitEventCommand(final SummitChatEventsPlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // CommandExecutor
    // -----------------------------------------------------------------------

    @Override
    public boolean onCommand(
            final @NotNull CommandSender sender,
            final @NotNull Command command,
            final @NotNull String label,
            final @NotNull String[] args
    ) {
        // No sub-command provided — print usage
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        final String sub = args[0].toLowerCase();

        switch (sub) {
            case SUB_START -> handleStart(sender, args, label);
            default        -> {
                sender.sendMessage(PREFIX_ERROR + "Unknown sub-command. ");
                sendUsage(sender, label);
            }
        }

        return true;
    }

    // -----------------------------------------------------------------------
    // TabCompleter
    // -----------------------------------------------------------------------

    @Override
    public @Nullable List<String> onTabComplete(
            final @NotNull CommandSender sender,
            final @NotNull Command command,
            final @NotNull String label,
            final @NotNull String[] args
    ) {
        if (args.length == 1) {
            // Suggest sub-commands the sender has permission to use
            if (sender.hasPermission(PERM_START)) {
                return List.of(SUB_START);
            }
            return List.of();
        }

        if (args.length == 2 && SUB_START.equalsIgnoreCase(args[0])) {
            // Suggest a placeholder event name so players know what to type
            return List.of("<event-name>");
        }

        return List.of();
    }

    // -----------------------------------------------------------------------
    // Sub-command handlers
    // -----------------------------------------------------------------------

    /**
     * Handles {@code /summitevent start <event-name>}.
     */
    private void handleStart(
            final CommandSender sender,
            final String[] args,
            final String label
    ) {
        // Permission check
        if (!sender.hasPermission(PERM_START)) {
            sender.sendMessage(PREFIX_ERROR + "You do not have permission to start events.");
            return;
        }

        // Require an event name
        if (args.length < 2) {
            sender.sendMessage(PREFIX_INFO + "Usage: /" + label + " start <event-name>");
            return;
        }

        final String eventName = args[1].trim();

        if (eventName.isEmpty()) {
            sender.sendMessage(PREFIX_ERROR + "Event name cannot be empty.");
            return;
        }

        final EventManager eventManager = plugin.getEventManager();

        // Guard: another event is already running
        if (eventManager.isEventRunning()) {
            sender.sendMessage(
                PREFIX_ERROR + "An event is already running: §e"
                + eventManager.getActiveEventName()
                + "§c. Stop it before starting a new one."
            );
            return;
        }

        // Attempt to start — EventManager is the authority
        final boolean started = eventManager.startEvent(eventName);

        if (started) {
            sender.sendMessage(PREFIX_SUCCESS + "Event §e" + eventName + "§a has been started!");
            plugin.getLogger().info(sender.getName() + " started event: " + eventName);
        } else {
            // Shouldn't normally reach here (we checked above), but be defensive
            sender.sendMessage(PREFIX_ERROR + "Could not start the event. Is another one running?");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void sendUsage(final CommandSender sender, final String label) {
        sender.sendMessage(PREFIX_INFO + "§lSummitEvents Commands:");
        sender.sendMessage(PREFIX_INFO + "  /" + label + " start <event-name> §7— Start a named event");
    }
}
