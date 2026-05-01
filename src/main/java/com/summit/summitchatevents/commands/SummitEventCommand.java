package com.summit.summitchatevents.commands;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.managers.EventManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the {@code /summitevent} command and all of its sub-commands.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code start <event-name>} — starts a registered event (requires {@code summitevents.start})</li>
 * </ul>
 *
 * <p>Registered in {@link SummitChatEventsPlugin} via:
 * <pre>{@code
 *   getCommand("summitevent").setExecutor(new SummitEventCommand(this));
 * }</pre>
 */
public final class SummitEventCommand implements CommandExecutor, TabCompleter {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String SUB_START  = "start";
    private static final String PERM_START = "summitevents.start";
    private static final String SUCCESS    = "\u00a7a[SummitEvents] \u00a7r";
    private static final String ERROR      = "\u00a7c[SummitEvents] \u00a7r";
    private static final String INFO       = "\u00a7e[SummitEvents] \u00a7r";

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
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case SUB_START -> handleStart(sender, args, label);
            default        -> {
                sender.sendMessage(ERROR + "Unknown sub-command.");
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
            if (sender.hasPermission(PERM_START)) {
                return List.of(SUB_START);
            }
            return List.of();
        }

        // Tab-complete event names for "/summitevent start <tab>"
        if (args.length == 2 && SUB_START.equalsIgnoreCase(args[0])
                && sender.hasPermission(PERM_START)) {
            final String partial = args[1].toLowerCase();
            final List<String> matches = new ArrayList<>();
            for (final String key : plugin.getEventManager().getRegisteredEventKeys()) {
                if (key.startsWith(partial)) {
                    matches.add(key);
                }
            }
            return matches;
        }

        return List.of();
    }

    // -----------------------------------------------------------------------
    // Sub-command handlers
    // -----------------------------------------------------------------------

    /**
     * Handles {@code /summitevent start <event-key>}.
     */
    private void handleStart(
            final CommandSender sender,
            final String[] args,
            final String label
    ) {
        if (!sender.hasPermission(PERM_START)) {
            sender.sendMessage(ERROR + "You do not have permission to start events.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(INFO + "Usage: /" + label + " start <event-name>");
            sender.sendMessage(INFO + "Available events: "
                    + plugin.getEventManager().getRegisteredEventKeys());
            return;
        }

        final String key = args[1].trim();
        if (key.isEmpty()) {
            sender.sendMessage(ERROR + "Event name cannot be empty.");
            return;
        }

        final EventManager.StartResult result = plugin.getEventManager().startEvent(key);

        switch (result) {
            case SUCCESS ->
                sender.sendMessage(SUCCESS + "Event \u00a7e" + key + "\u00a7a has been started!");

            case ALREADY_RUNNING ->
                sender.sendMessage(ERROR + "An event is already running: \u00a7e"
                        + plugin.getEventManager().getActiveEventName()
                        + "\u00a7c. Stop it before starting a new one.");

            case NOT_FOUND ->
                sender.sendMessage(ERROR + "Unknown event: \u00a7e" + key
                        + "\u00a7c. Available: " + plugin.getEventManager().getRegisteredEventKeys());
        }

        if (result == EventManager.StartResult.SUCCESS) {
            plugin.getLogger().info(sender.getName() + " started event: " + key);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void sendUsage(final CommandSender sender, final String label) {
        sender.sendMessage(INFO + "\u00a7lSummitEvents Commands:");
        sender.sendMessage(INFO + "  /" + label + " start <event-name> \u00a77\u2014 Start a registered event");
    }
}
