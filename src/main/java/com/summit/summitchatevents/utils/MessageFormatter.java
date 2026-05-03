package com.summit.summitchatevents.utils;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes config message strings into final § -coded Minecraft chat lines.
 *
 * <h3>Supported syntax</h3>
 * <ul>
 *   <li>{@code &x} — legacy colour/format codes (translated last)</li>
 *   <li>{@code &#RRGGBB} or {@code <#RRGGBB>} — true hex colour</li>
 *   <li>{@code <gradient:#RRGGBB:#RRGGBB>text</gradient>} — smooth colour gradient</li>
 *   <li>{@code <center>rest of line} — centre the remainder of the line in chat</li>
 * </ul>
 *
 * <h3>Processing order</h3>
 * <ol>
 *   <li>Detect {@code <center>} prefix and strip it.</li>
 *   <li>Expand {@code <gradient>} tags, writing per-character hex codes.</li>
 *   <li>Translate {@code &#RRGGBB} / {@code <#RRGGBB>} to § hex sequences.</li>
 *   <li>Translate {@code &x} legacy codes.</li>
 *   <li>If centring, compute visible pixel width and prepend the right number of spaces.</li>
 * </ol>
 *
 * <h3>Centering math</h3>
 * Minecraft's default chat viewport is 320 pixels wide. Each character
 * contributes a known pixel width (bold adds +1 per char). We sum widths
 * of the visible (non-code) characters, then prepend
 * {@code floor((320 - textWidth) / 2 / SPACE_WIDTH)} regular spaces.
 * A space is 4 px wide (regular), 5 px bold — we always use regular spaces
 * for the padding because bold spaces look identical to regular spaces in chat.
 */
public final class MessageFormatter {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Total pixel width of the default Minecraft chat box. */
    private static final int CHAT_WIDTH_PX = 320;

    /** Width of one regular space character in pixels. */
    private static final int SPACE_WIDTH_PX = 4;

    // Regex patterns
    private static final Pattern GRADIENT_PATTERN = Pattern.compile(
            "<gradient:(#[0-9A-Fa-f]{6}):(#[0-9A-Fa-f]{6})>(.*?)</gradient>",
            Pattern.DOTALL
    );
    private static final Pattern HEX_AMPERSAND = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final Pattern HEX_ANGLE     = Pattern.compile("<#([0-9A-Fa-f]{6})>");

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    private MessageFormatter() {}

    /**
     * Processes a raw config string into a final, ready-to-broadcast chat line.
     *
     * @param raw the raw string from config (may contain {@code <center>},
     *            gradient tags, hex codes, and {@code &} codes)
     * @return the processed string with § codes
     */
    public static @NotNull String process(final @NotNull String raw) {
        boolean centre = false;
        String s = raw;

        // 1. Detect and strip <center> tag (case-insensitive, must be at the start)
        if (s.regionMatches(true, 0, "<center>", 0, 8)) {
            centre = true;
            s = s.substring(8);
        }

        // 2. Expand gradient tags
        s = applyGradients(s);

        // 3. Translate &#RRGGBB → §x§R§R§G§G§B§B
        s = applyHexColours(s);

        // 4. Translate &x legacy codes
        s = translateLegacy(s);

        // 5. Centre if requested
        if (centre) {
            s = centre(s);
        }

        return s;
    }

    // -----------------------------------------------------------------------
    // Step implementations
    // -----------------------------------------------------------------------

    /**
     * Expands {@code <gradient:#RRGGBB:#RRGGBB>text</gradient>} tags.
     * Each visible character in {@code text} receives its own interpolated
     * hex colour code. Existing § codes inside the text are preserved.
     */
    static String applyGradients(final String input) {
        final Matcher m = GRADIENT_PATTERN.matcher(input);
        final StringBuffer sb = new StringBuffer();

        while (m.find()) {
            final int[]  from  = hexToRgb(m.group(1));
            final int[]  to    = hexToRgb(m.group(2));
            final String text  = m.group(3);

            // Count only visible (non-§-code) characters for interpolation steps
            final String stripped = stripCodes(text);
            final int    len      = Math.max(stripped.length(), 1);

            final StringBuilder coloured = new StringBuilder();
            int visIdx = 0;

            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);

                if (c == '\u00a7' && i + 1 < text.length()) {
                    // Pass through existing § codes unchanged
                    coloured.append(c).append(text.charAt(i + 1));
                    i++;
                } else {
                    // Interpolate colour for this visible character
                    final float t   = len == 1 ? 0f : (float) visIdx / (len - 1);
                    final int   r   = lerp(from[0], to[0], t);
                    final int   g   = lerp(from[1], to[1], t);
                    final int   b   = lerp(from[2], to[2], t);
                    coloured.append(toHexCode(r, g, b)).append(c);
                    visIdx++;
                }
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(coloured.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Translates {@code &#RRGGBB} and {@code <#RRGGBB>} to the
     * {@code §x§R§R§G§G§B§B} format recognised by Paper/Spigot 1.16+.
     */
    static String applyHexColours(final String input) {
        // Handle &#RRGGBB
        String s = HEX_AMPERSAND.matcher(input).replaceAll(mr ->
                toHexCode(mr.group(1)));
        // Handle <#RRGGBB>
        s = HEX_ANGLE.matcher(s).replaceAll(mr ->
                toHexCode(mr.group(1)));
        return s;
    }

    /**
     * Translates {@code &x} codes to § codes, honouring the full legacy palette
     * ({@code 0-9}, {@code a-f}, {@code k-o}, {@code r}).
     */
    static String translateLegacy(final String input) {
        if (input == null) return "";
        final StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);
            if (c == '&' && i + 1 < input.length()) {
                final char next = input.charAt(i + 1);
                if (isLegacyCode(next)) {
                    sb.append('\u00a7').append(next);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Centres {@code line} (which must already have § codes applied) in the
     * Minecraft default chat window by prepending the correct number of spaces.
     *
     * <p>The algorithm:
     * <ol>
     *   <li>Walk the string, skipping {@code §x} code pairs.</li>
     *   <li>Track whether bold ({@code §l}) is active; bold adds 1 px per char.</li>
     *   <li>Sum pixel widths of all visible characters.</li>
     *   <li>Prepend {@code floor((CHAT_WIDTH - textWidth) / 2 / SPACE_WIDTH)} spaces.</li>
     * </ol>
     */
    static String centre(final String line) {
        final int textWidth  = visiblePixelWidth(line);
        final int spaceCount = Math.max(0, (CHAT_WIDTH_PX - textWidth) / 2 / SPACE_WIDTH_PX);
        return " ".repeat(spaceCount) + line;
    }

    // -----------------------------------------------------------------------
    // Pixel-width measurement
    // -----------------------------------------------------------------------

    /**
     * Computes the pixel width of the visible (non-code) characters in a
     * § -coded string, accounting for bold.
     */
    static int visiblePixelWidth(final String coded) {
        int  total = 0;
        boolean bold = false;

        for (int i = 0; i < coded.length(); i++) {
            final char c = coded.charAt(i);
            if (c == '\u00a7' && i + 1 < coded.length()) {
                final char code = Character.toLowerCase(coded.charAt(i + 1));
                if (code == 'l') bold = true;
                // reset bold on colour codes and §r
                else if (code == 'r' || (code >= '0' && code <= '9')
                        || (code >= 'a' && code <= 'f')) {
                    bold = false;
                }
                i++; // skip the code char
            } else {
                // §x hex codes produced by toHexCode() are 14 chars: §x§r§r§g§g§b§b
                // They are already in the string as raw §-chars; handle them as a unit.
                // Actually toHexCode emits 14 chars of §-codes with no visible char —
                // the visible char immediately follows the code. So treat §-pairs normally.
                total += charWidth(c, bold);
            }
        }
        return total;
    }

    /**
     * Returns the pixel width of a single Minecraft character.
     * Bold adds 1 px. Values are based on the default resource pack font.
     */
    static int charWidth(final char c, final boolean bold) {
        final int base;
        if (c == ' ')                                           base = 4;
        else if ("!(),.:;`i|".indexOf(c) >= 0)                base = 2;  // 1px + 1 gap
        else if ("\"*ftl{}".indexOf(c) >= 0)                   base = 4;
        else if ("@~".indexOf(c) >= 0)                         base = 7;
        else if ("[\\]".indexOf(c) >= 0)                       base = 4;
        else if (c >= 'A' && c <= 'Z') {
            // Most uppercase are 6px; some are wider
            if ("IFJM".indexOf(c) >= 0)       base = c == 'M' ? 7 : (c == 'I' ? 4 : 5);
            else                               base = 6;
        }
        else if (c >= '0' && c <= '9')                        base = 6;
        else                                                   base = 6; // most lowercase + misc
        return bold ? base + 1 : base;
    }

    // -----------------------------------------------------------------------
    // Hex helpers
    // -----------------------------------------------------------------------

    /** Converts a 6-char hex string (no #) to a §x§R§R§G§G§B§B sequence. */
    private static String toHexCode(final String hex) {
        // §x + §R §R §G §G §B §B  (each nibble as its own §-pair)
        return "\u00a7x"
                + "\u00a7" + hex.charAt(0)
                + "\u00a7" + hex.charAt(1)
                + "\u00a7" + hex.charAt(2)
                + "\u00a7" + hex.charAt(3)
                + "\u00a7" + hex.charAt(4)
                + "\u00a7" + hex.charAt(5);
    }

    /** Converts r, g, b (0-255) to a §x§R§R§G§G§B§B sequence. */
    private static String toHexCode(final int r, final int g, final int b) {
        return toHexCode(String.format("%02x%02x%02x", r, g, b));
    }

    private static int[] hexToRgb(final String hex) {
        final int h = Integer.parseUnsignedInt(hex.substring(1), 16);
        return new int[]{(h >> 16) & 0xFF, (h >> 8) & 0xFF, h & 0xFF};
    }

    private static int lerp(final int a, final int b, final float t) {
        return Math.round(a + (b - a) * t);
    }

    // -----------------------------------------------------------------------
    // Code helpers
    // -----------------------------------------------------------------------

    /** Strips all §x code pairs from a string, returning only visible chars. */
    static String stripCodes(final String s) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\u00a7' && i + 1 < s.length()) {
                i++; // skip code char
            } else {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    private static boolean isLegacyCode(final char c) {
        return "0123456789abcdefABCDEFklmnorKLMNOR".indexOf(c) >= 0;
    }
}
