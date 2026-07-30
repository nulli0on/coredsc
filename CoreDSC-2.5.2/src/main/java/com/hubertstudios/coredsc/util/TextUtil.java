package com.hubertstudios.coredsc.util;

import org.bukkit.ChatColor;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared text safety and template helpers. */
public final class TextUtil {
    private static final Pattern EVERYONE = Pattern.compile("@everyone", Pattern.CASE_INSENSITIVE);
    private static final Pattern HERE = Pattern.compile("@here", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("%([a-zA-Z0-9_]+)%");
    private static final Pattern MINECRAFT_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private TextUtil() { }

    public static String replace(String template, Map<String, ?> values) {
        String output = template == null ? "" : template;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().toString();
            output = output.replace("%" + entry.getKey().toLowerCase(Locale.ROOT) + "%", value);
        }
        return output;
    }

    public static String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    public static String sanitizeMassMentions(String input) {
        String value = input == null ? "" : input;
        value = EVERYONE.matcher(value).replaceAll("@\u200Beveryone");
        return HERE.matcher(value).replaceAll("@\u200Bhere");
    }

    public static String sanitizeMinecraftUserText(String input) {
        return (input == null ? "" : input)
                .replace('&', '＆')
                .replace('§', '＃')
                .replace('\n', ' ')
                .replace('\r', ' ');
    }

    public static String singleLine(String input) {
        return (input == null ? "" : input).replace('\n', ' ').replace('\r', ' ').trim();
    }

    public static String truncate(String input, int maximum) {
        String value = input == null ? "" : input;
        if (maximum < 1 || value.length() <= maximum) {
            return value;
        }
        if (maximum <= 3) {
            return value.substring(0, maximum);
        }
        int end = maximum - 3;
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end) + "...";
    }

    public static long parsePositiveLong(Object input) {
        if (input == null) return 0L;
        try {
            long value = Long.parseLong(input.toString().trim());
            return Math.max(0L, value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    public static boolean isPositiveSnowflake(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        try {
            return Long.parseLong(input.trim()) > 0L;
        } catch (NumberFormatException exception) {
            return false;
        }
    }


    /**
     * Renders a command template using only explicitly supplied, prevalidated
     * values. Any unknown placeholder is rejected instead of being forwarded
     * to Bukkit or another plugin.
     */
    public static String renderRestrictedCommand(
            String template,
            Map<String, String> safeValues
    ) {
        String raw = singleLine(template);
        java.util.regex.Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(raw);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            if (!safeValues.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Unsafe or unavailable command placeholder: %" + matcher.group(1) + "%");
            }
            String value = safeValues.get(key);
            matcher.appendReplacement(rendered, java.util.regex.Matcher.quoteReplacement(
                    value == null ? "" : value));
        }
        matcher.appendTail(rendered);
        String command = rendered.toString().trim();
        if (command.isBlank()) {
            throw new IllegalArgumentException("Rendered console command is blank");
        }
        if (command.indexOf('%') >= 0 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Unresolved or multiline console command blocked");
        }
        return command;
    }

    public static boolean isSafeMinecraftName(String value) {
        return value != null && MINECRAFT_NAME.matcher(value).matches();
    }

    public static boolean isSafeIdentifier(String value) {
        return value != null && SAFE_IDENTIFIER.matcher(value).matches();
    }

    public static boolean isUuid(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static String safeChannelToken(String input) {
        String normalized = singleLine(input).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return normalized.isBlank() ? "user" : normalized;
    }
}
