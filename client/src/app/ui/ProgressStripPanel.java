package app.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

import static app.Colors.BG;

public class ProgressStripPanel extends JPanel {
    private static final int TARGET_HEIGHT = 30;
    private final Timer animation;
    private boolean opening = false;
    private float alpha = 0.0f;
    private int animatedHeight = 0;

    public ProgressStripPanel() {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(new EmptyBorder(4, 10, 6, 10));
        animation = new Timer(16, e -> tick());
    }

    public void showAnimated() {
        opening = true;
        setVisible(true);
        animation.start();
    }

    public void hideAnimated() {
        opening = false;
        animation.start();
    }

    private void tick() {
        float alphaStep = opening ? 0.16f : -0.16f;
        int heightStep = opening ? 5 : -5;
        alpha = clamp(alpha + alphaStep, 0.0f, 1.0f);
        animatedHeight = Math.max(0, Math.min(TARGET_HEIGHT, animatedHeight + heightStep));
        revalidate();
        repaint();

        if (opening && alpha >= 1.0f && animatedHeight >= TARGET_HEIGHT) {
            animation.stop();
        } else if (!opening && alpha <= 0.0f && animatedHeight <= 0) {
            animation.stop();
            setVisible(false);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        size.height = animatedHeight;
        return size;
    }

    @Override
    public void paint(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setComposite(AlphaComposite.SrcOver.derive(alpha));
        g2.setColor(BG);
        g2.fillRect(0, 0, getWidth(), getHeight());
        super.paint(g2);
        g2.dispose();
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
