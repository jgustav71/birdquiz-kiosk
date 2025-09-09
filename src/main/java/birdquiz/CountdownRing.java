package birdquiz;

import javax.swing.*;
import java.awt.*;

/**
 * Standalone countdown ring component usable by both MainMenu and BirdQuizGUI.
 */
@SuppressWarnings("serial")
public class CountdownRing extends JComponent {
    private int total = 60;
    private int remaining = 60;

    public void setTotalSeconds(int s) {
        total = Math.max(1, s);
        repaint();
    }

    public void setSecondsRemaining(int s) {
        remaining = Math.max(0, Math.min(s, total));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        int d = Math.min(w, h) - 4;
        int x = (w - d) / 2;
        int y = (h - d) / 2;

        // colors
        Color bg     = new Color(0, 0, 0, 40);
        Color ok     = new Color(50, 160, 90);
        Color warn   = new Color(240, 170, 50);
        Color urgent = new Color(220, 70, 70);
        Color ring   = remaining <= 10 ? urgent : (remaining <= 30 ? warn : ok);

        g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(bg);
        g2.drawOval(x, y, d, d);

        float pct = total > 0 ? (remaining / (float) total) : 0f;
        int angle = Math.round(360f * pct);
        g2.setColor(ring);
        g2.drawArc(x, y, d, d, 90, -angle);

        String txt = String.format("%02d:%02d", remaining / 60, remaining % 60);
        g2.setFont(new Font("Arial", Font.BOLD, 14));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(new Color(0, 0, 0, 180));
        g2.drawString(txt, w / 2 - fm.stringWidth(txt) / 2, h / 2 + fm.getAscent() / 2 - 2);
        g2.dispose();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(72, 72);
    }
}
