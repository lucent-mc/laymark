package cx.mia.lucent.laymark.runner.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.runner.RunControl;
import java.awt.event.ComponentEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Walks the window across both collapse thresholds — width for the roster, height for the log —
 * and holds the shell to its invariant at every size: each collapsible lives in exactly one home,
 * laid out at real extent. Split mode means inside its split pane; collapsed means parked in its
 * drawer while the results fill the freed axis.
 *
 * <p>The window gets a peer ({@code addNotify}) but is never shown. Each resize is dispatched as
 * its own EDT turn and the assertion runs turns later, so the RepaintManager's <em>asynchronous</em>
 * validation interleaves exactly as it does under a native interactive resize — the timing that a
 * synchronous validate-after-every-step walk cannot reproduce, and the timing under which layout
 * bugs actually happen.
 */
class ResponsiveLayoutTest {

    private JFrame frame;

    @AfterEach
    void dispose() throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test
    void everySizeLandsInExactlyOneModePerAxis() throws Exception {
        var window = new Object() {
            PlanningView planning;
            JSplitPane body;
            JSplitPane resultsSplit;
            JPanel run;
            JPanel logPanel;
            JPanel centerHost;
            JPanel rosterDrawerPanel;
            JPanel logDrawerPanel;
        };
        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        Theme.install();
                        Constructor<RunnerWindow> ctor =
                                RunnerWindow.class.getDeclaredConstructor(
                                        RunControl.class, RunnerWindow.Launcher.class);
                        ctor.setAccessible(true);
                        RunnerWindow runner = ctor.newInstance(new RunControl(), null);
                        window.planning = field(runner, "planning");
                        window.body = field(runner, "body");
                        window.resultsSplit = field(runner, "resultsSplit");
                        window.run = field(runner, "run");
                        window.logPanel = field(runner, "logPanel");
                        window.centerHost = field(runner, "centerHost");
                        window.rosterDrawerPanel = drawerPanel(runner, "rosterDrawer");
                        window.logDrawerPanel = drawerPanel(runner, "logDrawer");
                        frame = field(runner, "frame");
                        // A peer without a visible window: layout runs for real, nothing shows.
                        frame.addNotify();
                        frame.validate();
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });

        // Pixel-fine across each threshold in both directions, the jumpy strides of a fast
        // native drag, then direction flapping right on the boundary. One axis moves at a time;
        // the other holds a value on each side of its own threshold.
        List<int[]> walk = new ArrayList<>();
        for (int w = 1200; w >= 560; w--) {
            walk.add(new int[] {w, 900});
        }
        for (int w = 560; w <= 1200; w += 37) {
            walk.add(new int[] {w, 700});
        }
        for (int h = 1000; h >= 480; h--) {
            walk.add(new int[] {1200, h});
        }
        for (int h = 480; h <= 1000; h += 37) {
            walk.add(new int[] {820, h});
        }
        for (int i = 0; i < 12; i++) {
            walk.add(new int[] {i % 2 == 0 ? 896 : 903, i % 2 == 0 ? 756 : 763});
        }

        List<String> violations = new ArrayList<>();
        for (int[] size : walk) {
            SwingUtilities.invokeAndWait(
                    () -> {
                        frame.setSize(size[0], size[1]);
                        frame.dispatchEvent(
                                new ComponentEvent(frame, ComponentEvent.COMPONENT_RESIZED));
                    });
            // Empty turns so the RepaintManager's queued validation runs before we look.
            SwingUtilities.invokeAndWait(() -> {});
            SwingUtilities.invokeAndWait(() -> {});
            SwingUtilities.invokeAndWait(
                    () -> {
                        String at = size[0] + "x" + size[1] + ": ";

                        boolean planningInSplit = window.body.getLeftComponent() == window.planning;
                        boolean runInSplit = window.body.getRightComponent() == window.run;
                        boolean bodyHosted = window.body.getParent() == window.centerHost;
                        boolean runHosted = window.run.getParent() == window.centerHost;
                        boolean planningInDrawer =
                                window.planning.getParent() == window.rosterDrawerPanel;
                        boolean rosterSplit = planningInSplit && runInSplit && bodyHosted;
                        boolean rosterCollapsed = runHosted && planningInDrawer && !bodyHosted;
                        if (rosterSplit == rosterCollapsed) {
                            violations.add(
                                    at + "roster in no single home: planningInSplit="
                                            + planningInSplit + " runInSplit=" + runInSplit
                                            + " bodyHosted=" + bodyHosted + " runHosted="
                                            + runHosted + " planningInDrawer=" + planningInDrawer);
                        } else if (rosterSplit
                                && (window.planning.getWidth() < 50
                                        || window.run.getWidth() < 50)) {
                            violations.add(
                                    at + "roster split laid out at planning="
                                            + window.planning.getWidth() + "w run="
                                            + window.run.getWidth() + "w");
                        }

                        boolean logInSplit =
                                window.resultsSplit.getBottomComponent() == window.logPanel;
                        boolean logInDrawer =
                                window.logPanel.getParent() == window.logDrawerPanel;
                        if (logInSplit == logInDrawer) {
                            violations.add(
                                    at + "log in no single home: inSplit=" + logInSplit
                                            + " inDrawer=" + logInDrawer);
                        } else if (logInSplit && window.logPanel.getHeight() < 50) {
                            violations.add(
                                    at + "log split laid out at " + window.logPanel.getHeight()
                                            + "h");
                        }
                    });
        }
        assertTrue(
                violations.isEmpty(),
                () ->
                        violations.size()
                                + " sizes broke the shell:\n"
                                + String.join(
                                        "\n",
                                        violations.subList(0, Math.min(20, violations.size()))));
    }

    private static JPanel drawerPanel(RunnerWindow runner, String name)
            throws ReflectiveOperationException {
        Object drawer = field(runner, name);
        Field panel = drawer.getClass().getDeclaredField("panel");
        panel.setAccessible(true);
        return (JPanel) panel.get(drawer);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(RunnerWindow window, String name) throws ReflectiveOperationException {
        Field field = RunnerWindow.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(window);
    }
}
