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

    private static SummitChatEventsPlugin instance;

    public static SummitChatEventsPlugin getInstance() { return instance; }

    private PluginConfig pluginConfig;
    private EventManager eventManager;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("╔══════════════════════════════════╗");
        getLogger().info("║  SummitChatEvents  v" + getDescription().getVersion() + "         ║");
        getLogger().info("╚══════════════════════════════════╝");

        saveDefaultConfig();
        refreshPluginConfig();

        eventManager = new EventManager(this);
        eventManager.init();

        final SummitEventCommand cmd = new SummitEventCommand(this);
        //noinspection DataFlowIssue
        getCommand("summitevent").setExecutor(cmd);
        getCommand("summitevent").setTabCompleter(cmd);

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        getLogger().info("SummitChatEvents enabled.");
    }

    @Override
    public void onDisable() {
        if (eventManager != null) eventManager.shutdown();
        getLogger().info("SummitChatEvents disabled.");
        instance = null;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Re-reads the YAML config and rebuilds the typed {@link PluginConfig}.
     * Safe to call at any time from the main thread (e.g. on /summitevent reload).
     */
    public void refreshPluginConfig() {
        reloadConfig();
        pluginConfig = new PluginConfig(this);
        getLogger().info("Configuration loaded.");
    }

    public PluginConfig getPluginConfig() { return pluginConfig; }
    public EventManager getEventManager() { return eventManager; }
}
