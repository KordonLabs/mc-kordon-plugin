package com.kordonlabs.plugin.common;

import com.kordonlabs.plugin.common.install.PanelUrls;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * Build information about the plugin: its version (from {@code kordon/version.properties}, filled in by the build),
 * the built-in Kordon API address, the {@code User-Agent} and the feature names sent in every heartbeat.
 */
public final class KordonInfo {

    public static final String VERSION_RESOURCE = "kordon/version.properties";

    /** Feature names this build reports in the heartbeat's {@code features} field. */
    public static final List<String> FEATURES = List.of("sessions", "kick", "lists", "lookup", "brand", "locale", "notices",
            "noticeLocales");

    /**
     * JVM flag {@code -Dkordon.debug=true} (placed before {@code -jar} in the proxy's start command) enables the
     * {@code [debug]} console lines. Read once at start.
     */
    public static final String DEBUG_PROPERTY = "kordon.debug";

    /**
     * JVM flag for development and tests: {@code -Dkordon.panelUrl=<url>} overrides the Kordon API address embedded in
     * the jar and the built-in default. Read once at start and never stored.
     */
    public static final String PANEL_URL_PROPERTY = "kordon.panelUrl";

    private static final Properties BUILD = readBuild();
    private static final String VERSION = readVersion(BUILD);
    private static final String DEFAULT_API_URL = readApiUrl(BUILD);

    private KordonInfo() {
    }

    /** The plugin version, or "unknown" when the version resource is missing (a broken build). */
    public static String version() {
        return VERSION;
    }

    /**
     * The Kordon API address used by a jar without {@code kordon-install.json}. It comes from the Gradle property
     * {@code kordonApiUrl} (default {@code https://api.kordonlabs.com}; a fork can build with
     * {@code -PkordonApiUrl=https://...}). Null when the value is blank, unexpanded or not a valid address.
     */
    public static String defaultApiUrl() {
        return DEFAULT_API_URL;
    }

    public static int javaVersion() {
        return Runtime.version().feature();
    }

    /** For example {@code Kordon/1.1.0 (velocity; Java 21)}. */
    public static String userAgent(String platformId) {
        return "Kordon/" + VERSION + " (" + platformId + "; Java " + javaVersion() + ")";
    }

    public static boolean debugRequested(String value) {
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    private static Properties readBuild() {
        Properties p = new Properties();
        try (InputStream in = KordonInfo.class.getClassLoader().getResourceAsStream(VERSION_RESOURCE)) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            // A broken build: the version reads "unknown" and there is no default address.
        }
        return p;
    }

    private static String readVersion(Properties build) {
        String v = build.getProperty("version", "").trim();
        return v.isEmpty() || v.contains("${") ? "unknown" : v;
    }

    private static String readApiUrl(Properties build) {
        String v = build.getProperty("apiUrl", "").trim();
        return v.isEmpty() || v.contains("${") ? null : PanelUrls.normalize(v);
    }
}
