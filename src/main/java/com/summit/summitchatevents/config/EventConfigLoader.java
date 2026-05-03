package com.summit.summitchatevents.config;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Loads per-event YAML configuration files from the plugin's data folder.
 *
 * <h3>File layout on disk</h3>
 * <pre>
 * plugins/SummitChatEvents/
 *   config.yml          ← global settings
 *   events/
 *     count.yml         ← Count Up settings
 *     wavelength.yml    ← Wavelength settings
 *     headsortails.yml  ← Heads or Tails settings
 * </pre>
 *
 * <h3>Reset behaviour</h3>
 * If a file is absent from the data folder it is copied from the jar's
 * {@code resources/events/} directory (same as {@code saveDefaultConfig()}).
 * Deleting a file and reloading the plugin restores all defaults.
 */
public final class EventConfigLoader {

    private EventConfigLoader() {}

    /**
     * Loads an event config file, writing the default from the jar if absent.
     *
     * @param plugin   the owning plugin (used for data folder and class loader)
     * @param fileName the file name inside {@code events/}, e.g. {@code "count.yml"}
     * @return the loaded {@link FileConfiguration}
     */
    public static FileConfiguration load(
            final SummitChatEventsPlugin plugin,
            final String fileName
    ) {
        final File dataDir  = new File(plugin.getDataFolder(), "events");
        final File diskFile = new File(dataDir, fileName);

        // Copy default from jar if the file doesn't exist yet
        if (!diskFile.exists()) {
            saveDefault(plugin, fileName, diskFile);
        }

        // Load from disk — overlay with jar defaults for any missing keys
        final FileConfiguration cfg = YamlConfiguration.loadConfiguration(diskFile);
        final InputStream jarStream = plugin.getResource("events/" + fileName);
        if (jarStream != null) {
            final FileConfiguration jarDefaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(jarStream, StandardCharsets.UTF_8));
            cfg.setDefaults(jarDefaults);
        }

        if (plugin.getPluginConfig() != null && plugin.getPluginConfig().isDebug()) {
            plugin.getLogger().info("[EventConfigLoader] Loaded events/" + fileName);
        }

        return cfg;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static void saveDefault(
            final SummitChatEventsPlugin plugin,
            final String fileName,
            final File dest
    ) {
        try {
            dest.getParentFile().mkdirs();
            plugin.saveResource("events/" + fileName, false);
            plugin.getLogger().info("[EventConfigLoader] Created default events/" + fileName);
        } catch (final Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "[EventConfigLoader] Could not save default events/" + fileName, e);
        }
    }
}
