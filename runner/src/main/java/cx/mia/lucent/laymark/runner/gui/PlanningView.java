package cx.mia.lucent.laymark.runner.gui;

import cx.mia.lucent.laymark.core.Laymark;
import cx.mia.lucent.laymark.core.experiment.Schedule;
import cx.mia.lucent.laymark.core.scenario.ConfigCodec;
import cx.mia.lucent.laymark.core.scenario.ScenarioConfigFile;
import cx.mia.lucent.laymark.core.select.DependencyGraph;
import cx.mia.lucent.laymark.runner.select.JarProbe;
import cx.mia.lucent.laymark.runner.launch.InstalledVersion;
import cx.mia.lucent.laymark.runner.launch.ModrinthInstance;
import cx.mia.lucent.laymark.runner.materialize.InlayIndex;
import cx.mia.lucent.laymark.runner.materialize.ModsDirectory;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;

/**
 * What to benchmark, chosen before anything launches.
 *
 * <p>The one place Laymark's GUI configures rather than observes, and it exists so the runner can
 * be opened by double-clicking the jar. Everything here has a command-line equivalent; the run it
 * produces is the run those arguments would have produced.
 *
 * <p>Candidates are the mods currently installed and enabled. A checked mod becomes a candidate —
 * an arm of the baseline plus that mod — and the baseline is the stack with every candidate
 * withheld, so the comparison is "with it" against "without it" rather than against the pack as
 * found.
 */
public final class PlanningView extends JPanel {

    /**
     * Everything the run needs that arguments would otherwise have carried.
     *
     * <p>What to measure is not here: that is the scenario config's job, and it is the operator's
     * to write. Pose, preset, phases and stop condition all live in that file.
     */
    /**
     * @param baseline what loads in every arm before any promotion: the explicit baseline mods,
     *     the instrumentation, and whatever those require
     * @param candidates the mods under test, in roster order. Bundling — each candidate plus the
     *     dependencies it carries — is the selection driver's job, recomputed every round as the
     *     baseline grows, which is why this carries the raw {@code requires} edges instead of
     *     bundles computed once.
     * @param requires direct requirements between installed files, from the jars' own manifests
     */
    public record Choice(
            ModrinthInstance instance,
            Set<String> baseline,
            Set<String> candidates,
            Map<String, Set<String>> requires,
            List<cx.mia.lucent.laymark.core.select.Branching.Conflict> conflicts,
            Map<String, String> modIdByFile,
            Schedule schedule,
            Map<String, String> displayNames) {}

    private static final String NO_VERSION = "— pick one —";
    private static final String VERSION_KEY = "version.";
    private static final String SCHEDULE_KEY = "schedule";
    private static final String INTERVAL_KEY = "baselineInterval";
    private static final String DEFAULT_SCHEDULE = "A,B,C,B,C";
    private static final java.util.prefs.Preferences PREFERENCES =
            java.util.prefs.Preferences.userRoot().node("cx/mia/lucent/laymark");

    private final JComboBox<String> profiles = new JComboBox<>();
    private final JComboBox<String> versions = new JComboBox<>();
    private final JLabel configStatus = Theme.muted("—");
    private final JTextField schedule =
            new JTextField(PREFERENCES.get(SCHEDULE_KEY, DEFAULT_SCHEDULE), 16);

    /**
     * How many candidate arms may pass before a baseline is re-established.
     *
     * <p>Baselines are the drift checks, so this is what bounds thermal drift: a wider interval
     * spends fewer arms on checking and voids more results when a check fails. It applies on top of
     * whatever the schedule says, because a template alone does not scale — at twenty candidates a
     * single {@code B} sits hours from the arms it is meant to bound. 1 means strict alternation.
     */
    private final JSpinner baselineInterval =
            new JSpinner(
                    new SpinnerNumberModel(
                            PREFERENCES.getInt(INTERVAL_KEY, Schedule.DEFAULT_BASELINE_INTERVAL),
                            1,
                            99,
                            1));

    private final JPanel modList = new Theme.VerticalList();
    private final JLabel modCount = Theme.muted("");
    private final Map<String, RoleControl> roles = new LinkedHashMap<>();
    private final JTextField search = new JTextField(18);
    private final OverflowRow presetRow = new OverflowRow(Theme.muted("Baseline:"));

    private final cx.mia.lucent.laymark.runner.select.ProbeCache probeCache =
            cx.mia.lucent.laymark.runner.select.ProbeCache.open();
    private DependencyGraph graph;
    private Map<String, String> modIdByFile = Map.of();
    private Map<String, String> fileByModId = Map.of();
    private Map<String, String> displayNameByFile = Map.of();
    private List<cx.mia.lucent.laymark.core.select.Branching.Conflict> conflicts = List.of();
    private final Path root;
    private final String hereProfile;

    /**
     * The profile the runner is sitting in, or null if it is not sitting in one.
     *
     * <p>The jar lives at an instance root (§5.3), so double-clicking it starts the process with
     * that instance as the working directory — which is a far better guess at what someone wants to
     * benchmark than whichever profile sorts first.
     */
    private static String profileHere(Path here) {
        Path parent = here.getParent();
        return parent != null && parent.getFileName().toString().equals("profiles")
                ? here.getFileName().toString()
                : null;
    }

    PlanningView() {
        Path here = Path.of("").toAbsolutePath().normalize();
        hereProfile = profileHere(here);
        root = hereProfile == null ? ModrinthInstance.defaultRoot() : here.getParent().getParent();

        setLayout(new BorderLayout(12, 12));
        setBackground(Theme.BACKGROUND);
        Theme.pad(this, 12, 12, 12, 12);

        add(instanceCard(), BorderLayout.NORTH);
        add(candidatesCard(), BorderLayout.CENTER);

        for (String profile : directories(root.resolve("profiles"))) {
            profiles.addItem(profile);
        }
        versions.addItem(NO_VERSION);
        for (String version : installedVersions()) {
            versions.addItem(version);
        }
        if (hereProfile != null) {
            profiles.setSelectedItem(hereProfile);
        }
        // Attach only after population. JComboBox selects its first added item, and treating that
        // transient selection as deliberate would create configs in an unrelated profile before
        // selecting the instance the runner is actually sitting in.
        profiles.addActionListener(
                unused -> {
                    recallVersion();
                    refreshConfigStatus();
                    reloadMods();
                });
        versions.addActionListener(unused -> rememberVersion());
        recallVersion();
        refreshConfigStatus();
        reloadMods();
    }

    /**
     * A form grid rather than flowed rows: one right-aligned label column, every value starting on
     * the same axis. Alignment is what makes three unrelated rows read as one instrument panel,
     * and the legend sits a tier below the values it explains instead of beside them at full
     * volume.
     */
    private JPanel instanceCard() {
        JPanel card = Theme.card("Instance");
        JPanel grid = new JPanel(new java.awt.GridBagLayout());
        grid.setOpaque(false);

        JButton edit = Theme.button("Edit", false);
        edit.addActionListener(unused -> editConfig());

        JPanel instanceRow = row();
        instanceRow.add(profiles);
        instanceRow.add(Theme.muted("on"));
        instanceRow.add(versions);

        JPanel scenariosRow = row();
        scenariosRow.add(configStatus);
        scenariosRow.add(edit);

        JPanel scheduleRow = row();
        scheduleRow.add(schedule);
        scheduleRow.add(Theme.muted("baseline every"));
        scheduleRow.add(baselineInterval);
        scheduleRow.add(Theme.muted("arms"));

        formRow(grid, 0, "instance", instanceRow);
        formRow(grid, 1, "scenarios", scenariosRow);
        formRow(grid, 2, "schedule", scheduleRow);
        // The legend is the documentation -- a schedule field with no key is a field people leave
        // alone -- but it is a footnote to the field, not a peer of it.
        formRow(
                grid,
                3,
                null,
                Theme.small("A acclimation · B baseline · C a pass over the candidates · CC twice each"));

        card.add(grid, BorderLayout.CENTER);
        return card;
    }

    /** One grid row: a right-aligned muted label in a shared column, then the value. */
    private static void formRow(JPanel grid, int y, String label, Component value) {
        var constraints = new java.awt.GridBagConstraints();
        constraints.gridy = y;
        constraints.insets = new java.awt.Insets(y == 0 ? 0 : 6, 0, 0, 10);
        constraints.gridx = 0;
        constraints.anchor = java.awt.GridBagConstraints.EAST;
        JLabel key = Theme.muted(label == null ? "" : label);
        key.setPreferredSize(new Dimension(70, 20));
        key.setHorizontalAlignment(JLabel.RIGHT);
        grid.add(key, constraints);

        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.anchor = java.awt.GridBagConstraints.WEST;
        constraints.insets = new java.awt.Insets(y == 0 ? 0 : 6, 0, 0, 0);
        grid.add(value, constraints);
    }

    /** Opens the config in whatever edits JSON here; the file is the interface, not this window. */
    private void editConfig() {
        try {
            Path path = ScenarioConfigFile.ensureExists(gameDirectory());
            java.awt.Desktop.getDesktop().open(path.toFile());
        } catch (java.io.IOException | RuntimeException e) {
            javax.swing.JOptionPane.showMessageDialog(
                    this,
                    "Could not open " + configPath() + ": " + e.getMessage(),
                    "Edit config",
                    javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Reads the instance's own config and says what it holds.
     *
     * <p>Not a file picker. {@code config/laymark.jsonc} is the single source of what a run measures
     * — the harness reads it from inside the game — so the planner reports on it rather than
     * offering to point somewhere the harness will not look.
     */
    private void refreshConfigStatus() {
        String profile = (String) profiles.getSelectedItem();
        if (profile == null) {
            configStatus.setText("—");
            return;
        }
        try {
            Path path = ScenarioConfigFile.ensureExists(gameDirectory());
            var scenarios =
                    ConfigCodec.read(Files.readString(path, StandardCharsets.UTF_8)).scenarios();
            configStatus.setForeground(Theme.MUTED);
            configStatus.setText(
                    Laymark.CONFIG_PATH + "  ·  " + scenarios.size() + " scenario(s): "
                            + scenarios.stream()
                                    .map(scenario -> scenario.id())
                                    .reduce((a, b) -> a + ", " + b)
                                    .orElse(""));
        } catch (RuntimeException | java.io.IOException e) {
            configStatus.setForeground(Theme.BAD);
            configStatus.setText(Laymark.CONFIG_PATH + " could not be read: " + e.getMessage());
        }
    }

    private JPanel candidatesCard() {
        JPanel card = Theme.card("Mods");

        search.putClientProperty("JTextField.placeholderText", "search");
        search.getDocument()
                .addDocumentListener(
                        new javax.swing.event.DocumentListener() {
                            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                                refilter();
                            }

                            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                                refilter();
                            }

                            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                                refilter();
                            }
                        });

        JPanel legend = new JPanel(new BorderLayout());
        legend.setOpaque(false);
        legend.add(
                Theme.muted(
                        "baseline — loaded in every arm   ·   candidate — its own arm, measured"
                                + " against the baseline   ·   off — withheld for the whole run"),
                BorderLayout.WEST);
        legend.add(modCount, BorderLayout.EAST);

        // Presets and search on their own lines: they answer different questions ("what is the
        // baseline" vs "where is that mod"), and sharing a row made the presets read as search
        // controls.
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        JPanel presetLine = presets();
        presetLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel searchLine = new JPanel(new BorderLayout());
        searchLine.setOpaque(false);
        searchLine.add(search, BorderLayout.CENTER);
        searchLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchLine.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 0, 0, 0));
        top.add(presetLine);
        top.add(searchLine);

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(top, BorderLayout.NORTH);
        body.add(Theme.scroll(modList), BorderLayout.CENTER);
        body.add(legend, BorderLayout.SOUTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    /**
     * Rebuilds the visible list from the roster: fuzzy-matched against the query, best match
     * first, declaration order when the query is empty.
     *
     * <p>Filtering shows and hides the same {@link RoleControl} instances rather than recreating
     * them, so an assignment made while filtered survives the filter changing.
     */
    private void refilter() {
        String query = search.getText().trim();
        modList.removeAll();
        if (query.isEmpty()) {
            roles.values().forEach(modList::add);
        } else {
            roles.entrySet().stream()
                    .map(entry -> Map.entry(entry.getValue(), fuzzyScore(query, entry.getKey())))
                    .filter(entry -> entry.getValue() > 0)
                    .sorted(Map.Entry.<RoleControl, Integer>comparingByValue().reversed())
                    .forEach(entry -> modList.add(entry.getKey()));
        }
        // Striped by visible position, not roster position: the stripe exists to carry the eye
        // from a name to its buttons across the row's width, and a filtered list re-stripes.
        int visible = 0;
        for (Component component : modList.getComponents()) {
            if (component instanceof RoleControl row) {
                row.stripe(visible++ % 2 == 1);
            }
        }
        modList.add(Box.createVerticalGlue());
        modList.revalidate();
        modList.repaint();
    }

    /**
     * Subsequence match with a relevance score; 0 means no match.
     *
     * <p>Small and predictable beats clever here: every query character must appear in order, and
     * the score prefers consecutive characters, matches at the start, and matches at word breaks
     * ({@code -}, {@code _}, {@code .}, a digit boundary). That is enough to put
     * {@code sodium-extra} above {@code reeses-sodium-options} for "sod ex" style queries without
     * anyone having to learn what the matcher rewards.
     */
    private static int fuzzyScore(String query, String candidate) {
        String q = query.toLowerCase(Locale.ROOT);
        String c = candidate.toLowerCase(Locale.ROOT);
        int score = 0;
        // The whole query appearing verbatim outranks any scattered match: "cul" should surface
        // entityculling and moreculling ahead of a c, a u and an l that happen to occur in order.
        String verbatim = q.replace(" ", "");
        if (!verbatim.isEmpty() && c.contains(verbatim)) {
            score += 5 * verbatim.length();
        }
        int at = 0;
        int streak = 0;
        for (int i = 0; i < q.length(); i++) {
            char wanted = q.charAt(i);
            if (wanted == ' ') {
                streak = 0;
                continue;
            }
            int found = c.indexOf(wanted, at);
            if (found < 0) {
                return 0;
            }
            streak = found == at ? streak + 1 : 1;
            score += streak;
            if (found == 0 || isWordBreak(c.charAt(found - 1))) {
                score += 3;
            }
            at = found + 1;
        }
        return score;
    }

    private static boolean isWordBreak(char before) {
        return before == '-' || before == '_' || before == '.' || Character.isDigit(before);
    }

    /**
     * The two questions people actually ask, as one click each.
     *
     * <p>They are presets over the roster rather than a mode the run carries, because the roster is
     * the real thing: any assignment reachable by a preset is also reachable by hand, and a mode
     * would be a second way to say something the roster already says.
     */
    private JPanel presets() {
        // Short labels, full names in the tooltip: the sidebar's narrow states are supported
        // states, and a preset whose label truncates to "Pack min…" has stopped saying what it
        // does. Whatever still does not fit folds behind the row's "…" menu instead of clipping.
        presetRow.clearItems();

        JButton minusCandidates = Theme.button("Pack", false);
        minusCandidates.setToolTipText(
                tooltip(
                        "Pack minus candidates",
                        "Everything installed stays in the baseline except the candidates."
                                + " Answers: what does this mod add to the pack as it stands?"));
        minusCandidates.addActionListener(unused -> assign(name -> Role.BASELINE));
        presetRow.addItem(minusCandidates);

        JButton blank = Theme.button("Blank", false);
        blank.setToolTipText(
                tooltip(
                        "Blank slate",
                        "Nothing but the candidates and whatever they require. Answers: what does"
                                + " this mod do on its own?"));
        blank.addActionListener(unused -> assign(name -> Role.OFF));
        presetRow.addItem(blank);

        // Only offered when there is an index to read. A button that silently means "vanilla"
        // because no ancestry was recorded is a button that has answered a different question.
        Set<String> added = inlayLayer();
        if (added != null) {
            JButton parent = Theme.button("Inlay parent", false);
            parent.setToolTipText(
                    tooltip(
                            "Inlay parent minus candidates",
                            "Everything this layer adds is withheld, so candidates are measured"
                                    + " against the layer underneath. For a root layer that parent"
                                    + " is vanilla."));
            parent.addActionListener(unused -> assignInlayParent());
            presetRow.addItem(parent);
        }

        // Only offered when a previous run promoted something: continuing from nothing is the
        // Blank slate button, not this one wearing a misleading name.
        List<String> winners = previousWinners();
        if (!winners.isEmpty()) {
            JButton previous = Theme.button("Last winners", false);
            previous.setToolTipText(
                    tooltip(
                            "Previous run's winners",
                            "Blank slate plus what the last run promoted: "
                                    + String.join(", ", winners)
                                    + ". Candidates are measured against the stack the last"
                                    + " selection arrived at, so runs chain instead of starting"
                                    + " over."));
            previous.addActionListener(
                    unused -> assign(name -> winners.contains(name) ? Role.BASELINE : Role.OFF));
            presetRow.addItem(previous);
        }
        presetRow.revalidate();
        presetRow.repaint();
        return presetRow;
    }

    /** The preset's full name over its explanation, wrapped: the label only had to be short. */
    private static String tooltip(String fullName, String explanation) {
        return "<html><body style='width: 280px'><b>" + fullName + "</b><br>" + explanation
                + "</body></html>";
    }

    /**
     * What the most recent selection in this profile promoted, by file name; empty when there is
     * no previous run, its report is unreadable, or nothing won.
     *
     * <p>Read from the newest {@code .laymark/<runId>/experiment.json}. Only winners still
     * installed count — a stack member since uninstalled cannot be a baseline entry, and silently
     * dropping it beats refusing the preset over a mod the operator already removed.
     */
    private List<String> previousWinners() {
        String profile = (String) profiles.getSelectedItem();
        if (profile == null) {
            return List.of();
        }
        Path workDir = root.resolve("profiles").resolve(profile).resolve(Laymark.WORK_DIR);
        if (!Files.isDirectory(workDir)) {
            return List.of();
        }
        try (Stream<Path> runs = Files.list(workDir)) {
            Path newest =
                    runs.filter(Files::isDirectory)
                            .filter(run -> Files.isRegularFile(run.resolve("experiment.json")))
                            .max(java.util.Comparator.comparing(run -> run.getFileName().toString()))
                            .orElse(null);
            if (newest == null) {
                return List.of();
            }
            var report =
                    cx.mia.lucent.laymark.core.report.ReportCodec.read(
                            Files.readString(
                                    newest.resolve("experiment.json"), StandardCharsets.UTF_8));
            return report.stack().stream().filter(roles::containsKey).toList();
        } catch (java.io.IOException | RuntimeException e) {
            // An unreadable old report costs a convenience button, nothing more.
            return List.of();
        }
    }

    private Set<String> inlayLayer() {
        String profile = (String) profiles.getSelectedItem();
        return profile == null
                ? null
                : InlayIndex.addedMods(root.resolve("profiles").resolve(profile));
    }

    /**
     * Applies a preset, then lets dependencies settle.
     *
     * <p>Candidates are left where they are: a preset changes what they are measured against, not
     * what they are.
     */
    private void assign(java.util.function.Function<String, Role> role) {
        roles.forEach(
                (name, control) -> {
                    if (control.role() != Role.CANDIDATE) {
                        control.set(role.apply(name));
                    }
                });
        updateDependencyNotes();
        updateCount();
    }

    private void assignInlayParent() {
        Set<String> added = inlayLayer();
        if (added == null) {
            return;
        }
        assign(name -> added.contains(name) ? Role.OFF : Role.BASELINE);
        warnIfIndexIsStale(added);
    }

    /**
     * An index that no longer describes {@code mods/} produces a wrong baseline, quietly.
     *
     * <p>It matches by file name, so a mod updated since the index was written looks like something
     * the layer never added and stays in the baseline — the arm then measures a candidate against a
     * stack that still contains part of the layer. Nothing downstream can detect that, which is why
     * it is said here rather than assumed away.
     */
    private void warnIfIndexIsStale(Set<String> added) {
        String profile = (String) profiles.getSelectedItem();
        if (added == null || profile == null) {
            return;
        }
        // Against everything installed, not against the roster: the instrumentation is installed
        // and hidden from the roster deliberately, which must not read as "missing".
        Set<String> unmatched = new TreeSet<>(added);
        unmatched.removeAll(enabledMods(root.resolve("profiles").resolve(profile)));
        if (unmatched.isEmpty()) {
            return;
        }
        javax.swing.JOptionPane.showMessageDialog(
                this,
                unmatched.size()
                        + " mod(s) named by "
                        + InlayIndex.FILE_NAME
                        + " are not installed under that name — most likely updated since the index"
                        + " was written:\n\n  "
                        + String.join("\n  ", unmatched)
                        + "\n\nTheir installed versions stayed in the baseline. Set them to off by"
                        + " hand, or refresh the index, or the candidates are measured against a"
                        + " stack that still contains part of this layer.",
                "The index does not match mods/",
                javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    private static JPanel row() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        return row;
    }

    /**
     * A single-line row of buttons that folds what does not fit behind a "…" menu.
     *
     * <p>The alternative behaviours are both worse: a FlowLayout clips trailing buttons without a
     * trace, and truncated labels stop saying what the button does. Buttons keep their declared
     * order; the first one that does not fit and everything after it move into the menu, so the
     * row never shows a gap-toothed subset.
     */
    private static final class OverflowRow extends JPanel {

        private static final int GAP = 8;

        private final Component caption;
        private final List<AbstractButton> items = new ArrayList<>();
        private final JButton more = Theme.button("…", false);

        OverflowRow(Component caption) {
            super(null); // laid out by hand in doLayout
            setOpaque(false);
            this.caption = caption;
            more.setToolTipText("The presets that do not fit");
            more.addActionListener(unused -> menu());
            add(caption);
            add(more);
        }

        void clearItems() {
            items.forEach(this::remove);
            items.clear();
        }

        void addItem(AbstractButton button) {
            items.add(button);
            add(button);
        }

        /** The hidden buttons as menu entries, delegating to the buttons so behaviour has one home. */
        private void menu() {
            var popup = new javax.swing.JPopupMenu();
            for (AbstractButton hidden : items) {
                if (hidden.isVisible()) {
                    continue;
                }
                var item = new javax.swing.JMenuItem(hidden.getText());
                item.setToolTipText(hidden.getToolTipText());
                item.setEnabled(hidden.isEnabled());
                item.addActionListener(unused -> hidden.doClick());
                popup.add(item);
            }
            popup.show(more, 0, more.getHeight());
        }

        @Override
        public Dimension getPreferredSize() {
            int width = caption.getPreferredSize().width;
            int height = caption.getPreferredSize().height;
            for (AbstractButton item : items) {
                width += GAP + item.getPreferredSize().width;
                height = Math.max(height, item.getPreferredSize().height);
            }
            return new Dimension(width, Math.max(height, more.getPreferredSize().height));
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(
                    caption.getPreferredSize().width + GAP + more.getPreferredSize().width,
                    getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public void doLayout() {
            int width = getWidth();
            int height = getHeight();
            int x = 0;
            place(caption, x, height);
            x += caption.getPreferredSize().width;

            int needed = 0;
            for (AbstractButton item : items) {
                needed += GAP + item.getPreferredSize().width;
            }
            boolean allFit = x + needed <= width;
            int reserved = allFit ? 0 : GAP + more.getPreferredSize().width;

            boolean overflowing = false;
            for (AbstractButton item : items) {
                int itemWidth = item.getPreferredSize().width;
                if (!overflowing && x + GAP + itemWidth + reserved <= width) {
                    x += GAP;
                    place(item, x, height);
                    x += itemWidth;
                    item.setVisible(true);
                } else {
                    overflowing = true;
                    item.setVisible(false);
                }
            }
            more.setVisible(!allFit);
            if (!allFit) {
                place(more, x + GAP, height);
            }
        }

        private static void place(Component component, int x, int rowHeight) {
            Dimension pref = component.getPreferredSize();
            component.setBounds(x, (rowHeight - pref.height) / 2, pref.width, pref.height);
        }
    }

    private void reloadMods() {
        roles.clear();
        String profile = (String) profiles.getSelectedItem();
        if (profile != null) {
            Path gameDirectory = root.resolve("profiles").resolve(profile);
            probeDependencies(gameDirectory);
            for (String name : installed(gameDirectory)) {
                roles.put(name, new RoleControl(display(name), name, this::roleChanged));
            }
        }
        refilter();
        presets();
        updateCount();
        revalidate();
        repaint();
    }

    private void roleChanged() {
        updateDependencyNotes();
        updateCount();
    }

    /**
     * The files a mod's bundle carries besides itself: its transitive requirements, minus anything
     * already loaded in every arm.
     *
     * <p>A dependency is never promoted to a role of its own. It rides inside the bundle of
     * whatever needs it — a candidate's arm enables the candidate <em>and</em> its requirements,
     * the baseline includes what baseline mods require — while the dependency's own roster state
     * stays exactly what the operator set. Silently changing a row's state was the alternative,
     * and a control that changes itself is a control nobody trusts.
     */
    private Set<String> carriedDependencies(String fileName) {
        if (graph == null) {
            return Set.of();
        }
        String modId = modIdByFile.get(fileName);
        if (modId == null) {
            return Set.of();
        }
        Set<String> carried = new TreeSet<>();
        for (String required : graph.closureOf(modId)) {
            String file = fileByModId.get(required);
            if (file == null) {
                continue; // not installed under any name we can move; the game will say so
            }
            RoleControl control = roles.get(file);
            boolean alreadyEverywhere =
                    (control != null && control.role() == Role.BASELINE) || isInstrumentation(file);
            if (!alreadyEverywhere) {
                carried.add(file);
            }
        }
        return carried;
    }

    /**
     * Annotates every baseline or candidate row with what its bundle silently carries.
     *
     * <p>The note lives under the mod that <em>causes</em> the inclusion, because that is where
     * the decision was made; the dependency's own row shows nothing unusual.
     */
    private void updateDependencyNotes() {
        roles.forEach(
                (file, control) -> {
                    if (control.role() == Role.OFF) {
                        control.note(List.of());
                        return;
                    }
                    control.note(
                            carriedDependencies(file).stream()
                                    .map(dep -> display(dep) + " — included as a dependency of this bundle")
                                    .toList());
                });
        modList.revalidate();
        modList.repaint();
    }

    /** Reads every installed jar once, so a role change can consult the graph without touching disk. */
    private void probeDependencies(Path gameDirectory) {
        graph = null;
        modIdByFile = Map.of();
        fileByModId = Map.of();
        displayNameByFile = Map.of();
        conflicts = List.of();
        Path mods = gameDirectory.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return;
        }
        try (Stream<Path> jars = Files.list(mods)) {
            var probed =
                    JarProbe.inspect(
                            jars.filter(Files::isRegularFile)
                                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                                    .toList(),
                            probeCache);
            probeCache.persist();
            var overrides =
                    cx.mia.lucent.laymark.runner.select.DependencyOverrides.load(gameDirectory);
            // Merged with the jars winning on any shared edge: an operator's memory goes stale
            // faster than a manifest. Overrides exist for the edge no manifest declares.
            graph = DependencyGraph.merge(probed.graph(), overrides.graph());
            modIdByFile = probed.modIdByFile();
            displayNameByFile = probed.displayNameByFile();
            fileByModId = probed.providerByModId();

            List<cx.mia.lucent.laymark.core.select.Branching.Conflict> all =
                    new java.util.ArrayList<>(probed.conflicts());
            for (var conflict : overrides.conflicts()) {
                String a = fileByModId.get(conflict.a());
                String b = fileByModId.get(conflict.b());
                if (a != null && b != null) {
                    all.add(new cx.mia.lucent.laymark.core.select.Branching.Conflict(a, b));
                }
            }
            conflicts = List.copyOf(all);
        } catch (java.io.IOException | RuntimeException e) {
            // Without a graph the roster still works; it just stops offering to keep itself
            // loadable, which is better than refusing to plan a run.
            System.err.println("could not read mod dependencies: " + e.getMessage());
        }
    }

    /**
     * Locks or unlocks planning around a run.
     *
     * <p>The roster stays visible while an experiment executes — its rows carry the per-arm
     * status — but a toggle that appeared to change a running experiment would be lying, so
     * everything that shapes the next run is disabled until the current one finishes. Search
     * stays live: finding a row is reading, not planning. Unlocking rebuilds the preset row so
     * "Previous run's winners" reflects the run that just ended.
     */
    public void setPlanningEnabled(boolean enabled) {
        profiles.setEnabled(enabled);
        versions.setEnabled(enabled);
        schedule.setEnabled(enabled);
        baselineInterval.setEnabled(enabled);
        roles.values().forEach(control -> control.lock(!enabled));
        for (Component component : presetRow.getComponents()) {
            component.setEnabled(enabled);
        }
        if (enabled) {
            presets();
            revalidate();
            repaint();
        }
    }

    /** The live state of a candidate's arm, shown on its roster row during a run. */
    public void armStatus(String fileName, String text, java.awt.Color colour) {
        RoleControl control = roles.get(fileName);
        if (control != null) {
            control.status(text, colour);
        }
    }

    private static String shorten(String fileName) {
        return fileName.replaceFirst("\\.jar$", "");
    }

    /**
     * What a mod is called, for humans: the manifest's display name, the file name failing that.
     *
     * <p>"Sodium", not {@code sodium-neoforge-0.9.2-alpha.4+mc26.1.2}. The file name stays the
     * identity everywhere the code moves files; it is only the reading that changes.
     */
    private String display(String fileName) {
        return displayNameByFile.getOrDefault(fileName, shorten(fileName));
    }

    /** What one mod is doing in this experiment. */
    private enum Role {
        OFF,
        BASELINE,
        CANDIDATE
    }

    /** One mod's row: its name, the three things it can be, and what its bundle carries. */
    private static final class RoleControl extends JPanel {

        private final Map<Role, JToggleButton> buttons = new LinkedHashMap<>();
        private final JPanel notes = new JPanel();
        private final JLabel runState = new JLabel("");

        RoleControl(String displayName, String fileName, Runnable onChange) {
            setLayout(new BorderLayout(8, 0));
            setOpaque(false);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 0, 2, 6));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel name = new JLabel(displayName);
            name.setForeground(Theme.TEXT);
            // The file name stays reachable: it is what actually moves on disk, and two mods can
            // share a display name.
            name.setToolTipText(fileName);

            notes.setLayout(new BoxLayout(notes, BoxLayout.Y_AXIS));
            notes.setOpaque(false);
            notes.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 14, 0, 0));

            JPanel text = new JPanel();
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            text.setOpaque(false);
            name.setAlignmentX(Component.LEFT_ALIGNMENT);
            notes.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(name);
            text.add(notes);

            ButtonGroup group = new ButtonGroup();
            JPanel choices = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            choices.setOpaque(false);
            runState.setFont(runState.getFont().deriveFont(11f));
            choices.add(runState);
            for (Role role : Role.values()) {
                JToggleButton button = new JToggleButton(label(role));
                button.setFocusPainted(false);
                button.addActionListener(unused -> onChange.run());
                group.add(button);
                buttons.put(role, button);
                choices.add(button);
            }
            // Baseline by default: an unconfigured roster describes the pack as it is installed,
            // which is the only assignment that measures nothing until someone asks a question.
            buttons.get(Role.BASELINE).setSelected(true);

            add(text, BorderLayout.CENTER);
            add(choices, BorderLayout.EAST);
        }

        /** Alternating background, so the eye can follow a name to its buttons across the width. */
        void stripe(boolean shaded) {
            setOpaque(shaded);
            setBackground(Theme.RAISED);
        }

        /** The arm's live state during a run — queued, running, done, failed — beside the toggles. */
        void status(String text, java.awt.Color colour) {
            runState.setText(text);
            runState.setForeground(colour);
        }

        void lock(boolean locked) {
            buttons.values().forEach(button -> button.setEnabled(!locked));
            if (!locked) {
                runState.setText("");
            }
        }

        /** What this row's bundle carries, one line per dependency; empty clears it. */
        void note(List<String> lines) {
            notes.removeAll();
            for (String line : lines) {
                JLabel label = Theme.small(line);
                label.setAlignmentX(Component.LEFT_ALIGNMENT);
                notes.add(label);
            }
            revalidate();
        }

        @Override
        public Dimension getMaximumSize() {
            // Full width, own height: a BoxLayout would otherwise stretch rows to share leftover
            // vertical space, and a roster whose rows drift apart reads as broken.
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        private static String label(Role role) {
            return switch (role) {
                case OFF -> "off";
                case BASELINE -> "baseline";
                case CANDIDATE -> "candidate";
            };
        }

        Role role() {
            for (Map.Entry<Role, JToggleButton> entry : buttons.entrySet()) {
                if (entry.getValue().isSelected()) {
                    return entry.getKey();
                }
            }
            return Role.BASELINE;
        }

        void set(Role role) {
            buttons.get(role).setSelected(true);
        }
    }

    /**
     * Which version a profile runs is recorded only in the launcher's own database, which Laymark
     * does not read — so it is never guessed. Newest-installed is a plausible-looking wrong answer,
     * and the failure it produces is a whole game launched on the wrong version.
     *
     * <p>Asked once per profile and remembered after that.
     */
    private void recallVersion() {
        String profile = (String) profiles.getSelectedItem();
        if (profile == null) {
            versions.setSelectedItem(NO_VERSION);
            return;
        }
        // Detection first, memory second: the profile's own logs describe what it runs now, whereas
        // a remembered answer describes what it ran when someone last said so.
        String detected =
                InstalledVersion.detect(
                        root.resolve("profiles").resolve(profile), installedVersions());
        String remembered = PREFERENCES.get(VERSION_KEY + profile, null);
        String chosen = detected != null ? detected : remembered;
        versions.setSelectedItem(
                chosen != null && contains(versions, chosen) ? chosen : NO_VERSION);
    }

    private List<String> installedVersions() {
        return directories(root.resolve("meta").resolve("versions"));
    }

    private void rememberVersion() {
        String profile = (String) profiles.getSelectedItem();
        String version = (String) versions.getSelectedItem();
        if (profile != null && version != null && !NO_VERSION.equals(version)) {
            PREFERENCES.put(VERSION_KEY + profile, version);
        }
    }

    private static boolean contains(JComboBox<String> combo, String item) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (item.equals(combo.getItemAt(i))) {
                return true;
            }
        }
        return false;
    }

    private void updateCount() {
        modCount.setText(
                String.format(
                        Locale.ROOT,
                        "%d baseline · %d candidates · %d off",
                        named(Role.BASELINE).size(),
                        named(Role.CANDIDATE).size(),
                        named(Role.OFF).size()));
    }

    /**
     * The instrumentation, which is not up for assignment.
     *
     * <p>Laymark is what produces the measurements, and Spark and Chunky are what it measures
     * through — pinned dependencies rather than mods anyone chose. Offering one as a candidate
     * offers a run that reports nothing, and offering it as "off" offers a run that cannot start,
     * so all three are held in the baseline and kept out of the list.
     */
    private static final Set<String> INSTRUMENTATION = Laymark.INSTRUMENTATION_MOD_IDS;

    /** The installed mods an operator may assign. */
    private Set<String> installed(Path gameDirectory) {
        Set<String> names = enabledMods(gameDirectory);
        names.removeIf(this::isInstrumentation);
        return names;
    }

    /** Held in the baseline whatever the roster says. */
    private Set<String> instrumentation() {
        String profile = (String) profiles.getSelectedItem();
        if (profile == null) {
            return Set.of();
        }
        Set<String> names = enabledMods(root.resolve("profiles").resolve(profile));
        names.removeIf(name -> !isInstrumentation(name));
        return names;
    }

    /**
     * By mod id, from the jar's own manifest — the file name is a packaging choice, and a build of
     * Spark named anything at all still declares itself {@code spark}.
     */
    private boolean isInstrumentation(String fileName) {
        String modId = modIdByFile.get(fileName);
        return modId != null
                ? INSTRUMENTATION.contains(modId)
                : INSTRUMENTATION.stream()
                        .anyMatch(known -> fileName.toLowerCase(Locale.ROOT).startsWith(known));
    }

    private Set<String> enabledMods(Path gameDirectory) {
        return Files.isDirectory(gameDirectory)
                ? new TreeSet<>(new ModsDirectory(gameDirectory).read().enabledNames())
                : new TreeSet<>();
    }

    private static List<String> directories(Path parent) {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(parent)) {
            List<String> names = new ArrayList<>(entries.filter(Files::isDirectory).map(path -> path.getFileName().toString()).toList());
            names.sort(String::compareTo);
            return names;
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    private Set<String> named(Role role) {
        Set<String> chosen = new TreeSet<>();
        roles.forEach(
                (name, control) -> {
                    if (control.role() == role) {
                        chosen.add(name);
                    }
                });
        return chosen;
    }

    /** @return null when the form does not describe a runnable experiment yet */
    Choice choice() {
        String profile = (String) profiles.getSelectedItem();
        String version = (String) versions.getSelectedItem();
        if (profile == null
                || version == null
                || NO_VERSION.equals(version)
                || named(Role.CANDIDATE).isEmpty()
                || !Files.isRegularFile(configPath())) {
            return null;
        }
        Schedule parsed;
        try {
            parsed = schedule();
        } catch (RuntimeException e) {
            return null;
        }
        PREFERENCES.put(SCHEDULE_KEY, schedule.getText().trim());
        PREFERENCES.putInt(INTERVAL_KEY, (Integer) baselineInterval.getValue());
        // The baseline carries what baseline mods require, exactly as a candidate's bundle does.
        Set<String> baseline = named(Role.BASELINE);
        baseline.addAll(instrumentation());
        for (String name : named(Role.BASELINE)) {
            baseline.addAll(carriedDependencies(name));
        }

        // The dependency edges in file space, so the driver can rebuild bundles every round
        // against whatever the baseline has grown into.
        Map<String, Set<String>> requires = new LinkedHashMap<>();
        if (graph != null) {
            modIdByFile.forEach(
                    (file, modId) -> {
                        Set<String> required = new TreeSet<>();
                        for (String neededId : graph.directRequirementsOf(modId)) {
                            String neededFile = fileByModId.get(neededId);
                            if (neededFile != null) {
                                required.add(neededFile);
                            }
                        }
                        if (!required.isEmpty()) {
                            requires.put(file, required);
                        }
                    });
        }

        return new Choice(
                new ModrinthInstance(root, profile, version),
                baseline,
                named(Role.CANDIDATE),
                requires,
                conflicts,
                Map.copyOf(modIdByFile),
                parsed,
                Map.copyOf(displayNameByFile));
    }

    private Schedule schedule() {
        return new Schedule(
                cx.mia.lucent.laymark.core.experiment.RoundTemplate.parse(schedule.getText()),
                (Integer) baselineInterval.getValue());
    }

    /** The selected profile's own config; the only place scenarios come from. */
    private Path configPath() {
        return ScenarioConfigFile.path(gameDirectory());
    }

    private Path gameDirectory() {
        String profile = (String) profiles.getSelectedItem();
        return root.resolve("profiles")
                .resolve(profile == null ? "" : profile);
    }

    /** Why {@link #choice()} returned nothing, phrased for whoever is looking at the form. */
    String blocker() {
        if (profiles.getSelectedItem() == null) {
            return "No Modrinth profile found under " + root + ".";
        }
        Object version = versions.getSelectedItem();
        if (version == null) {
            return "No installed version found under " + root + "/meta/versions.";
        }
        if (NO_VERSION.equals(version)) {
            return "Pick the game version this profile runs. Laymark does not read the launcher's"
                    + " database, so it will not guess — and the wrong guess launches the wrong"
                    + " game. It is remembered after the first time.";
        }
        if (!Files.isRegularFile(configPath())) {
            return "No scenario config at " + configPath() + ". It is hand-authored and says what"
                    + " to measure — where to stand, which phases, when to stop. Laymark ships"
                    + " none of its own.";
        }
        try {
            schedule();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        return "Mark at least one mod as a candidate. A run with none compares nothing.";
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(760, 520);
    }
}
