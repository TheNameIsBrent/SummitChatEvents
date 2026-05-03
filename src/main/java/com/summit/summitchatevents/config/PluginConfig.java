package com.summit.summitchatevents.config;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import com.summit.summitchatevents.utils.MessageFormatter;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed view of {@code config.yml} — global settings only.
 *
 * <p>Event-specific settings are loaded from their own files via
 * {@link EventConfigLoader} and stored in dedicated config objects:
 * <ul>
 *   <li>{@link CountUpConfig}    — {@code events/count.yml}</li>
 *   <li>{@link WavelengthConfig} — {@code events/wavelength.yml}</li>
 *   <li>{@link HeadsOrTailsConfig} — {@code events/headsortails.yml}</li>
 * </ul>
 */
public final class PluginConfig {

    private final String  prefix;
    private final boolean debug;
    private final String  stoppedMessage;

    private final CountUpConfig       countUpConfig;
    private final WavelengthConfig    wavelengthConfig;
    private final HeadsOrTailsConfig  headsOrTailsConfig;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public PluginConfig(final SummitChatEventsPlugin plugin) {
        final FileConfiguration cfg = plugin.getConfig();

        final String rawPrefix = cfg.getString("prefix", "&6[&eSummitEvents&6]&r ");
        prefix = color(rawPrefix == null ? "" : rawPrefix);
        debug  = cfg.getBoolean("debug", false);

        stoppedMessage = msg(cfg, rawPrefix == null ? "" : rawPrefix,
                "stopped-message",
                "%prefix%&cThe event has been stopped by an administrator.");

        // ── Per-event configs — each from its own file ───────────────────────
        final String rp = rawPrefix == null ? "" : rawPrefix;

        countUpConfig = new CountUpConfig(
                EventConfigLoader.load(plugin, "count.yml"), rp);

        wavelengthConfig = new WavelengthConfig(
                EventConfigLoader.load(plugin, "wavelength.yml"), rp, plugin.getLogger());

        headsOrTailsConfig = new HeadsOrTailsConfig(
                EventConfigLoader.load(plugin, "headsortails.yml"), rp);

        if (debug) {
            plugin.getLogger().info("[Config] Loaded. prefix='" + prefix + "'");
        }
    }

    // -----------------------------------------------------------------------
    // Accessors — global
    // -----------------------------------------------------------------------

    public String  getPrefix()         { return prefix; }
    public boolean isDebug()           { return debug; }
    public String  getStoppedMessage() { return stoppedMessage; }

    // -----------------------------------------------------------------------
    // Accessors — per-event configs
    // -----------------------------------------------------------------------

    public CountUpConfig      getCountUpConfig()       { return countUpConfig; }
    public WavelengthConfig   getWavelengthConfig()    { return wavelengthConfig; }
    public HeadsOrTailsConfig getHeadsOrTailsConfig()  { return headsOrTailsConfig; }

    // -----------------------------------------------------------------------
    // Static helpers — used by all event config classes
    // -----------------------------------------------------------------------

    /**
     * Phase 1 (config load): translates {@code &x} codes to § equivalents.
     * Gradients, centering, and hex codes are left intact for phase 2.
     */
    public static String color(final String s) {
        return s == null ? "" : MessageFormatter.translateCodes(s);
    }

    /**
     * Phase 2 (broadcast time): call after all %placeholder% values are substituted.
     * Runs center detection, gradient expansion, hex translation, and centering.
     */
    public static String broadcast(final String s) {
        return s == null ? "" : MessageFormatter.process(s);
    }

    /** Replaces {@code %player%}, {@code %number%}, and {@code %round%} in a template. */
    public static String format(
            final String template,
            final @org.jetbrains.annotations.Nullable String player,
            final int number,
            final int round
    ) {
        String s = template;
        if (player != null) s = s.replace("%player%", player);
        if (number >= 0)    s = s.replace("%number%", String.valueOf(number));
        if (round  >= 0)    s = s.replace("%round%",  String.valueOf(round));
        return s;
    }

    public static String format(final String template, final String player, final int number) {
        return format(template, player, number, -1);
    }

    public static String format(final String template, final String player) {
        return format(template, player, -1, -1);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static String msg(final FileConfiguration cfg, final String rawPrefix,
                              final String key, final String def) {
        final String raw = cfg.getString(key, def);
        return color((raw == null ? def : raw).replace("%prefix%", rawPrefix));
    }
}
