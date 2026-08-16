package cx.mia.lucent.laymark.runner.launch;

import java.util.Locale;

/**
 * The OS and architecture a launch descriptor's rules are evaluated against.
 *
 * <p>Passed explicitly rather than read from system properties inside the assembler, so rule
 * evaluation is testable for every platform from any platform. Platform is an experimental
 * stratum — results are never pooled across it — so getting this wrong is not a portability
 * inconvenience, it silently changes which libraries a benchmark loads.
 */
public record HostPlatform(String osName, String arch) {

    public HostPlatform {
        if (osName == null || osName.isBlank()) {
            throw new IllegalArgumentException("osName must not be blank");
        }
        if (arch == null || arch.isBlank()) {
            throw new IllegalArgumentException("arch must not be blank");
        }
    }

    public static HostPlatform current() {
        return new HostPlatform(
                normaliseOs(System.getProperty("os.name", "")),
                normaliseArch(System.getProperty("os.arch", "")));
    }

    public static HostPlatform windowsX64() {
        return new HostPlatform("windows", "x86_64");
    }

    public static HostPlatform linuxX64() {
        return new HostPlatform("linux", "x86_64");
    }

    public static HostPlatform macosArm64() {
        return new HostPlatform("osx", "aarch64");
    }

    /** The names Mojang's launch descriptors use, which are not the JVM's names. */
    private static String normaliseOs(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("win")) {
            return "windows";
        }
        if (lower.contains("mac") || lower.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

    private static String normaliseArch(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "amd64", "x86_64" -> "x86_64";
            case "x86", "i386", "i586", "i686" -> "x86";
            case "aarch64", "arm64" -> "aarch64";
            default -> lower;
        };
    }

    public String pathSeparator() {
        return "windows".equals(osName) ? ";" : ":";
    }
}
