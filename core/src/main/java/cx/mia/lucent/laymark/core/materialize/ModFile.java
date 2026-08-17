package cx.mia.lucent.laymark.core.materialize;

import cx.mia.lucent.laymark.core.harness.HarnessException;

/**
 * One mod jar, identified by name and content.
 *
 * <p>Both, because either alone is a way to run the wrong stack and not know. A name alone cannot
 * tell that a jar was replaced between arms; a hash alone cannot tell the loader which file to
 * load. The pair is what makes "the mods folder is what the arm asked for" a checkable claim.
 *
 * @param fileName as it appears in the directory, without any {@code .disabled} suffix
 * @param sha256 lowercase hex of the file's contents
 */
public record ModFile(String fileName, String sha256) {

    /** The suffix launchers already use to mean "present but not loaded". */
    public static final String DISABLED_SUFFIX = ".disabled";

    public ModFile {
        if (fileName == null || fileName.isBlank()) {
            throw new HarnessException("a mod file has no name");
        }
        if (fileName.endsWith(DISABLED_SUFFIX)) {
            // The suffix is a state, not part of the identity. Letting it into the name would make
            // the same jar two different mods depending on whether it happened to be on.
            throw new HarnessException(
                    "mod file name carries a " + DISABLED_SUFFIX + " suffix: " + fileName);
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new HarnessException("mod file " + fileName + " has no usable sha256");
        }
    }

    /** The name this file takes on disk when it is present but must not load. */
    public String disabledName() {
        return fileName + DISABLED_SUFFIX;
    }

    /** Strips the state suffix from an on-disk name, if it has one. */
    public static String stripDisabled(String onDisk) {
        return onDisk.endsWith(DISABLED_SUFFIX)
                ? onDisk.substring(0, onDisk.length() - DISABLED_SUFFIX.length())
                : onDisk;
    }

    public static boolean isDisabled(String onDisk) {
        return onDisk.endsWith(DISABLED_SUFFIX);
    }
}
