package com.summit.summitchatevents.config;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed, pre-parsed view of {@code config.yml}.
 *
 * <p>Construct a new instance after every config reload. All values are read
 * once at construction time; {@code &} colour codes are translated to § on load,
 * and {@code %prefix%} is substituted in every message field.
 *
 * <p>Wavelength-specific config is delegated to {@link WavelengthConfig}.
 */
public final class PluginConfig {

    // -----------------------------------------------------------------------
    // Global
    // -----------------------------------------------------------------------

    private final String  prefix;
    private final boolean debug;

    // -----------------------------------------------------------------------
    // Count Up event
    // -----------------------------------------------------------------------

    private final int    countMinDuration;
    private final int    countMaxDuration;
    private final String countRewardCommand;
    private final String countMsgAnnounce;
    private final String countMsgRules;
    private final String countMsgHereWeGo;
    private final String countMsgWinner;
    private final String countMsgNoWinner;
    private final String countMsgReward;

    // -----------------------------------------------------------------------
    // Wavelength event — fully delegated
    // -----------------------------------------------------------------------

    private final WavelengthConfig wavelengthConfig;
    private final String            stoppedMessage;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public PluginConfig(final SummitChatEventsPlugin plugin) {
        final FileConfiguration cfg = plugin.getConfig();

        // Raw prefix for %prefix% substitution (translated separately)
        final String rawPrefix = cfg.getString("prefix", "&6[&eSummitEvents&6]&r ");
        prefix = color(rawPrefix == null ? "" : rawPrefix);
        debug  = cfg.getBoolean("debug", false);

        // ── Count Up ────────────────────────────────────────────────────────
        countMinDuration   = cfg.getInt("events.count.min-duration", 30);
        countMaxDuration   = cfg.getInt("events.count.max-duration", 90);
        countRewardCommand = cfg.getString("events.count.reward-command", "");

        countMsgAnnounce  = msg(cfg, rawPrefix, "events.count.messages.announce",
                "%prefix%&e&lA Count Up event is about to begin! Get ready!");
        countMsgRules     = msg(cfg, rawPrefix, "events.count.messages.rules",
                "%prefix%&7How it works: &fType the next number. No two in a row!");
        countMsgHereWeGo  = msg(cfg, rawPrefix, "events.count.messages.here-we-go",
                "%prefix%&a&lHere we go!");
        countMsgWinner    = msg(cfg, rawPrefix, "events.count.messages.winner",
                "%prefix%&aEvent over! &eWinner: &6&l%player% &awith &e%number%&a!");
        countMsgNoWinner  = msg(cfg, rawPrefix, "events.count.messages.no-winner",
                "%prefix%&cThe event ended \u2014 nobody scored!");
        countMsgReward    = msg(cfg, rawPrefix, "events.count.messages.reward",
                "%prefix%&6%player% &ehas been rewarded!");

        // ── Wavelength ───────────────────────────────────────────────────────
        stoppedMessage = msg(cfg, rawPrefix == null ? "" : rawPrefix,
                "stopped-message",
                "%prefix%&cThe event has been stopped by an administrator.");

        wavelengthConfig = new WavelengthConfig(cfg,
                rawPrefix == null ? "" : rawPrefix,
                plugin.getLogger());

        if (debug) {
            plugin.getLogger().info("[Config] prefix='" + prefix + "' loaded.");
        }
    }

    // -----------------------------------------------------------------------
    // Accessors — global
    // -----------------------------------------------------------------------

    public String  getPrefix() { return prefix; }
    public boolean isDebug()   { return debug; }

    // -----------------------------------------------------------------------
    // Accessors — Count Up
    // -----------------------------------------------------------------------

    public int    getCountMinDuration()   { return countMinDuration; }
    public int    getCountMaxDuration()   { return countMaxDuration; }
    public String getCountRewardCommand() { return countRewardCommand; }
    public String getCountMsgAnnounce()   { return countMsgAnnounce; }
    public String getCountMsgRules()      { return countMsgRules; }
    public String getCountMsgHereWeGo()   { return countMsgHereWeGo; }
    public String getCountMsgWinner()     { return countMsgWinner; }
    public String getCountMsgNoWinner()   { return countMsgNoWinner; }
    public String getCountMsgReward()     { return countMsgReward; }

    // -----------------------------------------------------------------------
    // Accessors — Wavelength
    // -----------------------------------------------------------------------

    public WavelengthConfig getWavelengthConfig() { return wavelengthConfig; }
    public String           getStoppedMessage()   { return stoppedMessage; }

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------

    /** Translates {@code &x} colour codes to the § equivalent. */
    public static String color(final String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
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

    /** Reads a YAML string, substitutes %prefix%, then translates colour codes. */
    private static String msg(final FileConfiguration cfg, final String rawPrefix,
                              final String key, final String def) {
        final String raw = cfg.getString(key, def);
        return color((raw == null ? def : raw).replace("%prefix%",
                rawPrefix == null ? "" : rawPrefix));
    }
}
