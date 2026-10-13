package com.kordonlabs.plugin.common.install;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server key format, {@code ^kmc_[A-Za-z0-9_-]{20,124}$}, and its masking. Logs, replies and status show at most
 * the first {@link #PREFIX_LENGTH} characters of a key, and {@link #maskAll} cuts any key that would reach the console
 * anyway.
 */
public final class Tokens {

    /** {@code kmc_} plus 6 characters: the only part of a key that may appear in logs, replies and status. */
    public static final int PREFIX_LENGTH = 10;

    private static final Pattern TOKEN = Pattern.compile("kmc_[A-Za-z0-9_-]{20,124}");
    /** Anything that looks like a key, valid or not, since a mistyped key is a secret too. */
    private static final Pattern ANY_TOKEN = Pattern.compile("kmc_[A-Za-z0-9_-]{7,}");

    private Tokens() {
    }

    /** A well-formed key; false for null or a pasted placeholder. */
    public static boolean valid(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /** The first {@link #PREFIX_LENGTH} characters; null for null. */
    public static String prefix(String token) {
        if (token == null) return null;
        return token.substring(0, Math.min(PREFIX_LENGTH, token.length()));
    }

    /** Cuts every key-like {@code kmc_} sequence to its {@link #PREFIX_LENGTH}-char prefix followed by an ellipsis. */
    public static String maskAll(String text) {
        if (text == null || text.indexOf("kmc_") < 0) return text;
        Matcher m = ANY_TOKEN.matcher(text);
        StringBuilder out = null;
        while (m.find()) {
            if (out == null) out = new StringBuilder(text.length());
            m.appendReplacement(out, Matcher.quoteReplacement(prefix(m.group()) + "…"));
        }
        if (out == null) return text;
        m.appendTail(out);
        return out.toString();
    }
}
