package cx.mia.lucent.laymark.core.materialize;

/**
 * One filesystem move needed to put the instance into a wanted state.
 *
 * <p>Every one is a <strong>rename</strong>, which is what makes this loader-agnostic. NeoForge's
 * {@code ModsFolderLocator} filters on {@code endsWith(".jar")} and Fabric's directory scan does
 * the same, so a {@code .jar.disabled} file is invisible to both without either being told
 * anything. There is no loader-specific code here and none is needed on Fabric either.
 *
 * <p>Computed as data rather than performed inline so the decision — what should move where — is
 * testable without a filesystem, and so a caller can show an operator the plan before it runs.
 */
public sealed interface FileOperation {

    /** The mod this operation is about. */
    String fileName();

    /** {@code foo.jar.disabled} → {@code foo.jar}, inside {@code mods/}. */
    record Enable(String fileName) implements FileOperation {}

    /** {@code foo.jar} → {@code foo.jar.disabled}, inside {@code mods/}. */
    record Disable(String fileName) implements FileOperation {}

    /** {@code mods/foo.jar} → {@code laymark/withheld/foo.jar}. */
    record Withhold(String fileName) implements FileOperation {}

    /** {@code laymark/withheld/foo.jar} → {@code mods/foo.jar}, as the run ends. */
    record Restore(String fileName) implements FileOperation {}
}
