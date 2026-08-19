package cx.mia.lucent.laymark.runner.gui;

import cx.mia.lucent.laymark.core.experiment.Arm;
import cx.mia.lucent.laymark.core.report.SelectionReport;
import cx.mia.lucent.laymark.core.stats.Comparison;
import cx.mia.lucent.laymark.runner.ExperimentListener;
import cx.mia.lucent.laymark.runner.RunControl;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * The runner's window: the roster on the left, the results everywhere else, in every state.
 *
 * <p>One layout, no view swap. Planning and watching are the same screen: the roster stays
 * visible while a run executes (locked, with per-arm status on its rows), the columns and log
 * stay visible while the next run is planned, and Start re-arms when a run finishes — so runs
 * chain: results, adjust the roster, start again. The planning sidebar is the only part that
 * configures anything; once Start is pressed the window only observes and controls.
 *
 * <p>It shows no summary statistic for the run in flight. No single live number separates a real
 * improvement from noise; the paired comparison in the run columns is the answer, and a headline
 * beside it would be read as one.
 *
 * <p>Swing, in the runner's process and never the game's, so the GUI cannot end up beneath a
 * published number.
 *
 * <p>All listener callbacks arrive on the experiment thread and are marshalled to the EDT here; the
 * experiment never waits on this class.
 */
public final class RunnerWindow implements ExperimentListener {

    /** How the window asks for the experiment it just described to be run. */
    public interface Launcher {
        void start(PlanningView.Choice choice, RunControl control, ExperimentListener listener);
    }

    private static final int LOG_LINE_LIMIT = 4000;

    private final RunControl control;
    private final Launcher launcher;

    private final JFrame frame = new JFrame("Laymark");
    private final PlanningView planning = new PlanningView();

    // Responsive shell: wide windows show the roster as a split-pane sidebar and tall windows
    // keep the log under a draggable divider; below the thresholds each collapses into a drawer
    // that slides over the results on demand.
    private static final int COLLAPSE_THRESHOLD = 900;
    private static final int LOG_COLLAPSE_THRESHOLD = 760;
    private static final int DRAWER_WIDTH = 400;
    private static final int LOG_DRAWER_HEIGHT = 300;
    private javax.swing.JSplitPane body;
    private javax.swing.JSplitPane resultsSplit;
    private JPanel run;
    private JPanel logPanel;
    private final JPanel centerHost = new JPanel(new BorderLayout());
    private JPanel scrim;
    private final JButton hamburger = Theme.button("☰", false);
    private final JButton logButton = Theme.button("Log", false);
    private SlideDrawer rosterDrawer;
    private SlideDrawer logDrawer;
    private boolean railCollapsed;
    private boolean logCollapsed;
    private int logHeight = 240;
    private boolean anchoringLog;

    private final JButton startButton = Theme.button("Start", true);
    private final JButton stopButton = Theme.button("Stop", false);
    private final Theme.Dot statusDot = new Theme.Dot(Theme.MUTED, true);
    private final JLabel status = new JLabel("Ready");
    private final JLabel progress = Theme.muted("");
    private final JLabel eta = Theme.muted("");

    private final JPanel columns = new JPanel();
    private final JLabel currentArm = Theme.mono("—", Theme.TEXT);
    private final JLabel currentBaseline = Theme.mono("—", Theme.TEXT);
    private final JLabel currentScenario = Theme.mono("—", Theme.TEXT);
    private final JTextArea log = new JTextArea();

    private Map<String, String> displayNames = Map.of();
    private final Map<String, ExperimentListener.Preliminary> liveScores = new LinkedHashMap<>();
    // Which live cards and scenario sections the operator (or the run itself) has open. Kept
    // outside the components because every preliminary update rebuilds the column.
    private final Set<String> expandedLive = new java.util.HashSet<>();
    private final Set<String> collapsedLiveScenarios = new java.util.HashSet<>();
    // The lap's candidates and where each stands — queued, testing, done, failed — so the lap
    // column names its whole field from the moment the lap starts, not only the measured part.
    private final Map<String, Integer> lapArmsTotal = new LinkedHashMap<>();
    private final Map<String, Integer> lapArmsDone = new java.util.HashMap<>();
    private final Set<String> lapFailed = new java.util.HashSet<>();
    private String runningCandidate;
    private JPanel liveColumn;
    private JPanel liveRows;
    private String baselineLabel = "baseline";
    private Set<String> baselineMods = Set.of();

    private long startedAt;
    private long armStartedAt;
    private long armMillisTotal;
    private int armsFinished;
    private int armsTotal;
    private int round = 1;
    private int rounds = 1;

    private Timer clock;
    private boolean running;
    private boolean paused;

    private RunnerWindow(RunControl control, Launcher launcher) {
        this.control = control;
        this.launcher = launcher;
        build();
    }

    /**
     * Opens the window on the planning view, where an operator chooses what to run.
     *
     * @param launcher what Start invokes; the window never builds an experiment itself
     */
    public static RunnerWindow open(RunControl control, Launcher launcher) {
        Theme.install();
        RunnerWindow window = new RunnerWindow(control, launcher);
        SwingUtilities.invokeLater(() -> window.frame.setVisible(true));
        return window;
    }

    /** Opens straight into the run view, for an experiment the command line already described. */
    public static RunnerWindow openRunning(RunControl control) {
        RunnerWindow window = open(control, null);
        SwingUtilities.invokeLater(window::enterRunView);
        return window;
    }

    // --- structure ---

    private void build() {
        // One layout, no view swap: the roster is the left sidebar in every state, so the next
        // run is planned while the last run's columns are still on screen, and Start re-arms once
        // a run finishes. The divider is draggable -- planning wants a wide roster, watching
        // wants wide columns, and only the operator knows which they are doing.
        planning.setMinimumSize(new Dimension(300, 0));

        run = runView();
        // The split pane bounds dragging by component minimums, and BorderLayout reports the
        // widest child's minimum -- which here is an unwrappable caption, several hundred pixels
        // of it. In the narrow strip the window docks into beside the game, that phantom minimum
        // consumed the whole drag range and froze the divider. Zero it: a clipped caption is
        // recoverable with a drag, a divider that will not drag is not.
        run.setMinimumSize(new Dimension(0, 0));
        body = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, planning, run);
        body.setBackground(Theme.BACKGROUND);
        body.setBorder(null);
        body.setContinuousLayout(true);
        body.setDividerSize(8);
        body.setDividerLocation(430);
        // Extra width goes to the results side; the roster keeps whatever the operator dragged.
        body.setResizeWeight(0);

        rosterDrawer = new SlideDrawer(true);
        logDrawer = new SlideDrawer(false);
        scrim =
                new JPanel() {
                    @Override
                    protected void paintComponent(java.awt.Graphics g) {
                        g.setColor(new Color(0, 0, 0, 110));
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }
                };
        scrim.setOpaque(false);
        scrim.addMouseListener(
                new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent unused) {
                        rosterDrawer.close(true);
                        logDrawer.close(true);
                    }
                });

        centerHost.setBackground(Theme.BACKGROUND);
        centerHost.add(body, BorderLayout.CENTER);

        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(Theme.BACKGROUND);
        frame.add(topBar(), BorderLayout.NORTH);
        frame.add(centerHost, BorderLayout.CENTER);
        frame.setSize(1500, 900);
        // Small enough to live in whatever strip the game leaves free; below the collapse
        // thresholds the roster and the log are drawers, so the results keep the full window.
        frame.setMinimumSize(new Dimension(560, 480));
        frame.setLocationByPlatform(true);
        frame.addComponentListener(
                new java.awt.event.ComponentAdapter() {
                    @Override
                    public void componentResized(java.awt.event.ComponentEvent unused) {
                        updateResponsiveLayout();
                    }
                });

        // Closing is a stop, not an escape: a run left headless by accident would keep the machine
        // busy with no way to reach it.
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(
                new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent unused) {
                        if (running) {
                            confirmStop();
                        } else {
                            frame.dispose();
                            System.exit(0);
                        }
                    }
                });

        clock =
                new Timer(
                        1000,
                        unused -> {
                            updateEstimate();
                            frame.repaint();
                        });

        // The plan draws itself where its laps will land, live from the first look.
        planning.onPlanChanged(() -> SwingUtilities.invokeLater(this::renderPlanPreview));
        SwingUtilities.invokeLater(this::renderPlanPreview);
    }

    private JPanel topBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 12));
        bar.setBackground(Theme.CARD);
        bar.setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE));

        startButton.addActionListener(unused -> onStartOrPause());
        stopButton.addActionListener(unused -> confirmStop());
        stopButton.setEnabled(false);
        status.setForeground(Theme.MUTED);

        hamburger.setToolTipText("Mod roster");
        hamburger.setVisible(false);
        hamburger.addActionListener(unused -> rosterDrawer.toggle());
        logButton.setToolTipText("Log");
        logButton.setVisible(false);
        logButton.addActionListener(unused -> logDrawer.toggle());

        bar.add(hamburger);
        bar.add(logButton);
        bar.add(startButton);
        bar.add(stopButton);
        bar.add(Theme.separator());
        bar.add(statusDot);
        bar.add(status);
        bar.add(Theme.separator());
        bar.add(progress);
        bar.add(Theme.separator());
        bar.add(eta);
        return bar;
    }

    // --- responsive shell ---

    /**
     * Moves the roster and the log between their two homes as the window crosses the thresholds.
     *
     * <p>Wide: the roster is a split-pane sidebar, always visible, divider draggable. Narrow: it
     * leaves the layout — the results take the full width — and comes back as a drawer sliding in
     * from the hamburger. Tall and short do the same for the log along the other axis. The same
     * component instances move between parents, so toggles, search state, per-arm status and the
     * log text survive every crossing.
     */
    private void updateResponsiveLayout() {
        boolean narrow = frame.getWidth() < COLLAPSE_THRESHOLD;
        if (narrow != railCollapsed) {
            railCollapsed = narrow;
            hamburger.setVisible(narrow);
            if (narrow) {
                centerHost.remove(body);
                rosterDrawer.panel.add(planning, BorderLayout.CENTER);
                centerHost.add(run, BorderLayout.CENTER);
            } else {
                rosterDrawer.close(false);
                rosterDrawer.panel.remove(planning);
                centerHost.remove(run);
                body.setLeftComponent(planning);
                body.setRightComponent(run);
                centerHost.add(body, BorderLayout.CENTER);
                body.setDividerLocation(Math.min(430, frame.getWidth() / 2));
            }
            centerHost.revalidate();
            centerHost.repaint();
        }

        boolean shallow = frame.getHeight() < LOG_COLLAPSE_THRESHOLD;
        if (shallow != logCollapsed) {
            logCollapsed = shallow;
            logButton.setVisible(shallow);
            if (shallow) {
                resultsSplit.setBottomComponent(null);
                logDrawer.panel.add(logPanel, BorderLayout.CENTER);
            } else {
                logDrawer.close(false);
                logDrawer.panel.remove(logPanel);
                resultsSplit.setBottomComponent(logPanel);
                anchorLog();
            }
            resultsSplit.revalidate();
            resultsSplit.repaint();
        }

        rosterDrawer.relayout();
        logDrawer.relayout();
    }

    /**
     * A sheet that slides over the results from one edge — the roster from the left, the log from
     * the bottom. Both share the single scrim, so opening one closes the other.
     */
    private final class SlideDrawer {

        final JPanel panel = new JPanel(new BorderLayout());
        private final boolean fromLeft;
        private boolean shown;
        private Timer animator;

        SlideDrawer(boolean fromLeft) {
            this.fromLeft = fromLeft;
            panel.setBackground(Theme.BACKGROUND);
            panel.setBorder(
                    fromLeft
                            ? javax.swing.BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.LINE)
                            : javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.LINE));
        }

        void toggle() {
            if (shown) {
                close(true);
            } else {
                open();
            }
        }

        void open() {
            if (shown) {
                return;
            }
            (this == rosterDrawer ? logDrawer : rosterDrawer).close(false);
            shown = true;
            var layers = frame.getRootPane().getLayeredPane();
            relayout();
            panel.setLocation(
                    fromLeft ? -panel.getWidth() : 0, fromLeft ? 0 : layers.getHeight());
            layers.add(scrim, javax.swing.JLayeredPane.MODAL_LAYER);
            layers.add(panel, javax.swing.JLayeredPane.POPUP_LAYER);
            layers.revalidate();
            layers.repaint();
            slideTo(fromLeft ? 0 : layers.getHeight() - panel.getHeight(), null);
        }

        void close(boolean animated) {
            if (!shown) {
                return;
            }
            shown = false;
            var layers = frame.getRootPane().getLayeredPane();
            Runnable detach =
                    () -> {
                        layers.remove(scrim);
                        layers.remove(panel);
                        layers.revalidate();
                        layers.repaint();
                    };
            if (animated) {
                slideTo(fromLeft ? -panel.getWidth() : layers.getHeight(), detach);
            } else {
                if (animator != null) {
                    animator.stop();
                }
                detach.run();
            }
        }

        /** Overlay geometry, recomputed on open and on every resize while the sheet is out. */
        void relayout() {
            if (!shown) {
                return;
            }
            var layers = frame.getRootPane().getLayeredPane();
            scrim.setBounds(0, 0, layers.getWidth(), layers.getHeight());
            boolean sliding = animator != null && animator.isRunning();
            if (fromLeft) {
                int width = Math.min(DRAWER_WIDTH, Math.max(260, layers.getWidth() - 60));
                panel.setBounds(sliding ? panel.getX() : 0, 0, width, layers.getHeight());
            } else {
                int height =
                        Math.min(LOG_DRAWER_HEIGHT, Math.max(160, layers.getHeight() - 160));
                panel.setBounds(
                        0,
                        sliding ? panel.getY() : layers.getHeight() - height,
                        layers.getWidth(),
                        height);
            }
        }

        /** A short ease-out slide; the scrim just appears, the sheet does the moving. */
        private void slideTo(int target, Runnable onDone) {
            if (animator != null) {
                animator.stop();
            }
            int from = fromLeft ? panel.getX() : panel.getY();
            long startedSliding = System.currentTimeMillis();
            int duration = 140;
            animator = new Timer(10, null);
            animator.addActionListener(
                    unused -> {
                        float linear =
                                Math.min(
                                        1f,
                                        (System.currentTimeMillis() - startedSliding)
                                                / (float) duration);
                        float eased = 1 - (1 - linear) * (1 - linear);
                        int at = Math.round(from + (target - from) * eased);
                        panel.setLocation(
                                fromLeft ? at : panel.getX(), fromLeft ? panel.getY() : at);
                        if (linear >= 1f) {
                            animator.stop();
                            if (onDone != null) {
                                onDone.run();
                            }
                        }
                    });
            animator.start();
        }
    }

    private JPanel runView() {
        JPanel view = new JPanel(new BorderLayout(12, 12));
        view.setBackground(Theme.BACKGROUND);
        Theme.pad(view, 12, 12, 12, 12);

        columns.setLayout(new BoxLayout(columns, BoxLayout.X_AXIS));
        columns.setOpaque(false);
        JScrollPane columnScroll = Theme.scroll(columns);
        columnScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JPanel grid = new JPanel(new BorderLayout(0, 6));
        grid.setOpaque(false);
        grid.add(columnScroll, BorderLayout.CENTER);
        grid.add(
                Theme.muted(
                        "Ranked by improvement against the column's baseline. A band, not a score:"
                                + " a percentage without one is noise wearing a number."),
                BorderLayout.SOUTH);

        // The current-run card stays fixed above the divider; only the log rides below it, so
        // the handle sits directly over the log and dragging it never moves the three facts
        // that say what the numbers mean.
        JPanel main = new JPanel(new BorderLayout(0, 12));
        main.setOpaque(false);
        main.add(grid, BorderLayout.CENTER);
        main.add(currentRunCard(), BorderLayout.SOUTH);
        main.setMinimumSize(new Dimension(0, 0));

        logPanel = logCard();
        logPanel.setMinimumSize(new Dimension(0, 0));
        logPanel.setPreferredSize(new Dimension(0, 240));

        resultsSplit =
                new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, main, logPanel);
        resultsSplit.setBackground(Theme.BACKGROUND);
        resultsSplit.setBorder(null);
        resultsSplit.setContinuousLayout(true);
        resultsSplit.setDividerSize(8);

        // The log is anchored to the bottom by hand, not with resizeWeight: weight-based
        // redistribution computes deltas from the last laid-out size, and asynchronous
        // validation can interleave a zero-size pass that wrecks the arithmetic -- observed as
        // the divider pinning absolutely and the log being eaten 1:1 by a shrinking window.
        // Explicit is boring and correct: divider drags record the operator's log height, and
        // every split resize re-asserts it.
        resultsSplit.addPropertyChangeListener(
                javax.swing.JSplitPane.DIVIDER_LOCATION_PROPERTY,
                unused -> {
                    if (!anchoringLog
                            && resultsSplit.getHeight() > 0
                            && resultsSplit.getBottomComponent() == logPanel) {
                        int dragged =
                                resultsSplit.getHeight()
                                        - resultsSplit.getDividerLocation()
                                        - resultsSplit.getDividerSize();
                        // A floor, not >= 0: transient layout passes (macOS fires divider events
                        // for clamps a human never made) would otherwise record "the operator
                        // dragged the log to nothing" and the anchor would faithfully keep it
                        // there. Hiding the log is the drawer's job, not a zero-height drag's.
                        if (dragged >= 40) {
                            logHeight = dragged;
                        }
                    }
                });
        resultsSplit.addComponentListener(
                new java.awt.event.ComponentAdapter() {
                    @Override
                    public void componentResized(java.awt.event.ComponentEvent unused) {
                        anchorLog();
                    }
                });

        view.add(resultsSplit, BorderLayout.CENTER);
        return view;
    }

    /** Re-asserts the operator's log height after anything moves the split under it. */
    private void anchorLog() {
        if (resultsSplit.getBottomComponent() != logPanel || resultsSplit.getHeight() <= 0) {
            return;
        }
        anchoringLog = true;
        int height = resultsSplit.getHeight();
        int location = Math.max(120, height - logHeight - resultsSplit.getDividerSize());
        resultsSplit.setDividerLocation(
                Math.min(location, height - resultsSplit.getDividerSize()));
        anchoringLog = false;
    }

    /**
     * The three facts that decide whether the number arriving next means anything: which arm, what
     * it is measured against, and which scenario is capturing.
     */
    private JPanel currentRunCard() {
        JPanel card = Theme.card("Current arm");
        JPanel fields = new JPanel(new GridLayout(1, 3, 24, 0));
        fields.setOpaque(false);
        fields.add(field("Arm being tested", currentArm));
        fields.add(field("Baseline it is measured against", currentBaseline));
        fields.add(field("Scenario capturing", currentScenario));
        card.add(fields, BorderLayout.CENTER);
        return card;
    }

    private static JPanel field(String name, JLabel value) {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        JLabel label = Theme.muted(name);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        value.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(label);
        column.add(Box.createVerticalStrut(4));
        column.add(value);
        return column;
    }

    private JPanel logCard() {
        JPanel card = Theme.card(null);
        card.setLayout(new BorderLayout(0, 8));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(Theme.heading("Log"), BorderLayout.WEST);
        JButton clear = Theme.button("Clear", false);
        clear.addActionListener(unused -> log.setText(""));
        header.add(clear, BorderLayout.EAST);

        log.setEditable(false);
        log.setFont(Theme.MONO);
        log.setBackground(Theme.CARD);
        log.setForeground(Theme.TEXT);

        card.add(header, BorderLayout.NORTH);
        card.add(Theme.scroll(log), BorderLayout.CENTER);
        return card;
    }

    // --- control ---

    private void onStartOrPause() {
        if (!running) {
            PlanningView.Choice choice = planning.choice();
            if (choice == null) {
                JOptionPane.showMessageDialog(
                        frame, planning.blocker(), "Nothing to run", JOptionPane.WARNING_MESSAGE);
                return;
            }
            enterRunView();
            launcher.start(choice, control, this);
            return;
        }
        if (paused) {
            control.resume();
            paused = false;
            startButton.setText("Pause");
            state("Running", Theme.GOOD);
        } else {
            control.pause();
            paused = true;
            startButton.setText("Resume");
            // Honest about the semantics: the hold happens at the boundary, not now.
            state("Pausing after this arm", Theme.ACCENT);
        }
    }

    private void enterRunView() {
        // The run starts over its own placeholders, not a blank: if the columns hold anything
        // else (the last run's results), the preview redraws first, and each starting lap then
        // replaces its placeholder in place. Stability over spectacle.
        if (!columnsArePreview) {
            renderPlanPreview();
        }
        columnsArePreview = false;
        running = true;
        startedAt = System.currentTimeMillis();
        armMillisTotal = 0;
        armsFinished = 0;
        armsTotal = plannedArms;
        round = 1;
        rounds = Math.max(1, plannedLaps);
        baselineLabel = "baseline";
        liveScores.clear();
        expandedLive.clear();
        collapsedLiveScenarios.clear();
        startButton.setText("Pause");
        stopButton.setEnabled(true);
        updateProgress();
        // The roster stays visible but stops being editable: the arms are already decided, and a
        // toggle that appeared to change a running experiment would be lying.
        planning.setPlanningEnabled(false);
        state("Starting", Theme.ACCENT);
        clock.start();
        dockBesideGame();
    }

    /**
     * Moves this window into whatever space the game will not use.
     *
     * <p>The game is pinned to the top-left corner at a fixed size (the preset does that), so the
     * space right of it — or below it, on a narrow screen — is known before it even launches.
     * Docking there means the schedule and the current arm stay visible while the game runs,
     * instead of the two windows taking turns hiding each other.
     */
    private void dockBesideGame() {
        var screen = frame.getGraphicsConfiguration().getBounds();
        var scale = frame.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        int gameRight = (int) Math.ceil(cx.mia.lucent.laymark.core.Laymark.WINDOW_WIDTH / scale);
        int gameBottom =
                (int) Math.ceil((cx.mia.lucent.laymark.core.Laymark.WINDOW_HEIGHT + 80) / scale);

        int rightWidth = screen.width - gameRight;
        if (rightWidth >= 420) {
            frame.setBounds(screen.x + gameRight, screen.y, rightWidth, screen.height);
        } else if (screen.height - gameBottom >= 300) {
            frame.setBounds(screen.x, screen.y + gameBottom, screen.width, screen.height - gameBottom);
        }
        // A screen with room for neither keeps the window where the operator put it.

        // The dock decides the width, so decide the layout for it now rather than waiting for
        // the resize event: collapse to the drawer if the strip is narrow, and in split mode
        // re-split at up to 430 but never past half the docked width, so both sides start usable.
        updateResponsiveLayout();
        if (!railCollapsed) {
            body.setDividerLocation(Math.min(430, frame.getWidth() / 2));
        }
    }

    private void confirmStop() {
        int choice =
                JOptionPane.showConfirmDialog(
                        frame,
                        "Stop the run? The current game is killed, the instance restored, and the"
                                + " report written from the arms that completed.",
                        "Stop",
                        JOptionPane.OK_CANCEL_OPTION);
        if (choice == JOptionPane.OK_OPTION) {
            control.stop();
            state("Stopping", Theme.BAD);
            startButton.setEnabled(false);
            stopButton.setEnabled(false);
        }
    }

    private void state(String text, Color colour) {
        status.setText(text);
        status.setForeground(colour);
        statusDot.set(colour, true);
    }

    // --- log ---

    /**
     * Wraps a stream so everything the runner prints also lands in the window's log.
     *
     * <p>Teeing rather than a log callback threaded through the experiment: the console output is
     * already the full account of a run, and a window showing less than the terminal is a window an
     * operator has to leave anyway.
     */
    public PrintStream tee(PrintStream underlying) {
        OutputStream sink =
                new FilterOutputStream(underlying) {
                    private final StringBuilder line = new StringBuilder();

                    @Override
                    public void write(int b) throws java.io.IOException {
                        underlying.write(b);
                        if (b == '\n') {
                            String text = line.toString();
                            line.setLength(0);
                            SwingUtilities.invokeLater(() -> append(text));
                        } else if (b != '\r') {
                            line.append((char) b);
                        }
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
                        for (int i = 0; i < length; i++) {
                            write(bytes[offset + i]);
                        }
                    }
                };
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    private void append(String text) {
        log.append(text);
        log.append("\n");
        if (log.getLineCount() > LOG_LINE_LIMIT) {
            try {
                log.replaceRange("", 0, log.getLineEndOffset(log.getLineCount() - LOG_LINE_LIMIT));
            } catch (javax.swing.text.BadLocationException ignored) {
                // Trimming is housekeeping; failing to trim is not worth losing the log over.
            }
        }
        log.setCaretPosition(log.getDocument().getLength());
    }

    // --- listener side, marshalled to the EDT ---

    @Override
    public void scheduleBuilt(Slate slate) {
        SwingUtilities.invokeLater(
                () -> {
                    armsTotal = slate.armsTotal();
                    round = slate.round();
                    rounds = slate.rounds();
                    for (Arm arm : slate.arms()) {
                        if (arm.kind() == Arm.Kind.BASELINE && baselineMods.isEmpty()) {
                            // The reference set every candidate is described against.
                            baselineMods = Set.copyOf(arm.enabled());
                        }
                    }
                    lapArmsTotal.clear();
                    lapArmsDone.clear();
                    lapFailed.clear();
                    runningCandidate = null;
                    for (Arm arm : slate.arms()) {
                        if (arm.kind() == Arm.Kind.CANDIDATE) {
                            planning.armStatus(arm.id(), "queued", Theme.MUTED);
                            lapArmsTotal.merge(arm.id(), 1, Integer::sum);
                        }
                    }
                    startColumn(baselineLabel);
                    renderLiveColumn();
                    updateProgress();
                    refresh();
                });
    }

    @Override
    public void runStarted(int sequence, Arm arm) {
        SwingUtilities.invokeLater(
                () -> {
                    armStartedAt = System.currentTimeMillis();
                    state("Running", Theme.GOOD);
                    currentArm.setText(describe(arm));
                    currentBaseline.setText(
                            arm.kind() == Arm.Kind.CANDIDATE ? baselineLabel : "— reference run");
                    currentScenario.setText("starting the game");
                    planning.armStatus(arm.id(), "running", Theme.ACCENT);
                    if (arm.kind() == Arm.Kind.CANDIDATE) {
                        runningCandidate = arm.id();
                        if (!expandedLive.contains(arm.id())) {
                            // The card follows the run: the candidate in flight opens so its
                            // numbers land in view, and the previous one closes for the room.
                            expandedLive.clear();
                            expandedLive.add(arm.id());
                            collapsedLiveScenarios.clear();
                        }
                        renderLiveColumn();
                    }
                    updateProgress();
                    refresh();
                });
    }

    @Override
    public void scenarioStarted(String scenarioId, int repetition) {
        SwingUtilities.invokeLater(() -> currentScenario.setText(scenarioId + "  #" + repetition));
    }

    @Override
    public void runFinished(int sequence, Arm arm, double scoredMillis, boolean failed) {
        SwingUtilities.invokeLater(
                () -> {
                    if (armStartedAt > 0) {
                        armMillisTotal += System.currentTimeMillis() - armStartedAt;
                        armStartedAt = 0;
                    }
                    armsFinished++;
                    currentScenario.setText("—");
                    planning.armStatus(
                            arm.id(), failed ? "failed" : "done", failed ? Theme.BAD : Theme.GOOD);
                    if (arm.kind() == Arm.Kind.CANDIDATE) {
                        lapArmsDone.merge(arm.id(), 1, Integer::sum);
                        if (failed) {
                            lapFailed.add(arm.id());
                        }
                        if (arm.id().equals(runningCandidate)) {
                            runningCandidate = null;
                        }
                        renderLiveColumn();
                    }
                    updateProgress();
                    updateEstimate();
                    refresh();
                });
    }

    @Override
    public void preliminaryScore(ExperimentListener.Preliminary preliminary) {
        // The full running aggregate against the baseline measured so far -- one opaque percent
        // mid-round read as a result while saying nothing about where it came from. Still
        // preliminary by name until the round closes and the real comparison replaces it.
        SwingUtilities.invokeLater(
                () -> {
                    liveScores.put(preliminary.id(), preliminary);
                    renderLiveColumn();
                    refresh();
                });
    }

    @Override
    public void roundCompleted(
            int roundNumber,
            String baseline,
            List<Comparison> comparisons,
            List<CandidateScore> scores,
            String promoted) {
        SwingUtilities.invokeLater(
                () -> {
                    finishColumn(comparisons, scores, promoted);
                    // The next round's column opens from its scheduleBuilt; what changes here is
                    // only what that column will call its baseline.
                    if (promoted != null) {
                        baselineLabel = baseline + " + " + display(promoted);
                    }
                    liveScores.clear();
                    expandedLive.clear();
                    collapsedLiveScenarios.clear();
                    refresh();
                });
    }

    @Override
    public void stateChanged(String text) {
        SwingUtilities.invokeLater(
                () -> {
                    if (paused && !"running".equals(text)) {
                        return;
                    }
                    switch (text) {
                        case "running" -> state("Running", Theme.GOOD);
                        case "paused" -> state("Paused", Theme.ACCENT);
                        case "stopping" -> state("Stopping", Theme.BAD);
                        default -> state(text, Theme.MUTED);
                    }
                });
    }

    @Override
    public void finished(SelectionReport report) {
        SwingUtilities.invokeLater(
                () -> {
                    running = false;
                    paused = false;
                    state(report == null ? "Stopped" : "Finished", report == null ? Theme.BAD : Theme.GOOD);
                    currentArm.setText("—");
                    currentBaseline.setText("—");
                    currentScenario.setText("—");
                    eta.setText("");
                    clock.stop();
                    // Re-armed, not retired: the columns stay on screen, the roster unlocks, and
                    // Start begins the next experiment with whatever the operator changes -- the
                    // "Previous run's winners" preset is the natural next click.
                    startButton.setText("Start");
                    startButton.setEnabled(launcher != null);
                    stopButton.setEnabled(false);
                    baselineMods = Set.of();
                    // Placeholders for laps that never ran (greedy selection stops when nothing
                    // promotes) come down with their struts; measured columns stay.
                    for (Component placeholder : previewColumns) {
                        int at = columns.getComponentZOrder(placeholder);
                        if (at >= 0) {
                            columns.remove(at);
                            if (at < columns.getComponentCount()) {
                                columns.remove(at); // the strut that followed it
                            }
                        }
                    }
                    previewColumns.clear();
                    columns.revalidate();
                    columns.repaint();
                    planning.setPlanningEnabled(true);
                });
    }

    // --- header readouts ---

    /** "18/28 arms in 4/7 laps" — arms are the launches, laps the selection rounds of one run. */
    private void updateProgress() {
        progress.setText(
                String.format(
                        Locale.ROOT,
                        "%d/%d arms in %d/%d laps",
                        armsFinished,
                        Math.max(armsTotal, armsFinished),
                        round,
                        rounds));
    }

    /**
     * Extrapolated from the arms that have finished, which is the only evidence there is. Shows
     * elapsed only until one has, rather than guessing from nothing.
     */
    private void updateEstimate() {
        long elapsed = System.currentTimeMillis() - startedAt;
        int left = Math.max(armsTotal, armsFinished) - armsFinished;
        if (armsFinished == 0 || left <= 0) {
            // Until an arm has finished there is no evidence, but there is still the plan: the
            // tilde-grade estimate counts down rather than the readout going blank at Start.
            eta.setText(
                    plannedRunMillis > 0 && left > 0
                            ? "elapsed "
                                    + clock(elapsed)
                                    + "   ETA ~"
                                    + clock(Math.max(0, plannedRunMillis - elapsed))
                            : "elapsed " + clock(elapsed));
            return;
        }
        long perArm = armMillisTotal / armsFinished;
        long inFlight = armStartedAt == 0 ? 0 : System.currentTimeMillis() - armStartedAt;
        eta.setText(
                "elapsed "
                        + clock(elapsed)
                        + "   ETA "
                        + clock(Math.max(0, (long) left * perArm - inFlight)));
    }

    private static String clock(long millis) {
        long seconds = millis / 1000;
        return String.format(
                Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    // --- the plan preview, live while planning ---

    private java.nio.file.Path cachedPlanPath;
    private long cachedPlanStamp;
    private cx.mia.lucent.laymark.core.plan.RunPlan cachedPlan;

    // The placeholders the preview drew, in lap order. A starting lap replaces its own
    // placeholder in place, so Start changes the columns' content, never their shape.
    private final List<Component> previewColumns = new ArrayList<>();
    private boolean columnsArePreview;
    private int plannedLaps;
    private int plannedArms;
    private long plannedRunMillis;

    /**
     * The run as currently planned, drawn where its laps will land: one estimated column per
     * lap, lap 1 filled with the chosen candidates, later laps holding "not yet known" slots —
     * the arms are certain, their occupants are what the run exists to decide. Redrawn on every
     * roster, schedule or instance change; replaced by the real columns the moment Start runs.
     */
    private void renderPlanPreview() {
        if (running) {
            return;
        }
        List<String> candidates = planning.candidateDisplays();
        var schedule = planning.previewSchedule();
        cx.mia.lucent.laymark.core.plan.RunPlan plan = previewPlan();

        columns.removeAll();
        previewColumns.clear();
        liveColumn = null;
        liveRows = null;
        columnsArePreview = true;
        plannedLaps = 0;
        plannedArms = 0;
        plannedRunMillis = 0;

        int pool = candidates.size();
        if (pool == 0 || schedule == null) {
            progress.setText(
                    schedule == null ? "the schedule does not parse" : "no candidates selected");
            eta.setText("");
            columns.revalidate();
            columns.repaint();
            refresh();
            return;
        }

        Arm baseline = new Arm("b", Arm.Kind.BASELINE, Set.of());
        Arm acclimation = new Arm("a", Arm.Kind.ACCLIMATION, Set.of());
        int totalArms = 0;
        for (int lap = 1; lap <= pool; lap++) {
            int inLap = pool - lap + 1;
            List<Arm> dummies = new ArrayList<>();
            for (int i = 0; i < inLap; i++) {
                dummies.add(new Arm("c" + i, Arm.Kind.CANDIDATE, Set.of()));
            }
            int arms = schedule.expand(dummies, baseline, acclimation, lap != 1).size();
            totalArms += arms;
            JPanel column = previewColumn(lap, inLap, lap == 1 ? candidates : List.of(), arms);
            previewColumns.add(column);
            columns.add(column);
            columns.add(Box.createHorizontalStrut(10));
        }
        plannedLaps = pool;
        plannedArms = totalArms;

        int scenarios = plan == null ? 0 : plan.scenarios().size();
        progress.setText(
                String.format(
                        Locale.ROOT,
                        "%d laps · %d arms · %d scenarios",
                        pool,
                        totalArms,
                        scenarios));
        plannedRunMillis = plan == null ? 0 : totalArms * armEstimateMillis(plan);
        eta.setText(plannedRunMillis == 0 ? "" : "ETA ~" + clock(plannedRunMillis));
        columns.revalidate();
        columns.repaint();
        refresh();
    }

    /** One projected lap: how many arms it costs, and who runs in it where that is knowable. */
    private JPanel previewColumn(int lap, int slots, List<String> candidates, int arms) {
        JPanel column = Theme.card(null);
        column.setLayout(new BorderLayout(0, 8));
        column.setAlignmentY(Component.TOP_ALIGNMENT);
        column.setPreferredSize(new Dimension(300, 320));
        column.setMaximumSize(new Dimension(300, Integer.MAX_VALUE));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        JLabel title = Theme.heading("Lap " + lap + "  (estimated)");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel sub =
                Theme.muted(
                        arms
                                + " arms"
                                + (lap == 1
                                        ? ""
                                        : "  ·  baseline grows by lap " + (lap - 1) + "'s winner"));
        sub.setAlignmentX(Component.CENTER_ALIGNMENT);
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(sub);

        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        for (int i = 0; i < slots; i++) {
            boolean known = i < candidates.size();
            rows.add(previewRow(i + 1, known ? candidates.get(i) : "not yet known", known));
        }

        ColumnView holder = new ColumnView();
        holder.add(rows, BorderLayout.NORTH);
        JScrollPane rowScroll = Theme.scroll(holder);
        rowScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        column.add(header, BorderLayout.NORTH);
        column.add(rowScroll, BorderLayout.CENTER);
        return column;
    }

    private static JPanel previewRow(int rank, String name, boolean known) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBackground(Theme.RAISED);
        row.setBorder(
                javax.swing.BorderFactory.createCompoundBorder(
                        new Theme.RoundedBorder(Theme.LINE, 8),
                        javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel label = new JLabel(rank + ".  " + name);
        label.setForeground(known ? Theme.TEXT : Theme.MUTED);
        if (!known) {
            label.setFont(label.getFont().deriveFont(Font.ITALIC));
        }
        label.setToolTipText(label.getText());
        label.setMinimumSize(new Dimension(0, 0));
        row.add(label, BorderLayout.CENTER);
        return fullWidthRow(row);
    }

    /** The instance's scenario config resolved to a plan, cached by the file's timestamp. */
    private cx.mia.lucent.laymark.core.plan.RunPlan previewPlan() {
        java.nio.file.Path directory = planning.previewGameDirectory();
        if (directory == null) {
            return null;
        }
        java.nio.file.Path config =
                directory.resolve(cx.mia.lucent.laymark.core.Laymark.CONFIG_PATH);
        try {
            if (!java.nio.file.Files.isRegularFile(config)) {
                return null;
            }
            long stamp = java.nio.file.Files.getLastModifiedTime(config).toMillis();
            if (config.equals(cachedPlanPath) && stamp == cachedPlanStamp) {
                return cachedPlan;
            }
            cachedPlan =
                    cx.mia.lucent.laymark.core.scenario.ConfigCodec.read(
                                    java.nio.file.Files.readString(config, StandardCharsets.UTF_8))
                            .resolve("preview", "preview");
            cachedPlanPath = config;
            cachedPlanStamp = stamp;
            return cachedPlan;
        } catch (java.io.IOException | RuntimeException unreadable) {
            cachedPlanPath = null;
            cachedPlan = null;
            return null;
        }
    }

    /**
     * A per-arm guess, tilde-grade on purpose. TIME stops add up exactly; CHUNKS stops are
     * guessed from what the measured phase costs per chunk; FRAMES assume ~100fps. The rest is
     * launch, world and settling overhead, and every scenario runs twice per arm — a cold and a
     * warm pass.
     */
    private static long armEstimateMillis(cx.mia.lucent.laymark.core.plan.RunPlan plan) {
        long millis = 60_000; // launch, handshake, shutdown
        for (cx.mia.lucent.laymark.core.plan.ScenarioSpec scenario : plan.scenarios()) {
            long capture =
                    switch (scenario.stopCondition().kind()) {
                        case TIME -> scenario.stopCondition().target();
                        case FRAMES -> scenario.stopCondition().target() * 10;
                        case CHUNKS ->
                                scenario.stopCondition().target() * msPerChunkGuess(scenario);
                    };
            if (scenario.measure().contains(cx.mia.lucent.laymark.core.Phase.SPAWN_GENERATION)) {
                capture += 30_000;
            }
            // World creation or reopening, the join barrier, settling -- per repetition.
            millis += (capture + 25_000L) * scenario.repetitions() * 2;
        }
        return millis;
    }

    private static long msPerChunkGuess(cx.mia.lucent.laymark.core.plan.ScenarioSpec scenario) {
        if (scenario.measure().contains(cx.mia.lucent.laymark.core.Phase.UNGENERATED_TRAVERSAL)) {
            return 40; // generation and everything downstream
        }
        if (scenario.measure().contains(cx.mia.lucent.laymark.core.Phase.GENERATED_STREAMING)) {
            return 8; // deserialise, send, mesh, upload
        }
        return 15;
    }

    /** An arm named by what it changes, since its id alone rarely says what is being tried. */
    private String describe(Arm arm) {
        Set<String> added = new TreeSet<>(arm.enabled());
        added.removeAll(baselineMods);
        Set<String> removed = new TreeSet<>(baselineMods);
        removed.removeAll(arm.enabled());
        if (added.isEmpty() && removed.isEmpty()) {
            return arm.id() + "  (the baseline stack unchanged)";
        }
        StringBuilder text = new StringBuilder(arm.id()).append("  ");
        added.forEach(mod -> text.append('+').append(display(mod)).append(' '));
        removed.forEach(mod -> text.append('-').append(display(mod)).append(' '));
        return text.toString().trim();
    }

    private static String shorten(String fileName) {
        return fileName.replaceFirst("\\.jar$", "");
    }

    /** The mod's own name where one is known; the file name is identity, not prose. */
    private String display(String fileName) {
        return displayNames.getOrDefault(fileName, shorten(fileName));
    }

    @Override
    public void named(Map<String, String> names) {
        SwingUtilities.invokeLater(() -> displayNames = Map.copyOf(names));
    }

    // --- run columns ---

    private void startColumn(String baseline) {
        baselineLabel = baseline;
        liveColumn = Theme.card(null);
        liveColumn.setLayout(new BorderLayout(0, 8));
        liveColumn.setAlignmentY(Component.TOP_ALIGNMENT);
        liveColumn.setPreferredSize(new Dimension(300, 320));
        liveColumn.setMaximumSize(new Dimension(300, Integer.MAX_VALUE));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        JLabel title = Theme.heading("Lap " + round);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel against = Theme.muted("Baseline: " + baseline);
        against.setAlignmentX(Component.CENTER_ALIGNMENT);
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(against);

        liveRows = new JPanel();
        liveRows.setLayout(new BoxLayout(liveRows, BoxLayout.Y_AXIS));
        liveRows.setOpaque(false);

        // Rows pinned to the top of the column; a BoxLayout left to fill would spread them down it.
        ColumnView holder = new ColumnView();
        holder.add(liveRows, BorderLayout.NORTH);

        // Vertically scrollable: an expanded card can want more height than the column has, and
        // a column with no scrollbar would clip the very detail someone just asked to see.
        JScrollPane rowScroll = Theme.scroll(holder);
        rowScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        liveColumn.add(header, BorderLayout.NORTH);
        liveColumn.add(rowScroll, BorderLayout.CENTER);
        // Into the lap's own placeholder where the preview drew one; the strut after it stays.
        Component placeholder =
                round - 1 < previewColumns.size() ? previewColumns.get(round - 1) : null;
        int at = placeholder == null ? -1 : columns.getComponentZOrder(placeholder);
        if (at >= 0) {
            columns.remove(at);
            columns.add(liveColumn, at);
        } else {
            columns.add(liveColumn);
            columns.add(Box.createHorizontalStrut(10));
        }
    }

    private void renderLiveColumn() {
        if (liveRows == null) {
            return;
        }
        liveRows.removeAll();
        int[] rank = {0};
        liveScores.values().stream()
                .sorted(
                        java.util.Comparator.comparingDouble(
                                        ExperimentListener.Preliminary::improvementPercent)
                                .reversed())
                .forEach(preliminary -> liveRows.add(preliminaryCard(++rank[0], preliminary)));
        // Everyone still to be measured, below the measured: the lap column names its whole
        // field from the start, and rows turn into cards as their numbers arrive.
        for (String id : lapArmsTotal.keySet()) {
            if (!liveScores.containsKey(id)) {
                liveRows.add(pendingRow(++rank[0], id));
            }
        }
        liveRows.revalidate();
        liveRows.repaint();
    }

    /** Where a candidate stands in this lap, for its chip: queued, testing, arm counts, done. */
    private String candidateState(String id) {
        if (lapFailed.contains(id)) {
            return "failed";
        }
        if (id.equals(runningCandidate)) {
            return "testing…";
        }
        int total = lapArmsTotal.getOrDefault(id, 0);
        int done = lapArmsDone.getOrDefault(id, 0);
        if (total > 0 && done >= total) {
            return "done";
        }
        if (done > 0) {
            return done + "/" + total + " arms";
        }
        return "queued";
    }

    private JLabel stateChip(String id) {
        String state = candidateState(id);
        Color colour =
                switch (state) {
                    case "failed" -> Theme.BAD;
                    case "testing…" -> Theme.ACCENT;
                    case "done" -> Theme.GOOD;
                    default -> Theme.MUTED;
                };
        JLabel chip = Theme.small(state);
        chip.setForeground(colour);
        return chip;
    }

    /** A candidate the lap has not measured yet: its name and where it stands, card-shaped. */
    private JPanel pendingRow(int rank, String id) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBackground(Theme.RAISED);
        row.setBorder(
                javax.swing.BorderFactory.createCompoundBorder(
                        new Theme.RoundedBorder(Theme.LINE, 8),
                        javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel name = new JLabel(rank + ".  " + display(id));
        name.setForeground(Theme.TEXT);
        name.setFont(name.getFont().deriveFont(Font.PLAIN, 13f));
        name.setToolTipText(name.getText());
        name.setMinimumSize(new Dimension(0, 0));
        row.add(name, BorderLayout.CENTER);
        row.add(stateChip(id), BorderLayout.EAST);
        return fullWidthRow(row);
    }

    /**
     * A candidate's running aggregate mid-round: the same grid the finished card will carry, from
     * every arm of this candidate measured so far, suffixed "so far" because none of it has an
     * interval yet.
     */
    private JPanel preliminaryCard(int rank, ExperimentListener.Preliminary preliminary) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Theme.RAISED);
        card.setBorder(
                javax.swing.BorderFactory.createCompoundBorder(
                        new Theme.RoundedBorder(Theme.LINE, 8),
                        javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        boolean expanded = expandedLive.contains(preliminary.id());
        JPanel headline = new JPanel(new BorderLayout(8, 0));
        headline.setOpaque(false);
        headline.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel chevron = Theme.small(expanded ? "▾" : "▸");
        JLabel name = new JLabel(rank + ".  " + display(preliminary.id()));
        name.setForeground(Theme.TEXT);
        name.setFont(name.getFont().deriveFont(Font.PLAIN, 13f));
        JPanel title = titleRow(chevron, name, stateChip(preliminary.id()));
        double percent = preliminary.improvementPercent();
        JLabel scoreLabel =
                Theme.mono(
                        String.format(Locale.ROOT, "%+.1f%%  so far", percent),
                        percent > 0 ? Theme.GOOD : percent < 0 ? Theme.BAD : Theme.MUTED);
        headline.add(title, BorderLayout.CENTER);
        headline.add(scoreLabel, BorderLayout.EAST);
        card.add(headline);
        card.add(preliminaryGrid(preliminary));

        JPanel details = new ExpandingPanel();
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setOpaque(false);
        details.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 0, 0, 0));
        details.setVisible(expanded);
        preliminary
                .scenarios()
                .forEach(
                        (scenarioId, stats) ->
                                details.add(
                                        preliminaryScenarioSection(
                                                preliminary.id(), scenarioId, stats)));
        card.add(details);
        wireExpander(
                headline,
                chevron,
                details,
                show -> {
                    if (show) {
                        expandedLive.add(preliminary.id());
                    } else {
                        expandedLive.remove(preliminary.id());
                    }
                });

        return fullWidthRow(card);
    }

    /** One scenario streaming in: open by default inside an open card, collapsible by click. */
    private JPanel preliminaryScenarioSection(
            String candidateId, String scenarioId, ExperimentListener.PreliminaryScenario stats) {
        String key = candidateId + "|" + scenarioId;
        boolean open = !collapsedLiveScenarios.contains(key);

        JPanel section = new ExpandingPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setOpaque(false);
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel chevron = Theme.small(open ? "▾" : "▸");
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel title = titleRow(chevron, Theme.small(scenarioId), null);
        header.add(title, BorderLayout.CENTER);
        header.add(
                Theme.mono(
                        String.format(Locale.ROOT, "%+.1f%%", stats.improvementPercent()),
                        direction(stats.improvementPercent(), false)),
                BorderLayout.EAST);
        header.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));

        JPanel body = new ExpandingPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 16, 4, 0));
        body.setVisible(open);
        body.add(
                statRow(
                        "improvement so far",
                        String.format(Locale.ROOT, "%+.1f%%", stats.improvementPercent()),
                        direction(stats.improvementPercent(), false)));
        body.add(
                statRow(
                        "arms measured",
                        stats.candidateArms() + " vs " + stats.baselineArms() + " baseline",
                        Theme.TEXT));
        if (stats.msptDelta() != null) {
            body.add(
                    statRow(
                            "mspt vs baseline",
                            String.format(Locale.ROOT, "%+.1f", stats.msptDelta()),
                            direction(stats.msptDelta(), true)));
        }
        if (stats.fpsDelta() != null) {
            body.add(
                    statRow(
                            "fps vs baseline",
                            String.format(Locale.ROOT, "%+.0f", stats.fpsDelta()),
                            direction(stats.fpsDelta(), false)));
        }
        if (stats.msPerChunkDelta() != null) {
            body.add(
                    statRow(
                            "ms/chunk vs baseline",
                            String.format(Locale.ROOT, "%+.2f", stats.msPerChunkDelta()),
                            direction(stats.msPerChunkDelta(), true)));
        }

        section.add(header);
        section.add(body);
        wireExpander(
                header,
                chevron,
                body,
                show -> {
                    if (show) {
                        collapsedLiveScenarios.remove(key);
                    } else {
                        collapsedLiveScenarios.add(key);
                    }
                });
        return section;
    }

    /**
     * A card's title line: chevron, name, optional trailing chip.
     *
     * <p>The name sits in the stretchy slot, so a long mod name is ellipsised to the card width
     * instead of widening the card past its column -- where it took the stat columns with it and
     * pushed them out of sight behind whatever the column overlapped.
     */
    private static JPanel titleRow(JLabel chevron, JLabel name, javax.swing.JComponent trailing) {
        JPanel title = new JPanel(new BorderLayout(4, 0));
        title.setOpaque(false);
        // Truncated on screen, whole in the tooltip: the name is still identity.
        name.setToolTipText(name.getText());
        name.setMinimumSize(new Dimension(0, 0));
        title.add(chevron, BorderLayout.WEST);
        title.add(name, BorderLayout.CENTER);
        if (trailing != null) {
            title.add(trailing, BorderLayout.EAST);
        }
        return title;
    }

    /**
     * A scroll view that is never wider than its viewport.
     *
     * <p>Without this a scroll pane sizes its view to the widest child preferred width, so one
     * long name made the whole column wider than the window and everything past the edge simply
     * disappeared. Tracking the viewport hands cards a real width to truncate against.
     */
    private static final class ColumnView extends JPanel implements javax.swing.Scrollable {
        ColumnView() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle view, int axis, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle view, int axis, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** One header-over-value column of a card's stats table. */
    private record StatColumn(String header, String tooltip, String value, Color colour) {}

    /**
     * The card's numbers as a table where each column takes the width it needs.
     *
     * <p>Not a GridLayout: that sizes every column to the widest one, and five equal columns in a
     * 300px card leave ~35px each — every value ellipsised to "…", a grid of dots. Natural widths
     * fit the same five columns with room to spare.
     */
    private static JPanel statsTable(List<StatColumn> statColumns) {
        JPanel grid = new JPanel(new java.awt.GridBagLayout());
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        var constraints = new java.awt.GridBagConstraints();
        constraints.anchor = java.awt.GridBagConstraints.WEST;
        // Every column shares the surplus equally, so the table spans the card instead of
        // huddling at its preferred width -- a clipped huddle, once the card was narrower.
        constraints.weightx = 1;
        for (int i = 0; i < statColumns.size(); i++) {
            StatColumn column = statColumns.get(i);
            constraints.gridx = i;
            constraints.insets = new java.awt.Insets(0, i == 0 ? 0 : 10, 0, 0);
            constraints.gridy = 0;
            JLabel header = Theme.small(column.header());
            header.setToolTipText(column.tooltip());
            grid.add(header, constraints);
            constraints.gridy = 1;
            JLabel value = Theme.mono(column.value(), column.colour());
            value.setToolTipText(column.tooltip());
            grid.add(value, constraints);
        }
        // Full width, own height: the card's BoxLayout centres and clips a child that claims a
        // fixed width wider than the card, which read as a mysterious left indent.
        grid.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
        return grid;
    }

    /** The finished card's grid shape, fed from the running aggregates. */
    private JPanel preliminaryGrid(ExperimentListener.Preliminary preliminary) {
        List<StatColumn> gridColumns = new ArrayList<>();
        String soFar = ", aggregated over this candidate's arms so far; no interval yet";
        if (preliminary.msptDelta() != null) {
            gridColumns.add(
                    new StatColumn(
                            "mspt",
                            "server mean tick time, delta vs baseline" + soFar,
                            String.format(Locale.ROOT, "%+.1f", preliminary.msptDelta()),
                            direction(preliminary.msptDelta(), true)));
        }
        if (preliminary.fpsDelta() != null) {
            gridColumns.add(
                    new StatColumn(
                            "fps",
                            "mean framerate, delta vs baseline" + soFar,
                            String.format(Locale.ROOT, "%+.0f", preliminary.fpsDelta()),
                            direction(preliminary.fpsDelta(), false)));
        }
        if (preliminary.msPerChunkDelta() != null) {
            gridColumns.add(
                    new StatColumn(
                            "ms/ch",
                            "time per chunk received, delta vs baseline" + soFar,
                            String.format(Locale.ROOT, "%+.2f", preliminary.msPerChunkDelta()),
                            direction(preliminary.msPerChunkDelta(), true)));
        }
        preliminary
                .scenarios()
                .forEach(
                        (scenarioId, stats) ->
                                gridColumns.add(
                                        new StatColumn(
                                                abbreviate(scenarioId),
                                                scenarioId
                                                        + ": scored improvement vs baseline"
                                                        + soFar,
                                                String.format(
                                                        Locale.ROOT,
                                                        "%+.1f%%",
                                                        stats.improvementPercent()),
                                                direction(stats.improvementPercent(), false))));

        return statsTable(gridColumns);
    }

    /**
     * Closes the round's column with one card per candidate: the score, the metric deltas, the
     * vs-original row from round 2 on, and the per-scenario evidence beneath.
     */
    private void finishColumn(
            List<Comparison> comparisons, List<CandidateScore> scores, String promoted) {
        if (liveRows == null) {
            return;
        }
        liveRows.removeAll();
        scores.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .forEach(
                        score ->
                                liveRows.add(
                                        candidateCard(
                                                score,
                                                comparisons.stream()
                                                        .filter(
                                                                c ->
                                                                        c.candidateId()
                                                                                .equals(score.id()))
                                                        .toList(),
                                                score.id().equals(promoted))));
        liveColumn = null;
        liveRows = null;
    }

    /**
     * One candidate's round, led by the score because the score is what ranks it. The card
     * expands from its headline into per-scenario sections, each expanding again into the full
     * stat list — three depths of the same answer, each one click apart.
     */
    private JPanel candidateCard(CandidateScore score, List<Comparison> comparisons, boolean winner) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(winner ? new Color(0x17321F) : Theme.RAISED);
        card.setBorder(
                javax.swing.BorderFactory.createCompoundBorder(
                        new Theme.RoundedBorder(winner ? Theme.GOOD : Theme.LINE, 8),
                        javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel headline = new JPanel(new BorderLayout(8, 0));
        headline.setOpaque(false);
        headline.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel chevron = Theme.small("▸");
        JLabel name = new JLabel((winner ? "★ " : "") + display(score.id()));
        name.setForeground(winner ? Theme.GOOD : Theme.TEXT);
        name.setFont(name.getFont().deriveFont(winner ? Font.BOLD : Font.PLAIN, 13f));
        JPanel title = titleRow(chevron, name, null);
        JLabel scoreLabel =
                Theme.mono(
                        String.format(Locale.ROOT, "%+.1f", score.score()),
                        score.score() > 0 ? Theme.GOOD : score.score() < 0 ? Theme.BAD : Theme.MUTED);
        scoreLabel.setFont(scoreLabel.getFont().deriveFont(Font.BOLD, 13f));
        headline.add(title, BorderLayout.CENTER);
        headline.add(scoreLabel, BorderLayout.EAST);
        card.add(headline);

        card.add(statsGrid(score, comparisons));

        if (score.vsOriginalPercent() != null) {
            JLabel vsOriginal =
                    Theme.mono(
                            String.format(
                                    Locale.ROOT,
                                    "vs original baseline  %+.1f%%",
                                    score.vsOriginalPercent()),
                            score.vsOriginalPercent() > 0 ? Theme.GOOD : Theme.BAD);
            vsOriginal.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(vsOriginal);
        }
        JLabel verdict = Theme.small(score.verdict());
        verdict.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(verdict);

        JPanel details = new ExpandingPanel();
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setOpaque(false);
        details.setAlignmentX(Component.LEFT_ALIGNMENT);
        details.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 0, 0, 0));
        details.setVisible(false);
        if (comparisons.isEmpty()) {
            JLabel none = Theme.small("no per-scenario comparison; a scenario needs 2+ arms");
            none.setAlignmentX(Component.LEFT_ALIGNMENT);
            details.add(none);
        }
        for (Comparison comparison :
                comparisons.stream()
                        .sorted(java.util.Comparator.comparing(Comparison::scenarioId))
                        .toList()) {
            details.add(
                    scenarioSection(
                            comparison, score.scenarioChannels().get(comparison.scenarioId())));
        }
        card.add(details);
        wireExpander(headline, chevron, details);

        return fullWidthRow(card);
    }

    /** One scenario's stats, behind its own header: id and headline percent, then the list. */
    private JPanel scenarioSection(
            Comparison comparison, ExperimentListener.ScenarioChannels channels) {
        JPanel section = new ExpandingPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setOpaque(false);
        section.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel chevron = Theme.small("▸");
        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel title = titleRow(chevron, Theme.small(comparison.scenarioId()), null);
        header.add(title, BorderLayout.CENTER);
        header.add(
                Theme.mono(
                        String.format(Locale.ROOT, "%+.1f%%", comparison.improvementPercent()),
                        bandColour(comparison)),
                BorderLayout.EAST);
        header.setMaximumSize(
                new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));

        // The stat list: label left, value right, one fact per line — a reading list, not the
        // at-a-glance grid above it.
        JPanel body = new ExpandingPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 16, 4, 0));
        body.setVisible(false);
        body.add(
                statRow(
                        "improvement",
                        String.format(Locale.ROOT, "%+.1f%%", comparison.improvementPercent()),
                        bandColour(comparison)));
        // The comparison's interval is change (negative is faster); shown as improvement, so the
        // ends swap and flip sign.
        body.add(
                statRow(
                        "95% interval",
                        String.format(
                                Locale.ROOT,
                                "%+.1f%% … %+.1f%%",
                                -comparison.highPercent(),
                                -comparison.lowPercent()),
                        Theme.MUTED));
        body.add(
                statRow(
                        "verdict",
                        comparison.band().toString().toLowerCase(Locale.ROOT).replace('_', ' '),
                        bandColour(comparison)));
        body.add(statRow("pairs", Integer.toString(comparison.pairs()), Theme.TEXT));
        if (comparison.voided() > 0) {
            body.add(statRow("voided", Integer.toString(comparison.voided()), Theme.BAD));
        }
        body.add(
                statRow(
                        "noise floor",
                        String.format(Locale.ROOT, "%.1f%%", comparison.floorPercent()),
                        Theme.MUTED));
        if (channels != null) {
            if (channels.msptDelta() != null) {
                body.add(
                        statRow(
                                "mspt vs baseline",
                                String.format(Locale.ROOT, "%+.1f", channels.msptDelta()),
                                direction(channels.msptDelta(), true)));
            }
            if (channels.fpsDelta() != null) {
                body.add(
                        statRow(
                                "fps vs baseline",
                                String.format(Locale.ROOT, "%+.0f", channels.fpsDelta()),
                                direction(channels.fpsDelta(), false)));
            }
            if (channels.msPerChunkDelta() != null) {
                body.add(
                        statRow(
                                "ms/chunk vs baseline",
                                String.format(Locale.ROOT, "%+.2f", channels.msPerChunkDelta()),
                                direction(channels.msPerChunkDelta(), true)));
            }
        }

        section.add(header);
        section.add(body);
        wireExpander(header, chevron, body);
        return section;
    }

    /** One fact: its name on the left edge, its value on the right, nothing between. */
    private static JPanel statRow(String label, String value, Color colour) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(Theme.small(label), BorderLayout.WEST);
        JLabel valueLabel = Theme.mono(value, colour);
        row.add(valueLabel, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** Click the header, toggle the body; the chevron says which way it stands. */
    private static void wireExpander(
            javax.swing.JComponent header, JLabel chevron, javax.swing.JComponent body) {
        wireExpander(header, chevron, body, unused -> {});
    }

    /** @param onToggle told the new state, for cards that remember it across rebuilds */
    private static void wireExpander(
            javax.swing.JComponent header,
            JLabel chevron,
            javax.swing.JComponent body,
            java.util.function.Consumer<Boolean> onToggle) {
        header.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        header.addMouseListener(
                new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent unused) {
                        boolean show = !body.isVisible();
                        body.setVisible(show);
                        chevron.setText(show ? "▾" : "▸");
                        onToggle.accept(show);
                        body.getParent().revalidate();
                        body.getParent().repaint();
                    }
                });
    }

    /**
     * A panel whose maximum height tracks its <em>current</em> preferred height, so expanding a
     * child actually grows it — a maximum captured once at build time would pin the card at its
     * collapsed size forever.
     */
    // Package-private so the card-truncation test can find the collapsibles and open them.
    static class ExpandingPanel extends JPanel {
        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Full column width, own height — recomputed live, so expansion can grow the row. */
    private static JPanel fullWidthRow(JPanel card) {
        JPanel spacer = new ExpandingPanel();
        spacer.setLayout(new BorderLayout());
        spacer.setOpaque(false);
        spacer.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 6, 0));
        spacer.add(card, BorderLayout.CENTER);
        spacer.setAlignmentX(Component.LEFT_ALIGNMENT);
        return spacer;
    }

    /**
     * The card's numbers as an actual grid: one column per metric and per scenario, headers over
     * values, everything aligned. A flowed line of "mspt −0.8 fps +330" reads token by token; a
     * grid reads at a glance and compares down a column across cards.
     */
    private JPanel statsGrid(CandidateScore score, List<Comparison> comparisons) {
        List<StatColumn> columns = new ArrayList<>();

        // Lower is better for mspt and ms/chunk, higher for fps: green means "moved the way you
        // want", not "went up".
        if (score.msptDelta() != null) {
            columns.add(
                    new StatColumn(
                            "mspt",
                            "server mean tick time, delta vs baseline",
                            String.format(Locale.ROOT, "%+.1f", score.msptDelta()),
                            direction(score.msptDelta(), true)));
        }
        if (score.fpsDelta() != null) {
            columns.add(
                    new StatColumn(
                            "fps",
                            "mean framerate, delta vs baseline",
                            String.format(Locale.ROOT, "%+.0f", score.fpsDelta()),
                            direction(score.fpsDelta(), false)));
        }
        if (score.msPerChunkDelta() != null) {
            columns.add(
                    new StatColumn(
                            "ms/ch",
                            "time per chunk received, delta vs baseline",
                            String.format(Locale.ROOT, "%+.2f", score.msPerChunkDelta()),
                            direction(score.msPerChunkDelta(), true)));
        }
        for (Comparison comparison :
                comparisons.stream()
                        .sorted(java.util.Comparator.comparing(Comparison::scenarioId))
                        .toList()) {
            columns.add(
                    new StatColumn(
                            abbreviate(comparison.scenarioId()),
                            comparison.scenarioId() + ": " + comparison.describe(),
                            String.format(
                                    Locale.ROOT, "%+.1f%%", comparison.improvementPercent()),
                            bandColour(comparison)));
        }

        return statsTable(columns);
    }

    private static Color direction(double delta, boolean lowerIsBetter) {
        if (delta == 0) {
            return Theme.MUTED;
        }
        return (lowerIsBetter ? delta < 0 : delta > 0) ? Theme.GOOD : Theme.BAD;
    }

    /** The band decides the colour, because the band is what says the percentage is real. */
    private static Color bandColour(Comparison comparison) {
        return switch (comparison.band()) {
            case IMPROVED -> Theme.GOOD;
            case REGRESSED -> Theme.BAD;
            case NEGLIGIBLE, NO_MEASURABLE_DIFFERENCE -> Theme.MUTED;
        };
    }

    /**
     * "chunk-generation" → "chgn": per hyphenated word, its first letter and the next consonant.
     * A column header has no room for the full id; the tooltip carries it.
     */
    private static String abbreviate(String scenarioId) {
        StringBuilder out = new StringBuilder();
        for (String word : scenarioId.split("[-_]")) {
            if (word.isEmpty()) {
                continue;
            }
            out.append(word.charAt(0));
            for (int i = 1; i < word.length(); i++) {
                char c = Character.toLowerCase(word.charAt(i));
                if ("aeiou".indexOf(c) < 0) {
                    out.append(word.charAt(i));
                    break;
                }
            }
        }
        return out.toString();
    }

    private static String band(Comparison comparison) {
        return comparison.band().toString().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /**
     * One candidate's entry inside a column: rank and name, then what it measured beneath.
     *
     * <p>Two lines rather than one. A band is several words long, and a band is the part that says
     * whether the percentage above it means anything — so it is the line that must not be the one
     * truncated to fit.
     */
    private static JPanel resultRow(int rank, String name, String value, boolean winner) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setBackground(winner ? new Color(0x17321F) : Theme.RAISED);
        row.setBorder(
                javax.swing.BorderFactory.createCompoundBorder(
                        new Theme.RoundedBorder(winner ? Theme.GOOD : Theme.LINE, 8),
                        javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));

        JLabel title = new JLabel((winner ? "★ " : rank + ".  ") + name);
        title.setForeground(winner ? Theme.GOOD : Theme.TEXT);
        title.setFont(title.getFont().deriveFont(winner ? Font.BOLD : Font.PLAIN, 12f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel detail = Theme.mono(value, winner ? Theme.GOOD : Theme.MUTED);
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);

        row.add(title);
        row.add(Box.createVerticalStrut(2));
        row.add(detail);
        return row;
    }

    private void refresh() {
        frame.revalidate();
        frame.repaint();
    }
}
