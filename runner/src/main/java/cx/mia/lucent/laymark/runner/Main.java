package cx.mia.lucent.laymark.runner;

import cx.mia.lucent.laymark.core.Laymark;

/**
 * Runner entry point.
 *
 * <p>No interactive CLI: invocation arguments are fine, but there is no TUI, no subcommand tree
 * and no prompting. Every ambiguity is resolved by config or fails the run, because a selection
 * run may last days unattended.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        System.out.printf(
                "laymark runner (protocol v%d) - scaffold only, no experiment implemented yet%n",
                Laymark.PROTOCOL_VERSION);
    }
}
