package com.kordonlabs.plugin.common.util;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugin version comparison for the update notice: {@code 1.2.3}, {@code 1.2}, {@code v1.2.3-beta.2},
 * build metadata ({@code +abc}) ignored, pre-releases ordered like SemVer 2.0 ({@code 1.0.0-rc.1 < 1.0.0}). Anything
 * unparsable is never "older", so a strange version string can never produce a false update notice.
 */
public final class SemVer implements Comparable<SemVer> {

    private static final Pattern FORMAT = Pattern.compile(
            "v?(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.(\\d{1,9}))?(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]*)?");

    private final int major;
    private final int minor;
    private final int patch;
    private final List<String> preRelease;

    private SemVer(int major, int minor, int patch, List<String> preRelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
    }

    public static Optional<SemVer> parse(String version) {
        if (version == null) return Optional.empty();
        Matcher m = FORMAT.matcher(version.trim());
        if (!m.matches()) return Optional.empty();
        List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
        return Optional.of(new SemVer(num(m.group(1)), num(m.group(2)), num(m.group(3)), pre));
    }

    /** True only when both versions parse and {@code current} is lower than {@code latest}. */
    public static boolean isOlder(String current, String latest) {
        Optional<SemVer> c = parse(current);
        Optional<SemVer> l = parse(latest);
        return c.isPresent() && l.isPresent() && c.get().compareTo(l.get()) < 0;
    }

    @Override
    public int compareTo(SemVer other) {
        int c = Integer.compare(major, other.major);
        if (c == 0) c = Integer.compare(minor, other.minor);
        if (c == 0) c = Integer.compare(patch, other.patch);
        if (c != 0) return c;
        // A version without a pre-release is higher than the same version with one.
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return Boolean.compare(preRelease.isEmpty(), other.preRelease.isEmpty());
        }
        for (int i = 0; i < Math.min(preRelease.size(), other.preRelease.size()); i++) {
            c = compareIdentifier(preRelease.get(i), other.preRelease.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(preRelease.size(), other.preRelease.size());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SemVer v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        // Pre-release identifiers compare numerically ("01" == "1"), so they stay out of the hash.
        return (major * 31 + minor) * 31 + patch;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease.isEmpty() ? "" : "-" + String.join(".", preRelease));
    }

    private static int compareIdentifier(String a, String b) {
        boolean aNum = a.chars().allMatch(Character::isDigit);
        boolean bNum = b.chars().allMatch(Character::isDigit);
        if (aNum && bNum) {
            // Compare as numbers without overflow: longer (without leading zeros) is larger.
            String x = a.replaceFirst("^0+(?=.)", "");
            String y = b.replaceFirst("^0+(?=.)", "");
            return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
        }
        if (aNum != bNum) return aNum ? -1 : 1; // numeric identifiers are lower than alphanumeric ones
        return a.compareTo(b);
    }

    private static int num(String group) {
        return group == null ? 0 : Integer.parseInt(group);
    }
}
