package cx.mia.lucent.laymark.runner.gui;

import cx.mia.lucent.laymark.core.experiment.Schedule;
import cx.mia.lucent.laymark.runner.launch.ModrinthInstance;
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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

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
            ModrinthInstance instance, Set<String> candidates, Path config, Schedule schedule) {}

    private static final String NO_VERSION = "— pick one —";
    private static final String VERSION_KEY = "version.";
    private static final String SCHEDULE_KEY = "schedule";
    private static final String DEFAULT_SCHEDULE = "A,B,C,B,C";
    private static final java.util.prefs.Preferences PREFERENCES =
            java.util.prefs.Preferences.userRoot().node("cx/mia/lucent/laymark");

    private final JComboBox<String> profiles = new JComboBox<>();
    private final JComboBox<String> versions = new JComboBox<>();
    private final JTextField config = new JTextField(38);
    private final JTextField schedule =
            new JTextField(PREFERENCES.get(SCHEDULE_KEY, DEFAULT_SCHEDULE), 16);

    private final JPanel modList = new JPanel();
    private final JLabel modCount = Theme.muted("");
    private final Map<String, JCheckBox> mods = new LinkedHashMap<>();
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
        for (String version : directories(root.resolve("meta").resolve("versions"))) {
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
        // The legend is the documentation. A schedule field with no key beside it is a field people
        // leave alone.
        bottom.add(Theme.muted("A acclimation   B baseline   C a pass over the candidates   CC twice each"));

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
        JPanel card = Theme.card("Candidates");
        modList.setLayout(new BoxLayout(modList, BoxLayout.Y_AXIS));
        modList.setOpaque(false);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(
                Theme.muted("Check a mod to measure the pack with it against the pack without it."),
                BorderLayout.WEST);
        header.add(modCount, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(header, BorderLayout.NORTH);
        body.add(Theme.scroll(modList), BorderLayout.CENTER);
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    private static JPanel row() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        return row;
    }

    private void reloadMods() {
        modList.removeAll();
        mods.clear();
        String profile = (String) profiles.getSelectedItem();
        if (profile != null) {
            for (String name : installed(root.resolve("profiles").resolve(profile))) {
                JCheckBox box = new JCheckBox(name.replaceFirst("\\.jar$", ""));
                box.setOpaque(false);
                box.setForeground(Theme.TEXT);
                box.setAlignmentX(Component.LEFT_ALIGNMENT);
                box.addActionListener(unused -> updateCount());
                mods.put(name, box);
                modList.add(box);
            }
        }
        modList.add(Box.createVerticalGlue());
        updateCount();
        revalidate();
        repaint();
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
        String remembered = profile == null ? null : PREFERENCES.get(VERSION_KEY + profile, null);
        versions.setSelectedItem(
                remembered != null && contains(versions, remembered) ? remembered : NO_VERSION);
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
        modCount.setText(selected().size() + " of " + mods.size() + " selected");
    }

    private Set<String> installed(Path gameDirectory) {
        if (!Files.isDirectory(gameDirectory)) {
            return Set.of();
        }
        return new TreeSet<>(new ModsDirectory(gameDirectory).read().enabledNames());
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

    Set<String> selected() {
        Set<String> chosen = new TreeSet<>();
        mods.forEach(
                (name, box) -> {
                    if (box.isSelected()) {
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
                || selected().isEmpty()
                || !Files.isRegularFile(configPath())) {
            return null;
        }
        Schedule parsed;
        try {
            parsed = Schedule.of(schedule.getText());
        } catch (RuntimeException e) {
            return null;
        }
        PREFERENCES.put(SCHEDULE_KEY, schedule.getText().trim());
        return new Choice(
                new ModrinthInstance(root, profile, version), selected(), configPath(), parsed);
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
            Schedule.of(schedule.getText());
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        return "Check at least one mod to benchmark.";
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(760, 520);
    }
}
