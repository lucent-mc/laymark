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
 * Walks the window width across the collapse threshold, both directions, and holds the shell to
 * its invariant at every width: split mode has the roster and results laid out inside the split
 * pane at real sizes, drawer mode has the results filling the window and the roster parked in the
 * drawer.
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
    void everyWidthLandsInExactlyOneMode() throws Exception {
        var window = new Object() {
            RunnerWindow runner;
            PlanningView planning;
            JSplitPane body;
            JPanel run;
            JPanel centerHost;
            JPanel drawer;
        };
        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        Theme.install();
                        Constructor<RunnerWindow> ctor =
                                RunnerWindow.class.getDeclaredConstructor(
                                        RunControl.class, RunnerWindow.Launcher.class);
                        ctor.setAccessible(true);
                        window.runner = ctor.newInstance(new RunControl(), null);
                        window.planning = field(window.runner, "planning");
                        window.body = field(window.runner, "body");
                        window.run = field(window.runner, "run");
                        window.centerHost = field(window.runner, "centerHost");
                        window.drawer = field(window.runner, "drawer");
                        frame = field(window.runner, "frame");
                        // A peer without a visible window: layout runs for real, nothing shows.
                        frame.addNotify();
                        frame.validate();
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });

        List<Integer> walk = new ArrayList<>();
        // Pixel-fine around the threshold in both directions, then the jumpy strides of a fast
        // native drag, then direction flapping right on the boundary.
        for (int w = 1200; w >= 560; w--) {
            walk.add(w);
        }
        for (int w = 560; w <= 1200; w++) {
            walk.add(w);
        }
        for (int w = 1200; w >= 560; w -= 37) {
            walk.add(w);
        }
        for (int w = 560; w <= 1200; w += 37) {
            walk.add(w);
        }
        for (int i = 0; i < 12; i++) {
            walk.add(i % 2 == 0 ? 896 : 903);
        }

        List<String> violations = new ArrayList<>();
        for (int width : walk) {
            SwingUtilities.invokeAndWait(
                    () -> {
                        frame.setSize(width, 900);
                        frame.dispatchEvent(
                                new ComponentEvent(frame, ComponentEvent.COMPONENT_RESIZED));
                    });
            // Empty turns so the RepaintManager's queued validation runs before we look.
            SwingUtilities.invokeAndWait(() -> {});
            SwingUtilities.invokeAndWait(() -> {});
            SwingUtilities.invokeAndWait(
                    () -> {
                        boolean planningInSplit = window.body.getLeftComponent() == window.planning;
                        boolean runInSplit = window.body.getRightComponent() == window.run;
                        boolean bodyHosted = window.body.getParent() == window.centerHost;
                        boolean runHosted = window.run.getParent() == window.centerHost;
                        boolean planningInDrawer = window.planning.getParent() == window.drawer;

                        boolean split = planningInSplit && runInSplit && bodyHosted;
                        boolean collapsed = runHosted && planningInDrawer && !bodyHosted;
                        if (split == collapsed) {
                            violations.add(
                                    width
                                            + "px: planningInSplit=" + planningInSplit
                                            + " runInSplit=" + runInSplit
                                            + " bodyHosted=" + bodyHosted
                                            + " runHosted=" + runHosted
                                            + " planningInDrawer=" + planningInDrawer);
                        } else if (split) {
                            // Laid-out reality, not just attachment: both sides need real extent,
                            // or the operator sees a divider beside a void.
                            if (window.planning.getWidth() < 50 || window.planning.getHeight() < 50) {
                                violations.add(
                                        width + "px: split mode but roster laid out at "
                                                + window.planning.getWidth() + "x"
                                                + window.planning.getHeight());
                            }
                            if (window.run.getWidth() < 50 || window.run.getHeight() < 50) {
                                violations.add(
                                        width + "px: split mode but results laid out at "
                                                + window.run.getWidth() + "x"
                                                + window.run.getHeight());
                            }
                        } else {
                            if (window.run.getWidth() < window.centerHost.getWidth() - 1
                                    || window.run.getHeight() < 50) {
                                violations.add(
                                        width + "px: drawer mode but results laid out at "
                                                + window.run.getWidth() + "x"
                                                + window.run.getHeight() + " in a "
                                                + window.centerHost.getWidth() + "px host");
                            }
                        }
                    });
        }
        assertTrue(
                violations.isEmpty(),
                () ->
                        violations.size()
                                + " widths broke the shell:\n"
                                + String.join("\n", violations.subList(0, Math.min(20, violations.size()))));
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(RunnerWindow window, String name) throws ReflectiveOperationException {
        Field field = RunnerWindow.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(window);
    }
}
