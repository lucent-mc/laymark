package cx.mia.lucent.laymark.runner.gui;

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
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
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

    /** Everything the run needs that arguments would otherwise have carried. */
    public record Choice(
            ModrinthInstance instance,
            Set<String> candidates,
            int captureSeconds,
            int repetitions,
            int renderDistance) {}

    private final JComboBox<String> profiles = new JComboBox<>();
    private final JComboBox<String> versions = new JComboBox<>();
    private final JSpinner captureSeconds = new JSpinner(new SpinnerNumberModel(30, 5, 600, 5));
    private final JSpinner repetitions = new JSpinner(new SpinnerNumberModel(3, 1, 20, 1));
    private final JSpinner renderDistance = new JSpinner(new SpinnerNumberModel(12, 2, 32, 1));

    private final JPanel modList = new JPanel();
    private final JLabel modCount = Theme.muted("");
    private final Map<String, JCheckBox> mods = new LinkedHashMap<>();
    private final Path root = ModrinthInstance.defaultRoot();

    PlanningView() {
        setLayout(new BorderLayout(12, 12));
        setBackground(Theme.BACKGROUND);
        Theme.pad(this, 12, 12, 12, 12);

        add(instanceCard(), BorderLayout.NORTH);
        add(candidatesCard(), BorderLayout.CENTER);

        profiles.addActionListener(unused -> reloadMods());
        for (String profile : directories(root.resolve("profiles"))) {
            profiles.addItem(profile);
        }
        for (String version : directories(root.resolve("meta").resolve("versions"))) {
            versions.addItem(version);
        }
        reloadMods();
    }

    private JPanel instanceCard() {
        JPanel card = Theme.card("Instance");
        JPanel fields = new JPanel(new GridLayout(2, 1, 0, 8));
        fields.setOpaque(false);

        JPanel top = row();
        top.add(Theme.muted("profile"));
        top.add(profiles);
        top.add(Theme.muted("version"));
        top.add(versions);

        JPanel bottom = row();
        bottom.add(Theme.muted("capture seconds"));
        bottom.add(captureSeconds);
        bottom.add(Theme.muted("repeats per arm"));
        bottom.add(repetitions);
        bottom.add(Theme.muted("render distance"));
        bottom.add(renderDistance);

        fields.add(top);
        fields.add(bottom);
        card.add(fields, BorderLayout.CENTER);
        return card;
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
        if (profile == null || version == null || selected().isEmpty()) {
            return null;
        }
        return new Choice(
                new ModrinthInstance(root, profile, version),
                selected(),
                (Integer) captureSeconds.getValue(),
                (Integer) repetitions.getValue(),
                (Integer) renderDistance.getValue());
    }

    /** Why {@link #choice()} returned nothing, phrased for whoever is looking at the form. */
    String blocker() {
        if (profiles.getSelectedItem() == null) {
            return "No Modrinth profile found under " + root + ".";
        }
        if (versions.getSelectedItem() == null) {
            return "No installed version found under " + root + "/meta/versions.";
        }
        return "Check at least one mod to benchmark.";
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(760, 520);
    }
}
