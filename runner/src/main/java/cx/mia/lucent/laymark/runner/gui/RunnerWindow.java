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
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * The runner's window: progress, what is running right now, the schedule, the selection grid, and
 * the log.
 *
 * <p>Observes and controls — it never configures. Arguments decide what the run is; this shows what
 * it is doing and lets an operator hold or end it. So there is nothing here that adds, reorders or
 * re-sorts an arm: those come from the command line, and a control that appeared to change them
 * would be lying.
 *
 * <p>It also shows no summary statistics for the run in flight. No single number says whether a
 * candidate is really an improvement — that is what the paired comparison in the selection grid is
 * for, and a headline mean beside it would be read as the answer.
 *
 * <p>Swing because it ships in the JDK: the runner stays one shaded jar with no toolkit dependency,
 * and the GUI runs in the runner's process, never the game's, so it cannot sit beneath a published
 * number.
 *
 * <p>All listener callbacks arrive on the experiment thread and are marshalled to the EDT here; the
 * experiment never waits on this class.
 */
public final class RunnerWindow implements ExperimentListener {

    private static final Color RUNNING = new Color(0x2F6FDE);
    private static final Color DONE = new Color(0x2E7D32);
    private static final Color FAILED = new Color(0xC62828);
    private static final Color WINNER = new Color(0x1B5E20);
    private static final Color MUTED = new Color(0x6B6B6B);

    /** Kept bounded: a long run prints more than a text area should hold. */
    private static final int LOG_LINE_LIMIT = 4000;

    private final RunControl control;
    private final JFrame frame = new JFrame("Laymark");
    private final JLabel status = new JLabel("starting");
    private final JLabel progress = new JLabel("");
    private final JLabel elapsed = new JLabel("0:00");
    private final JLabel remaining = new JLabel("");
    private final JButton pauseButton = new JButton("Pause");
    private final JButton stopButton = new JButton("Stop");

    private final JLabel currentArm = new JLabel("—");
    private final JLabel currentBaseline = new JLabel("—");
    private final JLabel currentScenario = new JLabel("—");

    private final JPanel schedulePanel = new JPanel();
    private final JPanel gridPanel = new JPanel();
    private final JTextArea log = new JTextArea();

    private final List<JLabel> scheduleRows = new ArrayList<>();
    private final Map<String, Double> liveScores = new LinkedHashMap<>();
    private JPanel liveColumn;
    private String baselineLabel = "baseline";
    private Set<String> baselineMods = Set.of();

    private final long startedAt = System.currentTimeMillis();
    private long armStartedAt;
    private long armMillisTotal;
    private int armsFinished;
    private int armsTotal;
    private int round = 1;
    private int rounds = 1;

    private Timer clock;
    private boolean paused;

    private RunnerWindow(RunControl control) {
        this.control = control;
        build();
    }

    /** Opens the window and returns the listener the experiment should report to. */
    public static RunnerWindow open(RunControl control) {
        RunnerWindow window = new RunnerWindow(control);
        SwingUtilities.invokeLater(() -> window.frame.setVisible(true));
        return window;
    }

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

    private void build() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        status.setFont(status.getFont().deriveFont(Font.BOLD));
        remaining.setForeground(MUTED);
        top.add(status);
        top.add(progress);
        top.add(elapsed);
        top.add(remaining);
        top.add(pauseButton);
        top.add(stopButton);

        pauseButton.addActionListener(unused -> togglePause());
        stopButton.addActionListener(unused -> confirmStop());

        JPanel header = new JPanel(new BorderLayout());
        header.add(top, BorderLayout.NORTH);
        header.add(nowRunning(), BorderLayout.CENTER);

        schedulePanel.setLayout(new BoxLayout(schedulePanel, BoxLayout.Y_AXIS));
        schedulePanel.setBorder(BorderFactory.createTitledBorder("Schedule"));
        JScrollPane scheduleScroll = new JScrollPane(schedulePanel);
        scheduleScroll.setPreferredSize(new Dimension(240, 400));

        gridPanel.setLayout(new BoxLayout(gridPanel, BoxLayout.X_AXIS));
        gridPanel.setBorder(BorderFactory.createTitledBorder("Selection"));

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));

        JSplitPane centre =
                new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(gridPanel), logScroll);
        centre.setResizeWeight(0.55);

        frame.setLayout(new BorderLayout());
        frame.add(header, BorderLayout.NORTH);
        frame.add(scheduleScroll, BorderLayout.WEST);
        frame.add(centre, BorderLayout.CENTER);
        frame.setSize(1020, 680);
        frame.setLocationByPlatform(true);

        // Closing the window is a stop, not an escape: a run left headless by accident would keep
        // the machine busy with no way to reach it.
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(
                new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent unused) {
                        confirmStop();
                    }
                });

        clock =
                new Timer(
                        1000,
                        unused -> {
                            elapsed.setText(clock(System.currentTimeMillis() - startedAt));
                            updateEstimate();
                        });
        clock.start();
    }

    /**
     * What is being measured at this moment: the arm, what it is measured against, and the scenario
     * in flight. Three facts, because those are the three that decide whether the number arriving
     * next means anything.
     */
    private JPanel nowRunning() {
        JPanel panel = new JPanel(new GridLayout(3, 1, 0, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Now running"));
        panel.add(field("arm", currentArm));
        panel.add(field("against", currentBaseline));
        panel.add(field("scenario", currentScenario));
        return panel;
    }

    private static JPanel field(String name, JLabel value) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel label = new JLabel(name);
        label.setForeground(MUTED);
        label.setPreferredSize(new Dimension(64, 16));
        value.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        row.add(label);
        row.add(value);
        return row;
    }

    private void togglePause() {
        if (paused) {
            control.resume();
            paused = false;
            pauseButton.setText("Pause");
            status.setText("running");
        } else {
            control.pause();
            paused = true;
            pauseButton.setText("Resume");
            // Honest about the semantics: the hold happens at the boundary, not now.
            status.setText("pausing after the current run");
        }
    }

    private void confirmStop() {
        int choice =
                JOptionPane.showConfirmDialog(
                        frame,
                        "Stop the run? The current game is killed, the instance restored, and the"
                                + " report written from the runs that completed.",
                        "Stop",
                        JOptionPane.OK_CANCEL_OPTION);
        if (choice == JOptionPane.OK_OPTION) {
            control.stop();
            status.setText("stopping");
            pauseButton.setEnabled(false);
            stopButton.setEnabled(false);
        }
    }

    // --- listener side, marshalled to the EDT ---

    @Override
    public void scheduleBuilt(Slate slate) {
        SwingUtilities.invokeLater(
                () -> {
                    schedulePanel.removeAll();
                    scheduleRows.clear();
                    armsTotal = slate.armsTotal();
                    round = slate.round();
                    rounds = slate.rounds();
                    for (int i = 0; i < slate.arms().size(); i++) {
                        Arm arm = slate.arms().get(i);
                        JLabel row =
                                new JLabel(
                                        String.format(
                                                Locale.ROOT,
                                                "%3d  %-20s %s",
                                                i + 1,
                                                arm.id(),
                                                arm.kind().toString().toLowerCase(Locale.ROOT)));
                        row.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                        scheduleRows.add(row);
                        schedulePanel.add(row);
                        if (arm.kind() == Arm.Kind.BASELINE && baselineMods.isEmpty()) {
                            // The reference set the "arm" line describes candidates against.
                            baselineMods = Set.copyOf(arm.enabled());
                        }
                    }
                    updateProgress();
                    startColumn("baseline");
                    refresh();
                });
    }

    @Override
    public void runStarted(int sequence, Arm arm) {
        SwingUtilities.invokeLater(
                () -> {
                    armStartedAt = System.currentTimeMillis();
                    mark(sequence, RUNNING, Font.BOLD);
                    status.setText("running");
                    currentArm.setText(describe(arm));
                    currentBaseline.setText(
                            arm.kind() == Arm.Kind.CANDIDATE ? baselineLabel : "— (reference run)");
                    currentScenario.setText("starting the game");
                    updateProgress();
                    refresh();
                });
    }

    @Override
    public void scenarioStarted(String scenarioId, int repetition) {
        SwingUtilities.invokeLater(
                () -> currentScenario.setText(scenarioId + "  #" + repetition));
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
                    mark(sequence, failed ? FAILED : DONE, Font.PLAIN);
                    currentScenario.setText("—");
                    if (!failed && arm.kind() == Arm.Kind.CANDIDATE) {
                        // Provisional, in raw milliseconds; the round's close replaces it with the
                        // paired percentage, which is the number that actually means something.
                        liveScores.merge(arm.id(), scoredMillis, (a, b) -> (a + b) / 2);
                        renderLiveColumn();
                    }
                    updateProgress();
                    updateEstimate();
                    refresh();
                });
    }

    @Override
    public void roundCompleted(
            int roundNumber, String baseline, List<Comparison> comparisons, String promoted) {
        SwingUtilities.invokeLater(
                () -> {
                    finishColumn(comparisons, promoted);
                    if (promoted != null) {
                        startColumn(baseline + " + " + promoted);
                    }
                    liveScores.clear();
                    refresh();
                });
    }

    @Override
    public void stateChanged(String state) {
        SwingUtilities.invokeLater(
                () -> {
                    if (!paused || "running".equals(state)) {
                        status.setText(state);
                    }
                });
    }

    @Override
    public void finished(SelectionReport report) {
        SwingUtilities.invokeLater(
                () -> {
                    status.setText(report == null ? "stopped" : "finished");
                    currentArm.setText("—");
                    currentBaseline.setText("—");
                    currentScenario.setText("—");
                    remaining.setText("");
                    clock.stop();
                    pauseButton.setEnabled(false);
                    stopButton.setEnabled(false);
                    // The window stays open: the grid, schedule and log are the summary an operator
                    // came back for, and closing now is a plain close.
                    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
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
     * Extrapolated from the arms that have finished, which is the only evidence there is. Blank
     * until one has, rather than guessing from nothing.
     */
    private void updateEstimate() {
        int left = Math.max(armsTotal, armsFinished) - armsFinished;
        if (armsFinished == 0 || left <= 0) {
            remaining.setText("");
            return;
        }
        long perArm = armMillisTotal / armsFinished;
        long inFlight = armStartedAt == 0 ? 0 : System.currentTimeMillis() - armStartedAt;
        remaining.setText("~" + clock(Math.max(0, (long) left * perArm - inFlight)) + " left");
    }

    private static String clock(long millis) {
        long seconds = millis / 1000;
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** An arm named by what it changes, since its id alone rarely says what is being tried. */
    private String describe(Arm arm) {
        Set<String> added = new TreeSet<>(arm.enabled());
        added.removeAll(baselineMods);
        Set<String> removed = new TreeSet<>(baselineMods);
        removed.removeAll(arm.enabled());
        if (added.isEmpty() && removed.isEmpty()) {
            return arm.id() + "  (identical to the baseline stack)";
        }
        StringBuilder text = new StringBuilder(arm.id()).append("  ");
        added.forEach(mod -> text.append('+').append(mod).append(' '));
        removed.forEach(mod -> text.append('-').append(mod).append(' '));
        return text.toString().trim();
    }

    // --- grid columns ---

    private void startColumn(String baseline) {
        baselineLabel = baseline;
        liveColumn = new JPanel();
        liveColumn.setLayout(new BoxLayout(liveColumn, BoxLayout.Y_AXIS));
        liveColumn.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        liveColumn.setAlignmentY(Component.TOP_ALIGNMENT);

        JLabel header = new JLabel(baseline);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        liveColumn.add(header);
        liveColumn.add(Box.createVerticalStrut(6));
        gridPanel.add(liveColumn);
    }

    private void renderLiveColumn() {
        if (liveColumn == null) {
            return;
        }
        // Header plus strut survive; rows are rebuilt sorted, lower milliseconds first.
        while (liveColumn.getComponentCount() > 2) {
            liveColumn.remove(2);
        }
        liveScores.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(
                        entry ->
                                liveColumn.add(
                                        monospaced(
                                                String.format(
                                                        Locale.ROOT,
                                                        "%-18s %8.2fms",
                                                        entry.getKey(),
                                                        entry.getValue()),
                                                null)));
    }

    private void finishColumn(List<Comparison> comparisons, String promoted) {
        if (liveColumn == null) {
            return;
        }
        while (liveColumn.getComponentCount() > 2) {
            liveColumn.remove(2);
        }
        comparisons.stream()
                .sorted((a, b) -> Double.compare(b.improvementPercent(), a.improvementPercent()))
                .forEach(
                        comparison -> {
                            boolean winner = comparison.candidateId().equals(promoted);
                            liveColumn.add(
                                    monospaced(
                                            String.format(
                                                    Locale.ROOT,
                                                    "%s%-16s %+6.1f%%  %s",
                                                    winner ? "★ " : "  ",
                                                    comparison.candidateId(),
                                                    comparison.improvementPercent(),
                                                    comparison.band()),
                                            winner ? WINNER : null));
                        });
        liveColumn = null;
    }

    private static JLabel monospaced(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        if (color != null) {
            label.setForeground(color);
            label.setFont(label.getFont().deriveFont(Font.BOLD));
        }
        return label;
    }

    private void mark(int sequence, Color color, int style) {
        if (sequence < scheduleRows.size()) {
            JLabel row = scheduleRows.get(sequence);
            row.setForeground(color);
            row.setFont(row.getFont().deriveFont(style));
        }
    }

    private void refresh() {
        frame.revalidate();
        frame.repaint();
    }
}
