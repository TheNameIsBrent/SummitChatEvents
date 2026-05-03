package com.summit.summitchatevents.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Parsed, immutable configuration for the Wavelength event.
 * Constructed once per config load by {@link PluginConfig}.
 */
public final class WavelengthConfig {

    // -----------------------------------------------------------------------
    // Scale — immutable value type
    // -----------------------------------------------------------------------

    public static final class Scale {
        private final String       min;
        private final String       max;
        private final List<String> prompts;

        public Scale(final String min, final String max, final List<String> prompts) {
            this.min     = min;
            this.max     = max;
            this.prompts = Collections.unmodifiableList(new ArrayList<>(prompts));
        }

        public @NotNull String       getMin()     { return min; }
        public @NotNull String       getMax()     { return max; }
        public @NotNull List<String> getPrompts() { return prompts; }

        public @NotNull String randomPrompt(final Random rng) {
            return prompts.get(rng.nextInt(prompts.size()));
        }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final int    minPlayers;
    private final String rewardCommand;
    private final String rewardDisplayName;
    private final List<Long> roundDurationTicks;
    private final List<Scale> scales;

    // Messages
    private final String msgBannerTop;
    private final String msgBannerBottom;
    private final String msgAnnounce;
    private final String msgRules;
    private final String msgHereWeGo;
    private final String msgAreYouReady;
    private final String msgPrizeLine;
    private final String msgScale;
    private final String msgPrompt;
    private final String msgRoundResultSingle;
    private final String msgRoundResultTie;
    private final String msgGuessingOver;
    private final String msgCountdown;
    private final String msgRound2Start;
    private final String msgFinalRound;
    private final String msgTieEliminated;
    private final String msgTieAdvanced;
    private final String msgWinner;
    private final String msgMultipleWinners;
    private final String msgNoWinner;
    private final String msgWinnerBannerTop;
    private final String msgWinnerLine;
    private final String msgWinnerPrizeLine;
    private final String msgWinnerAvgLine;
    private final String msgWinnerBannerBottom;
    private final String msgWinnerMultiLine;
    private final String msgRewardPrivate;
    private final String msgGuessAck;
    private final String msgStopped;

    // -----------------------------------------------------------------------
    // Constructor — called by PluginConfig
    // -----------------------------------------------------------------------

    public WavelengthConfig(final FileConfiguration yaml, final String rawPrefix, final Logger log) {
        minPlayers       = yaml.getInt("min-players", 3);
        rewardCommand    = yaml.getString("reward.command", "");
        rewardDisplayName = msg(yaml, rawPrefix, "reward.display-name", "&#FFD700a prize");

        // ── Round durations ────────────────────────────────────────────────
        final List<Long> durations = new ArrayList<>();
        final ConfigurationSection roundSec = yaml.getConfigurationSection("rounds");
        if (roundSec != null) {
            final List<String> keys = new ArrayList<>(roundSec.getKeys(false));
            Collections.sort(keys);
            for (final String key : keys) {
                durations.add((long) roundSec.getInt(key, 15) * 20L);
            }
        }
        if (durations.isEmpty()) {
            durations.add(300L); durations.add(200L); durations.add(100L); // 15s/10s/5s
            log.warning("[WavelengthConfig] No rounds defined — defaulting to 15s/10s/5s.");
        }
        roundDurationTicks = Collections.unmodifiableList(durations);

        // ── Scales ─────────────────────────────────────────────────────────
        final List<Scale> loadedScales = new ArrayList<>();
        final List<?> scaleList = yaml.getList("scales");
        if (scaleList != null) {
            for (final Object obj : scaleList) {
                if (!(obj instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                final Map<String, Object> map = (Map<String, Object>) obj;
                final String scaleMin = str(map, "scale-min", "?");
                final String scaleMax = str(map, "scale-max", "?");
                final List<String> prompts = new ArrayList<>();
                final Object po = map.get("prompts");
                if (po instanceof List) {
                    for (final Object p : (List<?>) po) {
                        if (p != null) prompts.add(p.toString());
                    }
                }
                if (!prompts.isEmpty()) loadedScales.add(new Scale(scaleMin, scaleMax, prompts));
                else log.warning("[WavelengthConfig] Scale '" + scaleMin + "' has no prompts — skipped.");
            }
        }
        if (loadedScales.isEmpty()) {
            loadedScales.add(new Scale("Worst", "Best", List.of("Default prompt — add scales!")));
            log.warning("[WavelengthConfig] No valid scales — using built-in fallback.");
        }
        scales = Collections.unmodifiableList(loadedScales);

        // ── Messages ───────────────────────────────────────────────────────
        final String mb = "messages";
        msgBannerTop         = msg(yaml, rawPrefix, mb + ".banner-top",
                "&d&m        &r &d&l   WAVELENGTH EVENT   &r &d&m        ");
        msgBannerBottom      = msg(yaml, rawPrefix, mb + ".banner-bottom",
                "&d&m==============================");
        msgAnnounce          = msg(yaml, rawPrefix, mb + ".announce",
                "&d&lA Wavelength event is starting!");
        msgRules             = msg(yaml, rawPrefix, mb + ".rules",
                "&7Place the prompt on the scale. &fType &e0&f-&e100&f.");
        msgHereWeGo          = msg(yaml, rawPrefix, mb + ".here-we-go",
                "&a&lHere we go!");
        msgAreYouReady       = msg(yaml, rawPrefix, mb + ".are-you-ready",
                "<center>&#AAAAAA Are you ready?");
        msgPrizeLine         = msg(yaml, rawPrefix, mb + ".prize-line",
                "<center>&#AAAAAA Prize: %prize%");
        msgScale             = msg(yaml, rawPrefix, mb + ".scale",
                "%prefix%&7Scale: &e%scale_min% &7\u2194 &e%scale_max%");
        msgPrompt            = msg(yaml, rawPrefix, mb + ".prompt",
                "%prefix%&fPrompt: &d&l%prompt%");
        msgRoundResultSingle = msg(yaml, rawPrefix, mb + ".round-result-single",
                "%prefix%&eAverage: &6%average%&e. Winner: &6&l%player% &7(&6%guess%&7)!");
        msgRoundResultTie    = msg(yaml, rawPrefix, mb + ".round-result-tie",
                "%prefix%&eAverage: &6%average%&e. Tied: &6&l%players%");
        msgGuessingOver      = msg(yaml, rawPrefix, mb + ".guessing-over",
                "&6&l\u2728 Ohhh, lots of guesses! Here comes the result...");
        msgCountdown         = msg(yaml, rawPrefix, mb + ".countdown",
                "%prefix%&e%seconds%s remaining!");
        msgRound2Start       = msg(yaml, rawPrefix, mb + ".round-2-start",
                "%prefix%&6&lTie! &eRound 2 \u2014 only these players continue: &6%players%");
        msgFinalRound        = msg(yaml, rawPrefix, mb + ".final-round",
                "%prefix%&c&lFinal Round! &eLast chance \u2014 remaining: &6%players%");
        msgTieEliminated     = msg(yaml, rawPrefix, mb + ".tie-eliminated",
                "%prefix%&cYou have been eliminated!");
        msgTieAdvanced       = msg(yaml, rawPrefix, mb + ".tie-advanced",
                "%prefix%&aYou advanced to the next round!");
        msgWinner            = msg(yaml, rawPrefix, mb + ".winner",
                "%prefix%&aEvent over! &eWinner: &6&l%player%&a!");
        msgMultipleWinners   = msg(yaml, rawPrefix, mb + ".multiple-winners",
                "%prefix%&6&lTie! &eAll tied players win: &6%players%");
        msgNoWinner          = msg(yaml, rawPrefix, mb + ".no-winner",
                "%prefix%&cThe event ended \u2014 nobody scored!");
        msgWinnerBannerTop    = msg(yaml, rawPrefix, mb + ".winner-banner-top",
                "<center><gradient:#B400FF:#FF00FF>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient>");
        msgWinnerLine         = msg(yaml, rawPrefix, mb + ".winner-line",
                "<center><gradient:#B400FF:#FF00FF>&l\uD83C\uDFC6 %player% WINS! \uD83C\uDFC6</gradient>");
        msgWinnerPrizeLine    = msg(yaml, rawPrefix, mb + ".winner-prize-line",
                "<center>&#AAAAAA Prize: %prize%");
        msgWinnerAvgLine      = msg(yaml, rawPrefix, mb + ".winner-avg-line",
                "<center>&#AAAAAA Average: %average%");
        msgWinnerBannerBottom = msg(yaml, rawPrefix, mb + ".winner-banner-bottom",
                "<center><gradient:#FF00FF:#B400FF>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</gradient>");
        msgWinnerMultiLine    = msg(yaml, rawPrefix, mb + ".winner-multi-line",
                "<center><gradient:#B400FF:#FF00FF>&l\uD83C\uDFC6 %players% WIN! \uD83C\uDFC6</gradient>");
        msgRewardPrivate      = msg(yaml, rawPrefix, mb + ".reward-private",
                "%prefix%<gradient:#B400FF:#FF00FF>&lCongratulations!</gradient> &eYou won %prize%&e!");
        msgGuessAck          = msg(yaml, rawPrefix, mb + ".guess-ack",
                "%prefix%&7Your guess &e%guess%&7 has been recorded!");
        msgStopped           = msg(yaml, rawPrefix, mb + ".stopped",
                "%prefix%&cThe event was stopped by an administrator.");
    }

    // -----------------------------------------------------------------------
    // Round duration API
    // -----------------------------------------------------------------------

    public int  getRoundCount() { return roundDurationTicks.size(); }

    public long getRoundDurationTicks(final int round) {
        if (roundDurationTicks.isEmpty()) return 300L;
        final int idx = Math.min(round - 1, roundDurationTicks.size() - 1);
        return roundDurationTicks.get(Math.max(idx, 0));
    }

    // -----------------------------------------------------------------------
    // Scale API
    // -----------------------------------------------------------------------

    public @NotNull List<Scale>  getScales()               { return scales; }
    public @NotNull Scale        randomScale(final Random r){ return scales.get(r.nextInt(scales.size())); }

    // -----------------------------------------------------------------------
    // Message accessors
    // -----------------------------------------------------------------------

    public int    getMinPlayers()          { return minPlayers; }
    public String getRewardCommand()       { return rewardCommand; }
    public String getRewardDisplayName()   { return rewardDisplayName; }
    public String getMsgBannerTop()        { return msgBannerTop; }
    public String getMsgBannerBottom()     { return msgBannerBottom; }
    public String getMsgAnnounce()         { return msgAnnounce; }
    public String getMsgRules()            { return msgRules; }
    public String getMsgHereWeGo()         { return msgHereWeGo; }
    public String getMsgAreYouReady()      { return msgAreYouReady; }
    public String getMsgPrizeLine()        { return msgPrizeLine; }
    public String getMsgScale()            { return msgScale; }
    public String getMsgPrompt()           { return msgPrompt; }
    public String getMsgRoundResultSingle(){ return msgRoundResultSingle; }
    public String getMsgRoundResultTie()   { return msgRoundResultTie; }
    public String getMsgGuessingOver()     { return msgGuessingOver; }
    public String getMsgCountdown()        { return msgCountdown; }
    public String getMsgRound2Start()      { return msgRound2Start; }
    public String getMsgFinalRound()       { return msgFinalRound; }
    public String getMsgTieEliminated()    { return msgTieEliminated; }
    public String getMsgTieAdvanced()      { return msgTieAdvanced; }
    public String getMsgWinner()           { return msgWinner; }
    public String getMsgMultipleWinners()  { return msgMultipleWinners; }
    public String getMsgNoWinner()         { return msgNoWinner; }
    public String getMsgGuessAck()         { return msgGuessAck; }
    public String getMsgStopped()          { return msgStopped; }
    public String getMsgWinnerBannerTop()    { return msgWinnerBannerTop; }
    public String getMsgWinnerLine()         { return msgWinnerLine; }
    public String getMsgWinnerPrizeLine()    { return msgWinnerPrizeLine; }
    public String getMsgWinnerAvgLine()      { return msgWinnerAvgLine; }
    public String getMsgWinnerBannerBottom() { return msgWinnerBannerBottom; }
    public String getMsgWinnerMultiLine()    { return msgWinnerMultiLine; }
    public String getMsgRewardPrivate()      { return msgRewardPrivate; }

    // -----------------------------------------------------------------------
    // Format helpers
    // -----------------------------------------------------------------------

    /**
     * Substitutes all Wavelength placeholders in {@code template}.
     * Pass {@code null} or {@code -1} to skip individual substitutions.
     */
    public static String format(
            final String template,
            final int round,
            final @Nullable String player,
            final int guess,
            final int target,
            final @Nullable String scaleMin,
            final @Nullable String scaleMax,
            final @Nullable String prompt
    ) {
        return format(template, round, player, guess, target, scaleMin, scaleMax, prompt, null, null);
    }

    public static String format(
            final String template,
            final int round,
            final @Nullable String player,
            final int guess,
            final int target,
            final @Nullable String scaleMin,
            final @Nullable String scaleMax,
            final @Nullable String prompt,
            final @Nullable String average,
            final @Nullable String players
    ) {
        String s = template;
        if (round    >= 0)    s = s.replace("%round%",     String.valueOf(round));
        if (player   != null) s = s.replace("%player%",    player);
        if (guess    >= 0)    s = s.replace("%guess%",     String.valueOf(guess));
        if (target   >= 0)    s = s.replace("%target%",    String.valueOf(target));
        if (scaleMin != null) s = s.replace("%scale_min%", scaleMin);
        if (scaleMax != null) s = s.replace("%scale_max%", scaleMax);
        if (prompt   != null) s = s.replace("%prompt%",    prompt);
        if (average  != null) s = s.replace("%average%",   average);
        if (players  != null) s = s.replace("%players%",   players);
        return s;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static String msg(final FileConfiguration yaml, final String rawPrefix,
                              final String key, final String def) {
        final String raw = yaml.getString(key, def);
        return PluginConfig.color((raw == null ? def : raw).replace("%prefix%", rawPrefix));
    }

    private static String str(final Map<String, Object> map, final String key, final String def) {
        final Object v = map.get(key);
        return v != null ? v.toString() : def;
    }
}
