package com.summit.summitchatevents;

import com.summit.summitchatevents.commands.SummitEventCommand;
import com.summit.summitchatevents.config.PluginConfig;
import com.summit.summitchatevents.listeners.ChatListener;
import com.summit.summitchatevents.managers.EventManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main entry point for the SummitChatEvents plugin.
 */
public final class SummitChatEventsPlugin extends JavaPlugin {

    // -----------------------------------------------------------------------
    // Singleton
    // -----------------------------------------------------------------------
    private static SummitChatEventsPlugin instance;

    public static SummitChatEventsPlugin getInstance() {
        return instance;
    }

    // -----------------------------------------------------------------------
    // Subsystems
    // -----------------------------------------------------------------------
    private PluginConfig pluginConfig;
    private EventManager eventManager;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("╔══════════════════════════════════╗");
        getLogger().info("║   SummitChatEvents  v" + getDescription().getVersion() + "        ║");
        getLogger().info("║   Enabling plugin...              ║");
        getLogger().info("╚══════════════════════════════════╝");

        loadPluginConfig();
        initManagers();
        registerCommands();
        registerListeners();

        getLogger().info("SummitChatEvents enabled successfully.");
    }

    @Override
    public void onDisable() {
        if (eventManager != null) {
            eventManager.shutdown();
        }
        getLogger().info("SummitChatEvents has been disabled. Goodbye!");
        instance = null;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void loadPluginConfig() {
        saveDefaultConfig();
        reloadConfig();
        pluginConfig = new PluginConfig(this);
        getLogger().info("Configuration loaded.");
    }

    private void initManagers() {
        eventManager = new EventManager(this);
        eventManager.init();
        getLogger().info("Managers initialised.");
    }

    private void registerCommands() {
        final SummitEventCommand cmd = new SummitEventCommand(this);
        //noinspection DataFlowIssue
        getCommand("summitevent").setExecutor(cmd);
        getCommand("summitevent").setTabCompleter(cmd);
        getLogger().info("Commands registered.");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getLogger().info("Listeners registered.");
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** @return the parsed plugin configuration (never null after onEnable) */
    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    /** @return the active EventManager (never null after onEnable) */
    public EventManager getEventManager() {
        return eventManager;
    }
}
