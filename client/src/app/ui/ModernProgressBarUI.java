package app.ui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicProgressBarUI;
import java.awt.*;

import static app.Colors.*;

public class ModernProgressBarUI extends BasicProgressBarUI {
    private final Timer timer;
    private int phase = 0;

    public ModernProgressBarUI() {
        timer = new Timer(32, e -> {
            phase = (phase + 3) % 80;
            if (progressBar != null && progressBar.isShowing()) progressBar.repaint();
        });
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        progressBar.setBorder(BorderFactory.createEmptyBorder());
        progressBar.setOpaque(false);
        timer.start();
    }

    @Override
    public void uninstallUI(JComponent c) {
        timer.stop();
        super.uninstallUI(c);
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        return new Dimension(240, 18);
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        if (!(g instanceof Graphics2D g2)) return;
        Insets insets = progressBar.getInsets();
        int x = insets.left;
        int y = insets.top;
        int w = progressBar.getWidth() - insets.left - insets.right;
        int h = progressBar.getHeight() - insets.top - insets.bottom;
        if (w <= 0 || h <= 0) return;

        Graphics2D copy = (Graphics2D) g2.create();
        UIUtils.applyTextRendering(copy);

        int arc = Math.max(12, h);
        copy.setColor(new Color(9, 13, 20));
        copy.fillRoundRect(x, y, w, h, arc, arc);
        copy.setColor(new Color(GRID.getRed(), GRID.getGreen(), GRID.getBlue(), 190));
        copy.drawRoundRect(x, y, w - 1, h - 1, arc, arc);

        int amount = getAmountFull(insets, w, h);
        if (amount > 0) {
            Shape oldClip = copy.getClip();
            copy.setClip(new RoundRectangle(x, y, amount, h, arc, arc));
            GradientPaint fill = new GradientPaint(
                    x, y, new Color(36, 117, 255),
                    x + Math.max(amount, 1), y, new Color(38, 220, 190));
            copy.setPaint(fill);
            copy.fillRoundRect(x, y, w, h, arc, arc);

            copy.setColor(new Color(255, 255, 255, 42));
            for (int sx = x - 80 + phase; sx < x + amount + 80; sx += 80) {
                Polygon stripe = new Polygon();
                stripe.addPoint(sx, y + h);
                stripe.addPoint(sx + 22, y + h);
                stripe.addPoint(sx + 50, y);
                stripe.addPoint(sx + 28, y);
                copy.fillPolygon(stripe);
            }
            copy.setClip(oldClip);
        }

        if (progressBar.isStringPainted()) {
            String text = progressBar.getString();
            Font font = UIUtils.labelFont(11.4f);
            copy.setFont(font);
            FontMetrics fm = copy.getFontMetrics();
            int tx = x + (w - fm.stringWidth(text)) / 2;
            int ty = y + (h - fm.getHeight()) / 2 + fm.getAscent();
            copy.setColor(new Color(0, 0, 0, 120));
            copy.drawString(text, tx + 1, ty + 1);
            copy.setColor(Color.WHITE);
            copy.drawString(text, tx, ty);
        }
        copy.dispose();
    }

    private static class RoundRectangle extends java.awt.geom.RoundRectangle2D.Double {
        RoundRectangle(double x, double y, double w, double h, double arcw, double arch) {
            super(x, y, w, h, arcw, arch);
        }
    }
}
