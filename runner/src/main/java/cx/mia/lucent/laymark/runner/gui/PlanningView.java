package cx.mia.lucent.laymark.runner.gui;

import cx.mia.lucent.laymark.core.experiment.Schedule;
import cx.mia.lucent.laymark.runner.launch.InstalledVersion;
import cx.mia.lucent.laymark.runner.launch.ModrinthInstance;
import cx.mia.lucent.laymark.runner.materialize.InlayIndex;
import cx.mia.lucent.laymark.runner.materialize.ModsDirectory;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
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
    public record Choice(
            ModrinthInstance instance,
            Set<String> baseline,
            Set<String> candidates,
            Path config,
            Schedule schedule) {

        /**
         * Every mod Laymark takes charge of. Anything installed and not named here is withheld for
         * the whole run rather than merely disabled, so {@code mods/} holds only what took part.
         */
        public Set<String> participants() {
            Set<String> all = new TreeSet<>(baseline);
            all.addAll(candidates);
            return all;
        }
    }

    private static final String NO_VERSION = "— pick one —";
    private static final String VERSION_KEY = "version.";
    private static final String SCHEDULE_KEY = "schedule";
    private static final String INTERVAL_KEY = "baselineInterval";
    private static final String DEFAULT_SCHEDULE = "A,B,C,B,C";
    private static final java.util.prefs.Preferences PREFERENCES =
            java.util.prefs.Preferences.userRoot().node("cx/mia/lucent/laymark");

    private final JComboBox<String> profiles = new JComboBox<>();
    private final JComboBox<String> versions = new JComboBox<>();
    private final JTextField config = new JTextField(38);
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

    private final JPanel modList = new JPanel();
    private final JLabel modCount = Theme.muted("");
    private final Map<String, RoleControl> roles = new LinkedHashMap<>();
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

        profiles.addActionListener(
                unused -> {
                    recallVersion();
                    reloadMods();
                });
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
        versions.addActionListener(unused -> rememberVersion());
        recallVersion();
        reloadMods();
    }

    private JPanel instanceCard() {
        JPanel card = Theme.card("Instance");
        JPanel fields = new JPanel(new GridLayout(3, 1, 0, 8));
        fields.setOpaque(false);

        JPanel top = row();
        top.add(Theme.muted("profile"));
        top.add(profiles);
        top.add(Theme.muted("version"));
        top.add(versions);

        JButton browse = Theme.button("Browse", false);
        browse.addActionListener(unused -> browseForConfig());
        JPanel middle = row();
        middle.add(Theme.muted("scenarios"));
        middle.add(config);
        middle.add(browse);

        JPanel bottom = row();
        bottom.add(Theme.muted("schedule"));
        bottom.add(schedule);
        bottom.add(Theme.muted("baseline every"));
        bottom.add(baselineInterval);
        bottom.add(Theme.muted("arms"));
        // The legend is the documentation. A schedule field with no key beside it is a field people
        // leave alone.
        bottom.add(Theme.muted("— A acclimation, B baseline, C a pass over the candidates, CC twice each"));

        fields.add(top);
        fields.add(middle);
        fields.add(bottom);
        card.add(fields, BorderLayout.CENTER);
        return card;
    }

    /**
     * What each scenario measures — pose, preset, phases, stop condition — comes from this file.
     *
     * <p>Laymark ships none. What is worth measuring is a property of the pack and of whoever is
     * tuning it, and a benchmark that invents its own scenarios measures its own opinion.
     */
    private void browseForConfig() {
        JFileChooser chooser = new JFileChooser(config.getText().isBlank() ? "." : config.getText());
        chooser.setDialogTitle("Scenario config");
        chooser.setFileFilter(
                new javax.swing.filechooser.FileNameExtensionFilter("Scenario config (*.json)", "json"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            config.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private JPanel candidatesCard() {
        JPanel card = Theme.card("Mods");
        modList.setLayout(new BoxLayout(modList, BoxLayout.Y_AXIS));
        modList.setOpaque(false);

        JPanel legend = new JPanel(new BorderLayout());
        legend.setOpaque(false);
        legend.add(
                Theme.muted(
                        "baseline — loaded in every arm   ·   candidate — its own arm, measured"
                                + " against the baseline   ·   off — withheld for the whole run"),
                BorderLayout.WEST);
        legend.add(modCount, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(presets(), BorderLayout.NORTH);
        body.add(Theme.scroll(modList), BorderLayout.CENTER);
        body.add(legend, BorderLayout.SOUTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    /**
     * The two questions people actually ask, as one click each.
     *
     * <p>They are presets over the roster rather than a mode the run carries, because the roster is
     * the real thing: any assignment reachable by a preset is also reachable by hand, and a mode
     * would be a second way to say something the roster already says.
     */
    private JPanel presets() {
        JButton minusCandidates = Theme.button("Baseline: pack minus candidates", false);
        minusCandidates.setToolTipText(
                "Everything installed stays in the baseline except the candidates. Answers:"
                        + " what does this mod add to the pack as it stands?");
        minusCandidates.addActionListener(unused -> assignAll(Role.BASELINE));

        JButton parentPack = Theme.button("Baseline: the pack this was built from", false);
        parentPack.setToolTipText(
                "Everything this Inlay layer adds is withheld, so candidates are measured against"
                        + " the pack underneath. Vanilla when there is no inlay.index.json.");
        parentPack.addActionListener(unused -> assignParentPack());

        JPanel row = row();
        row.add(minusCandidates);
        row.add(parentPack);
        return row;
    }

    /** Leaves candidates alone: a preset changes what they are measured against, not what they are. */
    private void assignAll(Role role) {
        roles.forEach(
                (name, control) -> {
                    if (control.role() != Role.CANDIDATE) {
                        control.set(role);
                    }
                });
        updateCount();
    }

    private void assignParentPack() {
        String profile = (String) profiles.getSelectedItem();
        if (profile == null) {
            return;
        }
        Set<String> added = InlayIndex.addedMods(root.resolve("profiles").resolve(profile));
        roles.forEach(
                (name, control) -> {
                    if (control.role() == Role.CANDIDATE) {
                        return;
                    }
                    // No index means no recorded ancestor, so nothing here is known to be inherited
                    // and the pack underneath is vanilla.
                    control.set(added == null || added.contains(name) ? Role.OFF : Role.BASELINE);
                });
        updateCount();
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
        if (added == null) {
            return;
        }
        Set<String> unmatched = new TreeSet<>(added);
        unmatched.removeAll(roles.keySet());
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

    private void reloadMods() {
        modList.removeAll();
        roles.clear();
        String profile = (String) profiles.getSelectedItem();
        if (profile != null) {
            for (String name : installed(root.resolve("profiles").resolve(profile))) {
                RoleControl control = new RoleControl(name, this::updateCount);
                roles.put(name, control);
                modList.add(control);
            }
        }
        modList.add(Box.createVerticalGlue());
        updateCount();
        revalidate();
        repaint();
    }

    /** What one mod is doing in this experiment. */
    private enum Role {
        OFF,
        BASELINE,
        CANDIDATE
    }

    /** One mod's row: its name, and the three things it can be. */
    private static final class RoleControl extends JPanel {

        private final Map<Role, JToggleButton> buttons = new LinkedHashMap<>();

        RoleControl(String fileName, Runnable onChange) {
            setLayout(new BorderLayout(8, 0));
            setOpaque(false);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 0, 2, 6));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
            setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel name = new JLabel(fileName.replaceFirst("\\.jar$", ""));
            name.setForeground(Theme.TEXT);

            ButtonGroup group = new ButtonGroup();
            JPanel choices = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            choices.setOpaque(false);
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

            add(name, BorderLayout.WEST);
            add(choices, BorderLayout.EAST);
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
     * The installed mods an operator may assign, which excludes Laymark's own.
     *
     * <p>The harness has to load in every arm — it is what produces the measurements — so offering
     * it as a candidate offers a run that cannot report anything, and offering it as "off" offers
     * one that cannot start. It is added to the baseline in {@link #choice()} instead.
     */
    private Set<String> installed(Path gameDirectory) {
        if (!Files.isDirectory(gameDirectory)) {
            return Set.of();
        }
        Set<String> names = new TreeSet<>(new ModsDirectory(gameDirectory).read().enabledNames());
        names.removeIf(PlanningView::isHarness);
        return names;
    }

    private static boolean isHarness(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).startsWith("laymark-");
    }

    /** Every installed mod, including the harness, so it can be put back in the baseline. */
    private Set<String> harnessMods() {
        String profile = (String) profiles.getSelectedItem();
        if (profile == null) {
            return Set.of();
        }
        Path gameDirectory = root.resolve("profiles").resolve(profile);
        if (!Files.isDirectory(gameDirectory)) {
            return Set.of();
        }
        Set<String> names = new TreeSet<>(new ModsDirectory(gameDirectory).read().enabledNames());
        names.removeIf(name -> !isHarness(name));
        return names;
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
        Set<String> baseline = named(Role.BASELINE);
        baseline.addAll(harnessMods());
        return new Choice(
                new ModrinthInstance(root, profile, version),
                baseline,
                named(Role.CANDIDATE),
                configPath(),
                parsed);
    }

    private Schedule schedule() {
        return new Schedule(
                cx.mia.lucent.laymark.core.experiment.RoundTemplate.parse(schedule.getText()),
                (Integer) baselineInterval.getValue());
    }

    private Path configPath() {
        return Path.of(config.getText().trim());
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
        if (config.getText().isBlank()) {
            return "Choose a scenario config. It is what says what to measure — where to stand,"
                    + " which phases, and when to stop — and Laymark ships none of its own.";
        }
        if (!Files.isRegularFile(configPath())) {
            return "No scenario config at " + configPath() + ".";
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
