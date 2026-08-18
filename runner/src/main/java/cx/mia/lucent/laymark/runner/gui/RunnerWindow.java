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
    private final Map<String, Double> liveScores = new LinkedHashMap<>();
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
        // a run finishes.
        planning.setPreferredSize(new Dimension(430, 0));

        JPanel body = new JPanel(new BorderLayout(12, 0));
        body.setBackground(Theme.BACKGROUND);
        body.add(planning, BorderLayout.WEST);
        body.add(runView(), BorderLayout.CENTER);

        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(Theme.BACKGROUND);
        frame.add(topBar(), BorderLayout.NORTH);
        frame.add(body, BorderLayout.CENTER);
        frame.setSize(1500, 900);
        frame.setMinimumSize(new Dimension(900, 620));
        frame.setLocationByPlatform(true);

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
    }

    private JPanel topBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 12));
        bar.setBackground(Theme.CARD);
        bar.setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE));

        startButton.addActionListener(unused -> onStartOrPause());
        stopButton.addActionListener(unused -> confirmStop());
        stopButton.setEnabled(false);
        status.setForeground(Theme.MUTED);

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

        JPanel bottom = new JPanel(new BorderLayout(0, 12));
        bottom.setOpaque(false);
        bottom.add(currentRunCard(), BorderLayout.NORTH);
        bottom.add(logCard(), BorderLayout.CENTER);
        bottom.setPreferredSize(new Dimension(0, 300));

        view.add(grid, BorderLayout.CENTER);
        view.add(bottom, BorderLayout.SOUTH);
        return view;
    }

    /**
     * The three facts that decide whether the number arriving next means anything: which arm, what
     * it is measured against, and which scenario is capturing.
     */
    private JPanel currentRunCard() {
        JPanel card = Theme.card("Current run");
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
        running = true;
        startedAt = System.currentTimeMillis();
        armMillisTotal = 0;
        armsFinished = 0;
        baselineLabel = "baseline";
        liveScores.clear();
        columns.removeAll();
        startButton.setText("Pause");
        stopButton.setEnabled(true);
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
                    for (Arm arm : slate.arms()) {
                        if (arm.kind() == Arm.Kind.CANDIDATE) {
                            planning.armStatus(arm.id(), "queued", Theme.MUTED);
                        }
                    }
                    startColumn(baselineLabel);
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
                    updateProgress();
                    updateEstimate();
                    refresh();
                });
    }

    @Override
    public void preliminaryScore(String id, double improvementPercent) {
        // A percent against the baseline measured so far -- a raw millisecond figure mid-round
        // read as a result while meaning nothing. Preliminary by name until the round closes.
        SwingUtilities.invokeLater(
                () -> {
                    liveScores.put(id, improvementPercent);
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
                    planning.setPlanningEnabled(true);
                });
    }

    // --- header readouts ---

    /** "18/28 arms in 4/7 runs" — arms are the launches, runs are the selection rounds. */
    private void updateProgress() {
        progress.setText(
                String.format(
                        Locale.ROOT,
                        "%d/%d arms in %d/%d runs",
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
            eta.setText("elapsed " + clock(elapsed));
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
        JLabel title = Theme.heading("Run " + round);
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
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(liveRows, BorderLayout.NORTH);

        liveColumn.add(header, BorderLayout.NORTH);
        liveColumn.add(holder, BorderLayout.CENTER);
        columns.add(liveColumn);
        columns.add(Box.createHorizontalStrut(10));
    }

    private void renderLiveColumn() {
        if (liveRows == null) {
            return;
        }
        liveRows.removeAll();
        int[] rank = {0};
        liveScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(
                        entry ->
                                liveRows.add(
                                        resultRow(
                                                ++rank[0],
                                                display(entry.getKey()),
                                                String.format(
                                                        Locale.ROOT,
                                                        "%+.1f%%  so far",
                                                        entry.getValue()),
                                                false)));
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

    /** One candidate's round, led by the score because the score is what ranks it. */
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
        JLabel name = new JLabel((winner ? "★ " : "") + display(score.id()));
        name.setForeground(winner ? Theme.GOOD : Theme.TEXT);
        name.setFont(name.getFont().deriveFont(winner ? Font.BOLD : Font.PLAIN, 13f));
        JLabel scoreLabel =
                Theme.mono(
                        String.format(Locale.ROOT, "%+.1f", score.score()),
                        score.score() > 0 ? Theme.GOOD : score.score() < 0 ? Theme.BAD : Theme.MUTED);
        scoreLabel.setFont(scoreLabel.getFont().deriveFont(Font.BOLD, 13f));
        headline.add(name, BorderLayout.WEST);
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

        JPanel spacer = new JPanel(new BorderLayout());
        spacer.setOpaque(false);
        spacer.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 6, 0));
        spacer.add(card, BorderLayout.CENTER);
        spacer.setAlignmentX(Component.LEFT_ALIGNMENT);
        spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, spacer.getPreferredSize().height));
        return spacer;
    }

    /**
     * The card's numbers as an actual grid: one column per metric and per scenario, headers over
     * values, everything aligned. A flowed line of "mspt −0.8 fps +330" reads token by token; a
     * grid reads at a glance and compares down a column across cards.
     */
    private JPanel statsGrid(CandidateScore score, List<Comparison> comparisons) {
        record Column(String header, String tooltip, String value, Color colour) {}
        List<Column> columns = new ArrayList<>();

        // Lower is better for mspt and ms/chunk, higher for fps: green means "moved the way you
        // want", not "went up".
        if (score.msptDelta() != null) {
            columns.add(
                    new Column(
                            "mspt",
                            "server mean tick time, delta vs baseline",
                            String.format(Locale.ROOT, "%+.1f", score.msptDelta()),
                            direction(score.msptDelta(), true)));
        }
        if (score.fpsDelta() != null) {
            columns.add(
                    new Column(
                            "fps",
                            "mean framerate, delta vs baseline",
                            String.format(Locale.ROOT, "%+.0f", score.fpsDelta()),
                            direction(score.fpsDelta(), false)));
        }
        if (score.msPerChunkDelta() != null) {
            columns.add(
                    new Column(
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
                    new Column(
                            abbreviate(comparison.scenarioId()),
                            comparison.scenarioId() + ": " + comparison.describe(),
                            String.format(
                                    Locale.ROOT, "%+.1f%%", comparison.improvementPercent()),
                            bandColour(comparison)));
        }

        JPanel grid = new JPanel(new java.awt.GridLayout(2, columns.size(), 12, 0));
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (Column column : columns) {
            JLabel header = Theme.small(column.header());
            header.setToolTipText(column.tooltip());
            grid.add(header);
        }
        for (Column column : columns) {
            JLabel value = Theme.mono(column.value(), column.colour());
            value.setToolTipText(column.tooltip());
            grid.add(value);
        }
        grid.setMaximumSize(grid.getPreferredSize());
        return grid;
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
