package cx.mia.lucent.laymark.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RunControlTest {

    @Test
    void aStoppedRunCanBeRearmedForTheNextRun() {
        RunControl control = new RunControl();
        control.stop();

        assertFalse(control.awaitClearance(), "the stopped run stays stopped");
        control.rearm();
        assertTrue(control.awaitClearance(), "the next run starts with fresh control state");
    }
}
