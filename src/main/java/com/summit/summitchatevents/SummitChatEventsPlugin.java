package com.summit.summitchatevents;

import com.summit.summitchatevents.commands.SummitEventCommand;
import com.summit.summitchatevents.managers.EventManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main entry point for the SummitChatEvents plugin.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #onEnable()} — called by the server when the plugin loads.</li>
 *   <li>{@link #onDisable()} — called by the server when the plugin unloads.</li>
 * </ol>
 *
 * <p>Future subsystems (event listeners, commands, config) should be
 * initialised inside {@code onEnable()} and cleaned up inside {@code onDisable()}.
 */
public final class SummitChatEventsPlugin extends JavaPlugin {

    // -----------------------------------------------------------------------
    // Singleton accessor (optional convenience; never leak to async threads)
    // -----------------------------------------------------------------------
    private static SummitChatEventsPlugin instance;

    public static SummitChatEventsPlugin getInstance() {
        return instance;
    }

    // -----------------------------------------------------------------------
    // Managers
    // -----------------------------------------------------------------------
    private EventManager eventManager;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onEnable() {
        instance = this;

        // ── Banner ──────────────────────────────────────────────────────────
        getLogger().info("╔══════════════════════════════════╗");
        getLogger().info("║   SummitChatEvents  v" + getDescription().getVersion() + "        ║");
        getLogger().info("║   Enabling plugin...              ║");
        getLogger().info("╚══════════════════════════════════╝");

        // ── Configuration ───────────────────────────────────────────────────
        saveDefaultConfig();          // writes config.yml from jar if absent
        reloadConfig();

        // ── Subsystems ──────────────────────────────────────────────────────
        initManagers();
        registerCommands();
        registerListeners();

        getLogger().info("SummitChatEvents enabled successfully.");
    }

    @Override
    public void onDisable() {
        // ── Tear-down ───────────────────────────────────────────────────────
        if (eventManager != null) {
            eventManager.shutdown();
        }

        getLogger().info("SummitChatEvents has been disabled. Goodbye!");
        instance = null;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Initialise all manager singletons in dependency order.
     * Add new managers here as the plugin grows.
     */
    private void initManagers() {
        eventManager = new EventManager(this);
        eventManager.init();
        getLogger().info("Managers initialised.");
    }

    /**
     * Register all commands defined in plugin.yml.
     * Add new commands here as the plugin grows.
     */
    private void registerCommands() {
        final SummitEventCommand summitEventCommand = new SummitEventCommand(this);
        //noinspection DataFlowIssue  — getCommand() is non-null when declared in plugin.yml
        getCommand("summitevent").setExecutor(summitEventCommand);
        getCommand("summitevent").setTabCompleter(summitEventCommand);
        getLogger().info("Commands registered.");
    }

    /**
     * Register all Bukkit event listeners.
     * Add new listeners here as the plugin grows.
     */
    private void registerListeners() {
        // Example (uncomment when the listener exists):
        // getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getLogger().info("Listeners registered. (none active yet)");
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** @return the active {@link EventManager}, or {@code null} if the plugin is disabled. */
    public EventManager getEventManager() {
        return eventManager;
    }
}
