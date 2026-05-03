package com.summit.summitchatevents.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Parsed, immutable configuration for the Heads or Tails event.
 * Loaded from {@code events/headsortails.yml} by {@link EventConfigLoader}.
 */
public final class HeadsOrTailsConfig {

    private final int    minPlayers;
    private final int    maxRounds;
    private final String rewardCommand;
    private final String rewardDisplayName;
    private final String msgBannerTop;
    private final String msgBannerBottom;
    private final String msgAnnounce;
    private final String msgRules;
    private final String msgHereWeGo;
    private final String msgAreYouReady;
    private final String msgPrizeLine;
    private final String msgChoose;
    private final String msgResult;
    private final String msgEliminated;
    private final String msgSurvived;
    private final String msgNoWinner;
    private final String msgWinnerBannerTop;
    private final String msgWinnerLine;
    private final String msgWinnerPrizeLine;
    private final String msgWinnerBannerBottom;
    private final String msgRewardPrivate;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public HeadsOrTailsConfig(final FileConfiguration cfg, final String rawPrefix) {
        minPlayers    = cfg.getInt("min-players", 2);
        maxRounds     = cfg.getInt("max-rounds", 10);
        rewardCommand     = cfg.getString("reward.command", "");
        rewardDisplayName = msg(cfg, rawPrefix, "reward.display-name", "&#FFD700a prize");

        msgBannerTop    = msg(cfg, rawPrefix, "messages.banner-top",
                "&e&m        &r &6&l   HEADS OR TAILS   &r &e&m        ");
        msgBannerBottom = msg(cfg, rawPrefix, "messages.banner-bottom",
                "&e&m==============================");
        msgAnnounce     = msg(cfg, rawPrefix, "messages.announce",
                "&6&lHeads or Tails is starting! Last one standing wins!");
        msgRules        = msg(cfg, rawPrefix, "messages.rules",
                "&7Each round type &aheads &7or &ctails &7in chat. Wrong guess? You're out!");
        msgHereWeGo     = msg(cfg, rawPrefix, "messages.here-we-go",
                "&a&lHere we go!");
        msgChoose       = msg(cfg, rawPrefix, "messages.choose",
                "%prefix%&eType &aheads &eor &ctails &enow!");
        msgResult       = msg(cfg, rawPrefix, "messages.result",
                "%prefix%&6It's &l%result%&6! %survivors% player(s) survive.");
        msgEliminated   = msg(cfg, rawPrefix, "messages.eliminated",
                "%prefix%&cYou picked &l%choice%&c \u2014 eliminated!");
        msgSurvived     = msg(cfg, rawPrefix, "messages.survived",
                "%prefix%&aYou picked &l%choice%&a \u2014 you survive!");
        msgNoWinner           = msg(cfg, rawPrefix, "messages.no-winner",
                "%prefix%&cThe event ended \u2014 nobody won!");
        msgWinnerBannerTop    = msg(cfg, rawPrefix, "messages.winner-banner-top",
                "<center><gradient:#FFD700:#FF8C00>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient>");
        msgWinnerLine         = msg(cfg, rawPrefix, "messages.winner-line",
                "<center><gradient:#FFD700:#FFC200>&l\uD83C\uDFC6 %player% WINS! \uD83C\uDFC6</gradient>");
        msgWinnerPrizeLine    = msg(cfg, rawPrefix, "messages.winner-prize-line",
                "<center>&#AAAAAA Prize: %prize%");
        msgWinnerBannerBottom = msg(cfg, rawPrefix, "messages.winner-banner-bottom",
                "<center><gradient:#FF8C00:#FFD700>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient>");
        msgRewardPrivate      = msg(cfg, rawPrefix, "messages.reward-private",
                "%prefix%<gradient:#FFD700:#FF8C00>&lCongratulations!</gradient> &eYou won %prize%&e!");
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public int    getMinPlayers()     { return minPlayers; }
    public int    getMaxRounds()      { return maxRounds; }
    public String getRewardCommand()      { return rewardCommand; }
    public String getRewardDisplayName()  { return rewardDisplayName; }
    public String getMsgBannerTop()   { return msgBannerTop; }
    public String getMsgBannerBottom(){ return msgBannerBottom; }
    public String getMsgAnnounce()    { return msgAnnounce; }
    public String getMsgRules()       { return msgRules; }
    public String getMsgHereWeGo()    { return msgHereWeGo; }
    public String getMsgAreYouReady() { return msgAreYouReady; }
    public String getMsgPrizeLine()   { return msgPrizeLine; }
    public String getMsgChoose()      { return msgChoose; }
    public String getMsgResult()      { return msgResult; }
    public String getMsgEliminated()  { return msgEliminated; }
    public String getMsgSurvived()    { return msgSurvived; }
    public String getMsgNoWinner()         { return msgNoWinner; }
    public String getMsgWinnerBannerTop()    { return msgWinnerBannerTop; }
    public String getMsgWinnerLine()         { return msgWinnerLine; }
    public String getMsgWinnerPrizeLine()    { return msgWinnerPrizeLine; }
    public String getMsgWinnerBannerBottom() { return msgWinnerBannerBottom; }
    public String getMsgRewardPrivate()      { return msgRewardPrivate; }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static String msg(final FileConfiguration cfg, final String rawPrefix,
                              final String key, final String def) {
        final String raw = cfg.getString(key, def);
        return PluginConfig.color((raw == null ? def : raw).replace("%prefix%", rawPrefix));
    }
}
