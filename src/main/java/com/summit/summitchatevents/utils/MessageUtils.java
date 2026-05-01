package com.summit.summitchatevents.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * General-purpose text and message utilities.
 *
 * <p>Uses the Adventure / MiniMessage API that ships with Paper 1.18+.
 * Methods will grow alongside the plugin's feature set.
 */
public final class MessageUtils {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    // Utility class — no instances
    private MessageUtils() {}

    /**
     * Parse a MiniMessage string into an Adventure {@link Component}.
     *
     * <p>Example: {@code "<red>Hello <bold>world</bold>"}
     *
     * @param miniMessageText the raw MiniMessage string
     * @return the parsed component, ready to send with {@code Player#sendMessage}
     */
    public static Component parse(final String miniMessageText) {
        return MINI_MESSAGE.deserialize(miniMessageText);
    }

    /**
     * Strip all MiniMessage tags from a string, returning plain text.
     *
     * @param miniMessageText the raw MiniMessage string
     * @return a tag-free plain string
     */
    public static String stripTags(final String miniMessageText) {
        return MINI_MESSAGE.stripTags(miniMessageText);
    }
}
