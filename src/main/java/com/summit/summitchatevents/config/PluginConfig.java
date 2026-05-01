package com.summit.summitchatevents.config;

import com.summit.summitchatevents.SummitChatEventsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed, pre-parsed view of {@code config.yml}.
 *
 * <p>Construct a new instance after every config reload. All values are read
 * once at construction time; {@code &} colour codes are translated to § on load.
 */
public final class PluginConfig {

    private final String  prefix;
    private final boolean debug;

    // count event
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
    // Constructor
    // -----------------------------------------------------------------------

    public PluginConfig(final SummitChatEventsPlugin plugin) {
        final FileConfiguration cfg = plugin.getConfig();

        prefix = color(cfg.getString("prefix", "&6[&eCount Up&6]&r "));
        debug  = cfg.getBoolean("debug", false);

        countMinDuration   = cfg.getInt("events.count.min-duration", 30);
        countMaxDuration   = cfg.getInt("events.count.max-duration", 90);
        countRewardCommand = cfg.getString("events.count.reward-command", "");

        countMsgAnnounce  = color(cfg.getString("events.count.messages.announce",
                "&e&lA Count Up event is about to begin! Get ready!"));
        countMsgRules     = color(cfg.getString("events.count.messages.rules",
                "&7How it works: &fType the next number in chat. Don't send two in a row!"));
        countMsgHereWeGo  = color(cfg.getString("events.count.messages.here-we-go",
                "&a&lHere we go!"));
        countMsgWinner    = color(cfg.getString("events.count.messages.winner",
                "&aEvent over! &eWinner: &6&l%player% &awith the last number &e%number%&a!"));
        countMsgNoWinner  = color(cfg.getString("events.count.messages.no-winner",
                "&cThe event ended &7\u2014 &cnobody scored!"));
        countMsgReward    = color(cfg.getString("events.count.messages.reward",
                "&6%player% &ehas been rewarded for winning!"));

        if (debug) {
            plugin.getLogger().info("[Config] prefix='" + prefix + "' duration="
                    + countMinDuration + "-" + countMaxDuration + "s");
        }
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public String  getPrefix()             { return prefix; }
    public boolean isDebug()               { return debug; }
    public int     getCountMinDuration()   { return countMinDuration; }
    public int     getCountMaxDuration()   { return countMaxDuration; }
    public String  getCountRewardCommand() { return countRewardCommand; }
    public String  getCountMsgAnnounce()   { return countMsgAnnounce; }
    public String  getCountMsgRules()      { return countMsgRules; }
    public String  getCountMsgHereWeGo()   { return countMsgHereWeGo; }
    public String  getCountMsgWinner()     { return countMsgWinner; }
    public String  getCountMsgNoWinner()   { return countMsgNoWinner; }
    public String  getCountMsgReward()     { return countMsgReward; }

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------

    /** Translates {@code &x} colour codes to the § equivalent. */
    public static String color(final String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Replaces {@code %player%} and {@code %number%} in a message template. */
    public static String format(final String template, final String player, final int number) {
        return template
                .replace("%player%", player)
                .replace("%number%", String.valueOf(number));
    }
}
