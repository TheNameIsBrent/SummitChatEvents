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
    // Wavelength event
    // -----------------------------------------------------------------------

    private final int    wavelengthMaxRounds;
    private final String wavelengthRewardCommand;
    private final String wavelengthMsgAnnounce;
    private final String wavelengthMsgRules;
    private final String wavelengthMsgHereWeGo;
    private final String wavelengthMsgRoundStart;
    private final String wavelengthMsgWinner;
    private final String wavelengthMsgNoWinner;
    private final String wavelengthMsgReward;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public PluginConfig(final SummitChatEventsPlugin plugin) {
        final FileConfiguration cfg = plugin.getConfig();

        // Read raw prefix first (no substitution on itself)
        prefix = color(cfg.getString("prefix", "&6[&eSummitEvents&6]&r "));
        debug  = cfg.getBoolean("debug", false);

        // ── Count Up ────────────────────────────────────────────────────────
        countMinDuration   = cfg.getInt("events.count.min-duration", 30);
        countMaxDuration   = cfg.getInt("events.count.max-duration", 90);
        countRewardCommand = cfg.getString("events.count.reward-command", "");

        countMsgAnnounce  = msg(cfg, "events.count.messages.announce",
                "%prefix%&e&lA Count Up event is about to begin! Get ready!");
        countMsgRules     = msg(cfg, "events.count.messages.rules",
                "%prefix%&7How it works: &fType the next number. No two in a row!");
        countMsgHereWeGo  = msg(cfg, "events.count.messages.here-we-go",
                "%prefix%&a&lHere we go!");
        countMsgWinner    = msg(cfg, "events.count.messages.winner",
                "%prefix%&aEvent over! &eWinner: &6&l%player% &awith &e%number%&a!");
        countMsgNoWinner  = msg(cfg, "events.count.messages.no-winner",
                "%prefix%&cThe event ended \u2014 nobody scored!");
        countMsgReward    = msg(cfg, "events.count.messages.reward",
                "%prefix%&6%player% &ehas been rewarded!");

        // ── Wavelength ───────────────────────────────────────────────────────
        wavelengthMaxRounds     = cfg.getInt("events.wavelength.max-rounds", 5);
        wavelengthRewardCommand = cfg.getString("events.wavelength.reward-command", "");

        wavelengthMsgAnnounce   = msg(cfg, "events.wavelength.messages.announce",
                "%prefix%&d&lA Wavelength event is starting! Get ready to guess!");
        wavelengthMsgRules      = msg(cfg, "events.wavelength.messages.rules",
                "%prefix%&7How it works: &fGuess a number between 1 and 100. Closest wins!");
        wavelengthMsgHereWeGo   = msg(cfg, "events.wavelength.messages.here-we-go",
                "%prefix%&a&lHere we go!");
        wavelengthMsgRoundStart = msg(cfg, "events.wavelength.messages.round-start",
                "%prefix%&eRound &6&l%round%&e started! Type your guess (1-100).");
        wavelengthMsgWinner     = msg(cfg, "events.wavelength.messages.winner",
                "%prefix%&aEvent over! &eWinner: &6&l%player%&a. Well played!");
        wavelengthMsgNoWinner   = msg(cfg, "events.wavelength.messages.no-winner",
                "%prefix%&cThe event ended \u2014 nobody scored!");
        wavelengthMsgReward     = msg(cfg, "events.wavelength.messages.reward",
                "%prefix%&6%player% &ehas been rewarded for winning Wavelength!");

        if (debug) {
            plugin.getLogger().info("[Config] prefix='" + prefix + "'");
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

    public int    getWavelengthMaxRounds()     { return wavelengthMaxRounds; }
    public String getWavelengthRewardCommand() { return wavelengthRewardCommand; }
    public String getWavelengthMsgAnnounce()   { return wavelengthMsgAnnounce; }
    public String getWavelengthMsgRules()      { return wavelengthMsgRules; }
    public String getWavelengthMsgHereWeGo()   { return wavelengthMsgHereWeGo; }
    public String getWavelengthMsgRoundStart() { return wavelengthMsgRoundStart; }
    public String getWavelengthMsgWinner()     { return wavelengthMsgWinner; }
    public String getWavelengthMsgNoWinner()   { return wavelengthMsgNoWinner; }
    public String getWavelengthMsgReward()     { return wavelengthMsgReward; }

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------

    /** Translates {@code &x} colour codes to the § equivalent. */
    public static String color(final String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    /**
     * Reads a message from YAML (falling back to {@code def}), translates
     * colour codes, then substitutes {@code %player%}, {@code %number%}, and
     * {@code %round%} placeholders.
     *
     * @param player   player name  — pass {@code null} to skip substitution
     * @param number   numeric value — pass {@code -1} to skip
     * @param round    round number  — pass {@code -1} to skip
     */
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

    /** Convenience overload for player + number (count event). */
    public static String format(final String template, final String player, final int number) {
        return format(template, player, number, -1);
    }

    /** Convenience overload for player only. */
    public static String format(final String template, final String player) {
        return format(template, player, -1, -1);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Reads a YAML string, applies the {@code %prefix%} substitution, then
     * translates colour codes. Used for all message fields.
     */
    private String msg(final FileConfiguration cfg, final String key, final String def) {
        final String raw = cfg.getString(key, def);
        return color(raw == null ? def : raw.replace("%prefix%", getRawPrefix(cfg)));
    }

    /**
     * Returns the raw (& not yet translated) prefix string from config,
     * used only during construction so {@code %prefix%} is substituted before
     * colour translation.
     */
    private static String getRawPrefix(final FileConfiguration cfg) {
        final String raw = cfg.getString("prefix", "&6[&eSummitEvents&6]&r ");
        return raw == null ? "" : raw;
    }
}
