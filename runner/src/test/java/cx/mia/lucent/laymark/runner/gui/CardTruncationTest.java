package cx.mia.lucent.laymark.runner.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.runner.ExperimentListener;
import cx.mia.lucent.laymark.runner.RunControl;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A mod whose name is wider than the column must be truncated by the card, not allowed to widen
 * it.
 *
 * <p>An overflowing card took the stat columns with it: the run column sized itself to the widest
 * card, the extra width fell outside the window, and the numbers went with it. The card is laid
 * out at a realistic column width here and every descendant has to stay inside its parent.
 */
class CardTruncationTest {

    private static final String LONG_NAME =
            "an-absurdly-long-mod-file-name-that-nobody-would-ever-actually-choose-1.2.3.jar";

    private static final int COLUMN_WIDTH = 280;

    @Test
    void aLongNameStaysInsideTheCard() throws Exception {
        // Font metrics decide where the text stops, and a headless runner has none.
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");

        List<String> violations = new ArrayList<>();
        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        Theme.install();
                        Constructor<RunnerWindow> ctor =
                                RunnerWindow.class.getDeclaredConstructor(
                                        RunControl.class, RunnerWindow.Launcher.class);
                        ctor.setAccessible(true);
                        RunnerWindow runner = ctor.newInstance(new RunControl(), null);

                        Map<String, ExperimentListener.PreliminaryScenario> scenarios =
                                new LinkedHashMap<>();
                        scenarios.put(
                                "chunk-generation",
                                new ExperimentListener.PreliminaryScenario(
                                        6.0, 0.5, 3.0, -2.5, 1, 2));
                        var preliminary =
                                new ExperimentListener.Preliminary(
                                        LONG_NAME, 7.5, 0.5, 3.0, scenarios);

                        Method build =
                                RunnerWindow.class.getDeclaredMethod(
                                        "preliminaryCard",
                                        int.class,
                                        ExperimentListener.Preliminary.class);
                        build.setAccessible(true);
                        JPanel card = (JPanel) build.invoke(runner, 1, preliminary);

                        // Expanded, because the sections carry the numbers that went missing.
                        setExpanded(card, true);
                        card.setBounds(0, 0, COLUMN_WIDTH, 600);
                        layoutTree(card);
                        assertContained(card, violations);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });

        assertTrue(
                violations.isEmpty(),
                () ->
                        "a "
                                + COLUMN_WIDTH
                                + "px card overflowed:\n"
                                + String.join("\n", violations));
    }

    /** Opens every collapsible in the card, so the detail rows are laid out too. */
    private static void setExpanded(Container parent, boolean expanded) {
        for (Component child : parent.getComponents()) {
            if (child instanceof RunnerWindow.ExpandingPanel panel) {
                panel.setVisible(expanded);
            }
            if (child instanceof Container container) {
                setExpanded(container, expanded);
            }
        }
    }

    /** Lays out the tree without a peer: doLayout runs the layout manager either way. */
    private static void layoutTree(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) {
            if (child instanceof Container container) {
                layoutTree(container);
            }
        }
    }

    private static void assertContained(Container parent, List<String> violations) {
        for (Component child : parent.getComponents()) {
            if (!child.isVisible() || child.getWidth() <= 0 || child.getHeight() <= 0) {
                continue;
            }
            if (child.getX() < 0 || child.getX() + child.getWidth() > parent.getWidth()) {
                violations.add(
                        describe(child)
                                + " spans "
                                + child.getX()
                                + ".."
                                + (child.getX() + child.getWidth())
                                + " inside a "
                                + parent.getWidth()
                                + "px "
                                + parent.getClass().getSimpleName());
            }
            if (child instanceof Container container) {
                assertContained(container, violations);
            }
        }
    }

    private static String describe(Component component) {
        String text =
                component instanceof javax.swing.JLabel label && label.getText() != null
                        ? " \"" + label.getText() + "\""
                        : "";
        return component.getClass().getSimpleName() + text;
    }
}
