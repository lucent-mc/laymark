package cx.mia.lucent.laymark.runner.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cx.mia.lucent.laymark.runner.RunControl;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class RunnerLifecycleTest {

    private JFrame frame;

    @AfterEach
    void dispose() throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test
    void finishingReturnsTheReusableWindowToItsPlanningLayout() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");

        RunControl control = new RunControl();
        Rectangle planningBounds = new Rectangle(80, 60, 1200, 800);
        var window = new RunnerWindow[1];

        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        Theme.install();
                        Constructor<RunnerWindow> constructor =
                                RunnerWindow.class.getDeclaredConstructor(
                                        RunControl.class, RunnerWindow.Launcher.class);
                        constructor.setAccessible(true);
                        RunnerWindow.Launcher launcher = (choice, run, listener) -> {};
                        window[0] = constructor.newInstance(control, launcher);
                        frame = field(window[0], "frame");
                        frame.addNotify();
                        frame.setBounds(planningBounds);

                        Method enterRunView = RunnerWindow.class.getDeclaredMethod("enterRunView");
                        enterRunView.setAccessible(true);
                        enterRunView.invoke(window[0]);
                        control.stop();
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });

        window[0].finished(null);
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(planningBounds, frame.getBounds(), "the run-only dock must not strand planning");
        assertTrue(control.awaitClearance(), "Stop must not poison the next run");

        PlanningView planning = field(window[0], "planning");
        assertTrue(planning.isEnabled(), "planning is interactive again");
    }

    @Test
    void planningKeepsFinishedResultsUntilTheCandidateRosterChanges() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");

        RunControl control = new RunControl();
        var window = new RunnerWindow[1];
        var previousResult = new JLabel("previous result");

        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        Theme.install();
                        Constructor<RunnerWindow> constructor =
                                RunnerWindow.class.getDeclaredConstructor(
                                        RunControl.class, RunnerWindow.Launcher.class);
                        constructor.setAccessible(true);
                        RunnerWindow.Launcher launcher = (choice, run, listener) -> {};
                        window[0] = constructor.newInstance(control, launcher);
                        frame = field(window[0], "frame");
                        frame.addNotify();

                        Method enterRunView = RunnerWindow.class.getDeclaredMethod("enterRunView");
                        enterRunView.setAccessible(true);
                        enterRunView.invoke(window[0]);

                        JPanel columns = field(window[0], "columns");
                        columns.removeAll();
                        columns.add(previousResult);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });

        window[0].finished(null);
        SwingUtilities.invokeAndWait(() -> {});

        PlanningView planning = field(window[0], "planning");
        JTextField schedule = field(planning, "schedule");
        SwingUtilities.invokeAndWait(() -> schedule.setText(schedule.getText() + ",B"));
        SwingUtilities.invokeAndWait(() -> {});

        assertSame(field(window[0], "columns"), previousResult.getParent());

        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        Method renderChangedPlan =
                                RunnerWindow.class.getDeclaredMethod(
                                        "renderChangedPlan", List.class);
                        renderChangedPlan.setAccessible(true);
                        renderChangedPlan.invoke(window[0], List.of("different-candidate.jar"));
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });

        assertNull(previousResult.getParent(), "a changed candidate roster starts a new plan");
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(RunnerWindow window, String name) throws ReflectiveOperationException {
        return field((Object) window, name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }
}
