package cx.mia.lucent.laymark.runner.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import javax.swing.border.Border;

/** The window's palette and the handful of components built out of it. */
final class Theme {

    private Theme() {}

    static final Color BACKGROUND = new Color(0x0F1115);
    static final Color CARD = new Color(0x171A20);
    static final Color RAISED = new Color(0x1F242C);
    static final Color LINE = new Color(0x2A303A);
    static final Color TEXT = new Color(0xE6E8EB);
    static final Color MUTED = new Color(0x8B939F);
    static final Color ACCENT = new Color(0x2F6FDE);
    static final Color GOOD = new Color(0x22C55E);
    static final Color BAD = new Color(0xEF4444);

    static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    /** Applied once, before any component is built. */
    static void install() {
        FlatDarkLaf.setup();
        UIManager.put("Component.focusWidth", 0);
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("ScrollBar.thumbArc", 8);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("ScrollPane.background", BACKGROUND);
        UIManager.put("Viewport.background", BACKGROUND);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("TextArea.background", CARD);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("ComboBox.background", RAISED);
        UIManager.put("List.background", RAISED);
    }

    /** A titled panel with the flat, rounded border the rest of the window is drawn against. */
    static JPanel card(String title) {
        JPanel panel = new JPanel();
        panel.setBackground(CARD);
        panel.setBorder(
                BorderFactory.createCompoundBorder(
                        new RoundedBorder(LINE, 10), BorderFactory.createEmptyBorder(10, 12, 12, 12)));
        if (title != null) {
            panel.setLayout(new java.awt.BorderLayout(0, 8));
            panel.add(heading(title), java.awt.BorderLayout.NORTH);
        }
        return panel;
    }

    static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(TEXT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        return label;
    }

    static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        return label;
    }

    static JLabel mono(String text, Color colour) {
        JLabel label = new JLabel(text);
        label.setFont(MONO);
        label.setForeground(colour);
        return label;
    }

    static JButton button(String text, boolean primary) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.putClientProperty("JButton.buttonType", "roundRect");
        button.setBackground(primary ? ACCENT : RAISED);
        button.setForeground(primary ? Color.WHITE : TEXT);
        button.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        return button;
    }

    /** A borderless scroll pane, so a card's own outline is the only one drawn. */
    static JScrollPane scroll(Component view) {
        JScrollPane pane = new JScrollPane(view);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.getViewport().setOpaque(false);
        pane.setOpaque(false);
        return pane;
    }

    static JPanel separator() {
        JPanel line = new JPanel();
        line.setBackground(LINE);
        line.setPreferredSize(new Dimension(1, 22));
        line.setMaximumSize(new Dimension(1, 22));
        return line;
    }

    static void pad(JComponent component, int top, int left, int bottom, int right) {
        component.setBorder(BorderFactory.createEmptyBorder(top, left, bottom, right));
    }

    /** The filled circle beside a status word, and the ring beside a queued candidate. */
    static final class Dot extends JComponent {

        private Color colour;
        private boolean filled;

        Dot(Color colour, boolean filled) {
            this.colour = colour;
            this.filled = filled;
            setPreferredSize(new Dimension(14, 14));
            setMaximumSize(new Dimension(14, 14));
        }

        void set(Color newColour, boolean nowFilled) {
            this.colour = newColour;
            this.filled = nowFilled;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(colour);
            if (filled) {
                g2.fillOval(1, 1, 11, 11);
            } else {
                g2.setStroke(new java.awt.BasicStroke(1.6f));
                g2.drawOval(1, 1, 11, 11);
            }
            g2.dispose();
        }
    }

    /** Rounded single-pixel outline; Swing has no such border of its own. */
    static final class RoundedBorder implements Border {

        private final Color colour;
        private final int radius;

        RoundedBorder(Color colour, int radius) {
            this.colour = colour;
            this.radius = radius;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(colour);
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius);
            g2.dispose();
        }

        @Override
        public java.awt.Insets getBorderInsets(Component c) {
            return new java.awt.Insets(1, 1, 1, 1);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }
}
