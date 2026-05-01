package com.summit.summitchatevents.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Parsed configuration for the Wavelength event.
 *
 * <p>Constructed once per config load by {@link PluginConfig}. Immutable after
 * construction — all collections are unmodifiable views or defensive copies.
 *
 * <h3>Config structure expected</h3>
 * <pre>
 * events:
 *   wavelength:
 *     reward-command: "give %player% emerald 3"
 *     rounds:
 *       round1-duration: 40
 *       round2-duration: 25
 *       round3-duration: 15
 *     scales:
 *       - scale-min: "Worst mob"
 *         scale-max: "Best mob"
 *         prompts:
 *           - "Creeper"
 *           - "Zombie"
 *     messages:
 *       announce:    "%prefix%..."
 *       round-start: "%prefix%&eRound %round%: %scale_min% &7<-> &e%scale_max%"
 *       prompt:      "%prefix%&fToday's prompt: &e%prompt%"
 *       ...
 * </pre>
 */
public final class WavelengthConfig {

    // -----------------------------------------------------------------------
    // Scale data class
    // -----------------------------------------------------------------------

    /**
     * Represents one scale entry: a min label, a max label, and a list of prompts.
     *
     * <p>Immutable value type.
     */
    public static final class Scale {
        private final String       min;
        private final String       max;
        private final List<String> prompts;

        public Scale(final String min, final String max, final List<String> prompts) {
            this.min     = min;
            this.max     = max;
            this.prompts = Collections.unmodifiableList(new ArrayList<>(prompts));
        }

        /** The "low end" label of the scale (e.g. {@code "Worst mob"}). */
        public @NotNull String getMin() { return min; }

        /** The "high end" label of the scale (e.g. {@code "Best mob"}). */
        public @NotNull String getMax() { return max; }

        /** All prompts associated with this scale. Never empty (validated on load). */
        public @NotNull List<String> getPrompts() { return prompts; }

        /** Returns a random prompt from this scale's list. */
        public @NotNull String randomPrompt(final Random rng) {
            return prompts.get(rng.nextInt(prompts.size()));
        }

        @Override
        public String toString() {
            return "Scale{min='" + min + "', max='" + max + "', prompts=" + prompts.size() + "}";
        }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final String rewardCommand;

    /**
     * Per-round durations in ticks, in round order.
     * Index 0 = round 1, index 1 = round 2, etc.
     * If a round index exceeds the list size, the last value is used.
     */
    private final List<Long> roundDurationTicks;

    /** All loaded scales. At least one is guaranteed after validation. */
    private final List<Scale> scales;

    // Messages
    private final String msgAnnounce;
    private final String msgRules;
    private final String msgHereWeGo;
    private final String msgRoundStart;
    private final String msgScale;
    private final String msgPrompt;
    private final String msgRoundWinner;
    private final String msgWinner;
    private final String msgNoWinner;
    private final String msgReward;
    private final String msgGuessAck;

    // -----------------------------------------------------------------------
    // Constructor — called by PluginConfig
    // -----------------------------------------------------------------------

    public WavelengthConfig(final FileConfiguration yaml, final String rawPrefix, final Logger log) {
        final String base = "events.wavelength";
        final ConfigurationSection root = yaml.getConfigurationSection(base);

        rewardCommand = yaml.getString(base + ".reward-command", "");

        // ── Round durations ────────────────────────────────────────────────
        final List<Long> durations = new ArrayList<>();
        final ConfigurationSection roundSec =
                root != null ? root.getConfigurationSection("rounds") : null;
        if (roundSec != null) {
            // Collect keys in natural insertion order (round1-duration, round2-duration, …)
            final List<String> keys = new ArrayList<>(roundSec.getKeys(false));
            Collections.sort(keys);  // alphabetical sort keeps round1 < round2 < round3
            for (final String key : keys) {
                final int secs = roundSec.getInt(key, 30);
                durations.add((long) secs * 20L);
            }
        }
        if (durations.isEmpty()) {
            // Default: 3 rounds of 30 s each
            durations.add(600L);
            durations.add(600L);
            durations.add(600L);
            log.warning("[WavelengthConfig] No rounds defined — defaulting to 3 x 30s rounds.");
        }
        roundDurationTicks = Collections.unmodifiableList(durations);

        // ── Scales ─────────────────────────────────────────────────────────
        final List<Scale> loadedScales = new ArrayList<>();
        final List<?> scaleList = yaml.getList(base + ".scales");
        if (scaleList != null) {
            for (final Object obj : scaleList) {
                if (!(obj instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                final Map<String, Object> map = (Map<String, Object>) obj;

                final String scaleMin = str(map, "scale-min", "?");
                final String scaleMax = str(map, "scale-max", "?");

                final List<String> prompts = new ArrayList<>();
                final Object promptObj = map.get("prompts");
                if (promptObj instanceof List) {
                    for (final Object p : (List<?>) promptObj) {
                        if (p != null) prompts.add(p.toString());
                    }
                }
                if (prompts.isEmpty()) {
                    log.warning("[WavelengthConfig] Scale '" + scaleMin + " <-> " + scaleMax
                            + "' has no prompts — skipping.");
                    continue;
                }
                loadedScales.add(new Scale(scaleMin, scaleMax, prompts));
            }
        }
        if (loadedScales.isEmpty()) {
            // Fallback so the event can still run
            loadedScales.add(new Scale("Worst", "Best",
                    List.of("Default prompt — add scales to config!")));
            log.warning("[WavelengthConfig] No valid scales found — using built-in fallback.");
        }
        scales = Collections.unmodifiableList(loadedScales);

        // ── Messages ───────────────────────────────────────────────────────
        final String msgBase = base + ".messages";
        msgAnnounce   = msg(yaml, rawPrefix, msgBase + ".announce",
                "%prefix%&d&lA Wavelength event is starting!");
        msgRules      = msg(yaml, rawPrefix, msgBase + ".rules",
                "%prefix%&7Place the prompt on the scale — type a number 1-100!");
        msgHereWeGo   = msg(yaml, rawPrefix, msgBase + ".here-we-go",
                "%prefix%&a&lHere we go!");
        msgRoundStart = msg(yaml, rawPrefix, msgBase + ".round-start",
                "%prefix%&eRound &6&l%round%&e has started!");
        msgScale      = msg(yaml, rawPrefix, msgBase + ".scale",
                "%prefix%&7Scale: &e%scale_min% &7<-> &e%scale_max%");
        msgPrompt     = msg(yaml, rawPrefix, msgBase + ".prompt",
                "%prefix%&fPrompt: &e&l%prompt%");
        msgRoundWinner = msg(yaml, rawPrefix, msgBase + ".round-winner",
                "%prefix%&eRound &6%round%&e winner: &6&l%player% &e(guessed &6%guess%&e, target &6%target%&e)!");
        msgWinner     = msg(yaml, rawPrefix, msgBase + ".winner",
                "%prefix%&aEvent over! &eWinner: &6&l%player%&a!");
        msgNoWinner   = msg(yaml, rawPrefix, msgBase + ".no-winner",
                "%prefix%&cThe event ended \u2014 nobody scored!");
        msgReward     = msg(yaml, rawPrefix, msgBase + ".reward",
                "%prefix%&6%player% &ehas been rewarded for winning Wavelength!");
        msgGuessAck   = msg(yaml, rawPrefix, msgBase + ".guess-ack",
                "%prefix%&7Your guess &e%guess%&7 has been recorded!");

        if (log.isLoggable(java.util.logging.Level.FINE)) {
            log.fine("[WavelengthConfig] Loaded " + scales.size() + " scale(s), "
                    + roundDurationTicks.size() + " round(s).");
        }
    }

    // -----------------------------------------------------------------------
    // Round duration API
    // -----------------------------------------------------------------------

    /** Total number of configured rounds. */
    public int getRoundCount() { return roundDurationTicks.size(); }

    /**
     * Duration in ticks for the given round (1-indexed).
     * If {@code round} exceeds the configured count, the last configured duration is returned.
     */
    public long getRoundDurationTicks(final int round) {
        if (roundDurationTicks.isEmpty()) return 600L;
        final int idx = Math.min(round - 1, roundDurationTicks.size() - 1);
        return roundDurationTicks.get(Math.max(idx, 0));
    }

    // -----------------------------------------------------------------------
    // Scale API
    // -----------------------------------------------------------------------

    /** All loaded scales. Never empty. */
    public @NotNull List<Scale> getScales() { return scales; }

    /**
     * Returns a randomly chosen scale.
     * All scales have equal probability.
     */
    public @NotNull Scale randomScale(final Random rng) {
        return scales.get(rng.nextInt(scales.size()));
    }

    // -----------------------------------------------------------------------
    // Message accessors
    // -----------------------------------------------------------------------

    public String getRewardCommand()  { return rewardCommand; }
    public String getMsgAnnounce()    { return msgAnnounce; }
    public String getMsgRules()       { return msgRules; }
    public String getMsgHereWeGo()    { return msgHereWeGo; }
    public String getMsgRoundStart()  { return msgRoundStart; }
    public String getMsgScale()       { return msgScale; }
    public String getMsgPrompt()      { return msgPrompt; }
    public String getMsgRoundWinner() { return msgRoundWinner; }
    public String getMsgWinner()      { return msgWinner; }
    public String getMsgNoWinner()    { return msgNoWinner; }
    public String getMsgReward()      { return msgReward; }
    public String getMsgGuessAck()    { return msgGuessAck; }

    // -----------------------------------------------------------------------
    // Message formatting helpers
    // -----------------------------------------------------------------------

    /**
     * Substitutes all Wavelength-specific placeholders in {@code template}:
     * {@code %round%}, {@code %player%}, {@code %guess%}, {@code %target%},
     * {@code %scale_min%}, {@code %scale_max%}, {@code %prompt%}.
     *
     * <p>Pass {@code null} or {@code -1} to skip individual substitutions.
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
        String s = template;
        if (round    >= 0)    s = s.replace("%round%",     String.valueOf(round));
        if (player   != null) s = s.replace("%player%",    player);
        if (guess    >= 0)    s = s.replace("%guess%",     String.valueOf(guess));
        if (target   >= 0)    s = s.replace("%target%",    String.valueOf(target));
        if (scaleMin != null) s = s.replace("%scale_min%", scaleMin);
        if (scaleMax != null) s = s.replace("%scale_max%", scaleMax);
        if (prompt   != null) s = s.replace("%prompt%",    prompt);
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
