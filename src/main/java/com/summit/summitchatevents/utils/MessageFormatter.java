package com.summit.summitchatevents.utils;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two-phase message processing pipeline.
 *
 * <h3>Phase 1 — at config load ({@link #translateCodes})</h3>
 * Only translates {@code &x} legacy codes to § equivalents.
 * Placeholders ({@code %player%} etc.) and formatting tags
 * ({@code <gradient>}, {@code <center>}) are left untouched so they survive
 * until runtime values are known.
 *
 * <h3>Phase 2 — at broadcast time ({@link #process})</h3>
 * Called after all {@code %placeholder%} values have been substituted into
 * the string. Runs in this order:
 * <ol>
 *   <li>Detect and strip {@code <center>}.</li>
 *   <li>Translate {@code &x} codes still present (e.g. from runtime values).</li>
 *   <li>Expand {@code <gradient:#RRGGBB:#RRGGBB>text</gradient>} tags,
 *       applying per-character hex colours.</li>
 *   <li>Translate {@code &#RRGGBB} / {@code <#RRGGBB>} hex shortcuts.</li>
 *   <li>If centring, compute pixel width and prepend spaces.</li>
 * </ol>
 *
 * <h3>Centering math</h3>
 * Chat box = 320 px. Each character has a known pixel width; bold adds 1 px.
 * Padding = {@code floor((320 - textWidth) / 2 / 4)} regular spaces (4 px each).
 */
public final class MessageFormatter {

    private static final int CHAT_WIDTH_PX  = 320;
    private static final int SPACE_WIDTH_PX = 4;

    private static final Pattern GRADIENT_PATTERN = Pattern.compile(
            "<gradient:(#[0-9A-Fa-f]{6}):(#[0-9A-Fa-f]{6})>(.*?)</gradient>",
            Pattern.DOTALL
    );
    private static final Pattern HEX_AMPERSAND = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final Pattern HEX_ANGLE     = Pattern.compile("<#([0-9A-Fa-f]{6})>");

    private MessageFormatter() {}

    // -----------------------------------------------------------------------
    // Phase 1 — config load
    // -----------------------------------------------------------------------

    /**
     * Translates {@code &x} codes to § equivalents.
     * Does NOT expand gradients or apply centering — those require
     * runtime values to already be substituted.
     */
    public static @NotNull String translateCodes(final @NotNull String raw) {
        return translateLegacy(raw);
    }

    // -----------------------------------------------------------------------
    // Phase 2 — broadcast time (after placeholder substitution)
    // -----------------------------------------------------------------------

    /**
     * Full processing: center detection → legacy codes → gradients → hex → centering.
     * Call this on strings that already have all {@code %placeholder%} values filled in.
     */
    public static @NotNull String process(final @NotNull String raw) {
        boolean centre = false;
        String s = raw;

        // 1. Detect <center>
        if (s.regionMatches(true, 0, "<center>", 0, 8)) {
            centre = true;
            s = s.substring(8);
        }

        // 2. Translate any remaining &x codes (including those from runtime values)
        s = translateLegacy(s);

        // 3. Expand gradients (now that §l etc. are real § codes)
        s = applyGradients(s);

        // 4. Translate hex shortcuts
        s = applyHexColours(s);

        // 5. Centre
        if (centre) {
            s = centre(s);
        }

        return s;
    }

    // -----------------------------------------------------------------------
    // Gradient expansion
    // -----------------------------------------------------------------------

    static String applyGradients(final String input) {
        final Matcher m = GRADIENT_PATTERN.matcher(input);
        final StringBuffer sb = new StringBuffer();

        while (m.find()) {
            final int[]  from = hexToRgb(m.group(1));
            final int[]  to   = hexToRgb(m.group(2));
            final String text = m.group(3);

            // Count visible chars for interpolation (skip § code pairs and their letter)
            final String stripped = stripCodes(text);
            final int    len      = Math.max(stripped.length(), 1);

            final StringBuilder coloured = new StringBuilder();
            // Track active format codes (bold, italic etc.) to re-apply after each hex code
            String activeFormats = "";
            int visIdx = 0;

            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);

                if (c == '\u00a7' && i + 1 < text.length()) {
                    final char code = text.charAt(i + 1);
                    // Accumulate format codes; clear on colour reset
                    if (code == 'r') {
                        activeFormats = "";
                    } else if ("klmno".indexOf(Character.toLowerCase(code)) >= 0) {
                        activeFormats += "\u00a7" + code;
                    }
                    coloured.append('\u00a7').append(code);
                    i++;
                } else {
                    final float t = len == 1 ? 0f : (float) visIdx / (len - 1);
                    final int r = lerp(from[0], to[0], t);
                    final int g = lerp(from[1], to[1], t);
                    final int b = lerp(from[2], to[2], t);
                    // Emit hex colour, re-apply any active format codes (e.g. §l for bold)
                    coloured.append(toHexCode(r, g, b)).append(activeFormats).append(c);
                    visIdx++;
                }
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(coloured.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Hex colour translation
    // -----------------------------------------------------------------------

    static String applyHexColours(final String input) {
        String s = HEX_AMPERSAND.matcher(input).replaceAll(mr -> toHexCode(mr.group(1)));
        s = HEX_ANGLE.matcher(s).replaceAll(mr -> toHexCode(mr.group(1)));
        return s;
    }

    // -----------------------------------------------------------------------
    // Legacy code translation
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // Centering
    // -----------------------------------------------------------------------

    static String centre(final String line) {
        final int textWidth  = visiblePixelWidth(line);
        final int spaceCount = Math.max(0, (CHAT_WIDTH_PX - textWidth) / 2 / SPACE_WIDTH_PX);
        return " ".repeat(spaceCount) + line;
    }

    static int visiblePixelWidth(final String coded) {
        int total = 0;
        boolean bold = false;
        for (int i = 0; i < coded.length(); i++) {
            final char c = coded.charAt(i);
            if (c == '\u00a7' && i + 1 < coded.length()) {
                final char code = Character.toLowerCase(coded.charAt(i + 1));
                if (code == 'l') bold = true;
                else if (code == 'r' || (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) bold = false;
                i++;
            } else {
                total += charWidth(c, bold);
            }
        }
        return total;
    }

    static int charWidth(final char c, final boolean bold) {
        final int base;
        if (c == ' ')                             base = 4;
        else if ("!,.:;`|".indexOf(c) >= 0)      base = 2;
        else if ("\"*()\u2502".indexOf(c) >= 0)  base = 4;
        else if ("ft".indexOf(c) >= 0)            base = 4;
        else if ("il".indexOf(c) >= 0)            base = 2;
        else if ("@~".indexOf(c) >= 0)            base = 7;
        else if (c == 'M' || c == 'W')            base = 7;
        else if (c == 'm' || c == 'w')            base = 7;
        else if (c == 'I')                        base = 4;
        else if (c >= 'A' && c <= 'Z')            base = 6;
        else if (c >= '0' && c <= '9')            base = 6;
        else if ("\u2500\u2501\u2550\u254c\u254d\u2574\u2578\u2581\u2582\u2583\u2584\u2585\u2586\u2587\u2588".indexOf(c) >= 0)
                                                  base = 9; // Unicode box/block chars
        else if ("\u2190\u2191\u2192\u2193\u2194".indexOf(c) >= 0) base = 7;
        else if ("\u2714\u2716\u2718".indexOf(c) >= 0) base = 7;
        else                                      base = 6;
        return bold ? base + 1 : base;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String toHexCode(final String hex) {
        return "\u00a7x"
                + "\u00a7" + hex.charAt(0) + "\u00a7" + hex.charAt(1)
                + "\u00a7" + hex.charAt(2) + "\u00a7" + hex.charAt(3)
                + "\u00a7" + hex.charAt(4) + "\u00a7" + hex.charAt(5);
    }

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

    static String stripCodes(final String s) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\u00a7' && i + 1 < s.length()) i++;
            else sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    private static boolean isLegacyCode(final char c) {
        return "0123456789abcdefABCDEFklmnorKLMNOR".indexOf(c) >= 0;
    }
}
