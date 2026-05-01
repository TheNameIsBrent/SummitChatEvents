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
 * Handles {@code /summitevent} and all sub-commands.
 *
 * <h3>Sub-commands</h3>
 * <ul>
 *   <li>{@code start <event>} — starts a registered event (perm: {@code summitevents.start})</li>
 *   <li>{@code reload}        — reloads config (perm: {@code summitevents.reload})</li>
 * </ul>
 */
public final class SummitEventCommand implements CommandExecutor, TabCompleter {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String SUB_START  = "start";
    private static final String SUB_RELOAD = "reload";

    private static final String PERM_START  = "summitevents.start";
    private static final String PERM_RELOAD = "summitevents.reload";

    // Colour shortcuts (§-coded legacy strings — safe in command feedback)
    private static final String C_OK   = "\u00a7a";  // green
    private static final String C_ERR  = "\u00a7c";  // red
    private static final String C_INFO = "\u00a7e";  // yellow
    private static final String C_RST  = "\u00a7r";  // reset

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
            case SUB_START  -> handleStart(sender, args, label);
            case SUB_RELOAD -> handleReload(sender);
            default         -> {
                msg(sender, C_ERR + "Unknown sub-command.");
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
            final List<String> subs = new ArrayList<>();
            if (sender.hasPermission(PERM_START))  subs.add(SUB_START);
            if (sender.hasPermission(PERM_RELOAD)) subs.add(SUB_RELOAD);
            return filterPrefix(subs, args[0]);
        }

        if (args.length == 2 && SUB_START.equalsIgnoreCase(args[0])
                && sender.hasPermission(PERM_START)) {
            return filterPrefix(
                    new ArrayList<>(plugin.getEventManager().getRegisteredEventKeys()),
                    args[1]);
        }

        return List.of();
    }

    // -----------------------------------------------------------------------
    // Sub-command handlers
    // -----------------------------------------------------------------------

    private void handleStart(
            final CommandSender sender,
            final String[] args,
            final String label
    ) {
        if (!sender.hasPermission(PERM_START)) {
            msg(sender, C_ERR + "You do not have permission to start events.");
            return;
        }

        if (args.length < 2 || args[1].isBlank()) {
            msg(sender, C_INFO + "Usage: /" + label + " start <event-name>");
            msg(sender, C_INFO + "Available: " + plugin.getEventManager().getRegisteredEventKeys());
            return;
        }

        final String key = args[1].trim();
        final EventManager.StartResult result = plugin.getEventManager().startEvent(key);

        switch (result) {
            case SUCCESS -> {
                msg(sender, C_OK + "Started event " + C_INFO + key + C_OK + ".");
                plugin.getLogger().info(sender.getName() + " started event: " + key);
            }
            case ALREADY_RUNNING -> msg(sender, C_ERR + "An event is already running: "
                    + C_INFO + plugin.getEventManager().getActiveEventName()
                    + C_ERR + ". Stop it first.");
            case NOT_FOUND -> msg(sender, C_ERR + "Unknown event: " + C_INFO + key
                    + C_ERR + ". Available: "
                    + plugin.getEventManager().getRegisteredEventKeys());
        }
    }

    private void handleReload(final CommandSender sender) {
        if (!sender.hasPermission(PERM_RELOAD)) {
            msg(sender, C_ERR + "You do not have permission to reload the config.");
            return;
        }

        plugin.reloadConfig();
        plugin.refreshPluginConfig();
        msg(sender, C_OK + "Configuration reloaded.");
        plugin.getLogger().info(sender.getName() + " reloaded the config.");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void sendUsage(final CommandSender sender, final String label) {
        msg(sender, C_INFO + "\u00a7lSummitEvents commands:");
        msg(sender, C_INFO + "  /" + label + " start <event>" + C_RST + " \u00a77\u2014 Start an event");
        msg(sender, C_INFO + "  /" + label + " reload"        + C_RST + " \u00a77\u2014 Reload config");
    }

    private static void msg(final CommandSender sender, final String text) {
        sender.sendMessage(text);
    }

    /**
     * Returns entries from {@code list} that start with {@code partial} (case-insensitive).
     */
    private static List<String> filterPrefix(final List<String> list, final String partial) {
        if (partial.isEmpty()) return list;
        final String lower = partial.toLowerCase();
        final List<String> out = new ArrayList<>();
        for (final String s : list) {
            if (s.toLowerCase().startsWith(lower)) out.add(s);
        }
        return out;
    }
}
