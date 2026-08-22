package cx.mia.lucent.laymark.runner;

/**
 * Pause, resume and stop for a running experiment.
 *
 * <p><strong>Pause takes effect at the next run boundary, never inside one.</strong> Suspending a
 * game mid-capture would leave a window whose samples straddle a freeze, and resuming would
 * measure a machine whose caches just sat idle — the run would be contaminated either way. So
 * pausing means "finish the current run, then hold", and the boundary is the only place the
 * experiment asks.
 *
 * <p>Stop is immediate: the current game process is killed and the instance restored. The runs
 * already completed keep their results and the report is written from what exists.
 */
public final class RunControl {

    private enum State {
        RUNNING,
        PAUSE_REQUESTED,
        PAUSED,
        STOPPING
    }

    private State state = State.RUNNING;

    /** Kills whatever game process is currently running; registered while one exists. */
    private Runnable abort;

    public synchronized void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSE_REQUESTED;
        }
    }

    public synchronized void resume() {
        if (state == State.PAUSED || state == State.PAUSE_REQUESTED) {
            state = State.RUNNING;
            notifyAll();
        }
    }

    public synchronized void stop() {
        state = State.STOPPING;
        notifyAll();
        if (abort != null) {
            abort.run();
        }
    }

    public synchronized boolean pauseRequested() {
        return state == State.PAUSE_REQUESTED;
    }

    public synchronized boolean stopping() {
        return state == State.STOPPING;
    }

    /**
     * Blocks while paused; called at each run boundary.
     *
     * @return false when the experiment should end instead of launching the next run
     */
    public synchronized boolean awaitClearance() {
        if (state == State.PAUSE_REQUESTED) {
            state = State.PAUSED;
        }
        while (state == State.PAUSED) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return state != State.STOPPING;
    }

    public synchronized void registerAbort(Runnable killer) {
        this.abort = killer;
        if (state == State.STOPPING && killer != null) {
            killer.run();
        }
    }

    public synchronized void clearAbort() {
        this.abort = null;
    }

    /**
     * Starts a new experiment with fresh control state after the previous one has fully ended.
     *
     * <p>Stop is deliberately sticky within one experiment: every remaining arm must observe it.
     * The desktop runner is reusable, though, so its terminal callback clears that state only after
     * the game is gone and the instance has been restored.
     */
    public synchronized void rearm() {
        if (abort != null) {
            throw new IllegalStateException("cannot rearm while a game process is still registered");
        }
        state = State.RUNNING;
    }
}
