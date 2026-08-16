package cx.mia.lucent.laymark.runner.launch;

/**
 * Where a launcher keeps the pieces of an instance.
 *
 * <p>Plain strings rather than {@code Path}, so assembly stays a pure function that can be
 * tested for any platform from any platform. Nothing here touches the filesystem.
 *
 * @param gameDirectory the instance root; the working directory for the launch
 * @param librariesDirectory root the descriptor's library paths are resolved against
 * @param nativesDirectory extracted natives for this exact version
 * @param assetsDirectory shared asset store
 * @param versionJar the unmodified client jar
 */
public record InstanceLayout(
        String gameDirectory,
        String librariesDirectory,
        String nativesDirectory,
        String assetsDirectory,
        String versionJar) {

    public InstanceLayout {
        require(gameDirectory, "gameDirectory");
        require(librariesDirectory, "librariesDirectory");
        require(nativesDirectory, "nativesDirectory");
        require(assetsDirectory, "assetsDirectory");
        require(versionJar, "versionJar");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new LaunchException(name + " must not be blank");
        }
    }
}
