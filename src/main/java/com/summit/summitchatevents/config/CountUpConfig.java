package com.summit.summitchatevents.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Parsed, immutable configuration for the Count Up event.
 * Loaded from {@code events/count.yml} by {@link EventConfigLoader}.
 */
public final class CountUpConfig {

    private final int    minPlayers;
    private final int    minDuration;
    private final int    maxDuration;
    private final String rewardCommand;
    private final String rewardDisplayName;
    private final String msgBannerTop;
    private final String msgBannerBottom;
    private final String msgAnnounce;
    private final String msgRules;
    private final String msgHereWeGo;
    private final String msgAreYouReady;
    private final String msgPrizeLine;
    private final String msgNoWinner;
    private final String msgWinnerBannerTop;
    private final String msgWinnerLine;
    private final String msgWinnerPrizeLine;
    private final String msgWinnerBannerBottom;
    private final String msgRewardPrivate;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public CountUpConfig(final FileConfiguration cfg, final String rawPrefix) {
        minPlayers         = cfg.getInt("min-players", 2);
        minDuration        = cfg.getInt("min-duration", 30);
        maxDuration        = cfg.getInt("max-duration", 90);
        rewardCommand      = cfg.getString("reward.command", "");
        rewardDisplayName  = msg(cfg, rawPrefix, "reward.display-name", "&#FFD700a prize");

        msgBannerTop          = msg(cfg, rawPrefix, "messages.banner-top",
                "<center><gradient:#FFD700:#FF8C00>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient>");
        msgBannerBottom       = msg(cfg, rawPrefix, "messages.banner-bottom",
                "<center><gradient:#FF8C00:#FFD700>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient>");
        msgAnnounce           = msg(cfg, rawPrefix, "messages.announce",
                "<center><gradient:#FFD700:#FF8C00>&l✦ COUNT UP EVENT ✦</gradient>");
        msgRules              = msg(cfg, rawPrefix, "messages.rules",
                "<center>&#AAAAAA▸ Type the next number. &#FF6B6BNo two in a row!");
        msgHereWeGo           = msg(cfg, rawPrefix, "messages.here-we-go",
                "<center><gradient:#00FF87:#00CFFF>&l» Here we go! «</gradient>");
        msgAreYouReady        = msg(cfg, rawPrefix, "messages.are-you-ready",
                "<center>&#AAAAAA Are you ready?");
        msgPrizeLine          = msg(cfg, rawPrefix, "messages.prize-line",
                "<center>&#AAAAAA Prize: %prize%");
        msgNoWinner           = msg(cfg, rawPrefix, "messages.no-winner",
                "%prefix%&cThe event ended \u2014 nobody scored!");
        msgWinnerBannerTop    = msg(cfg, rawPrefix, "messages.winner-banner-top",
                "<center><gradient:#FFD700:#FF8C00>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient>");
        msgWinnerLine         = msg(cfg, rawPrefix, "messages.winner-line",
                "<center><gradient:#FFD700:#FFA500>&l\uD83C\uDFC6 %player% WINS! \uD83C\uDFC6</gradient>");
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

    public int    getMinPlayers()          { return minPlayers; }
    public int    getMinDuration()         { return minDuration; }
    public int    getMaxDuration()         { return maxDuration; }
    public String getRewardCommand()       { return rewardCommand; }
    public String getRewardDisplayName()   { return rewardDisplayName; }
    public String getMsgBannerTop()        { return msgBannerTop; }
    public String getMsgBannerBottom()     { return msgBannerBottom; }
    public String getMsgAnnounce()         { return msgAnnounce; }
    public String getMsgRules()            { return msgRules; }
    public String getMsgHereWeGo()         { return msgHereWeGo; }
    public String getMsgAreYouReady()      { return msgAreYouReady; }
    public String getMsgPrizeLine()        { return msgPrizeLine; }
    public String getMsgNoWinner()         { return msgNoWinner; }
    public String getMsgWinnerBannerTop()    { return msgWinnerBannerTop; }
    public String getMsgWinnerLine()         { return msgWinnerLine; }
    public String getMsgWinnerPrizeLine()    { return msgWinnerPrizeLine; }
    public String getMsgWinnerBannerBottom() { return msgWinnerBannerBottom; }
    public String getMsgRewardPrivate()    { return msgRewardPrivate; }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static String msg(final FileConfiguration cfg, final String rawPrefix,
                              final String key, final String def) {
        final String raw = cfg.getString(key, def);
        return PluginConfig.color((raw == null ? def : raw).replace("%prefix%", rawPrefix));
    }
}
