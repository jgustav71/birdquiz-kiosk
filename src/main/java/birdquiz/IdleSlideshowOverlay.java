package birdquiz;

import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.Timer;

import java.awt.AWTEvent;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;

import java.awt.event.AWTEventListener; // <-- correct package
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import java.net.URL;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;


public final class IdleSlideshowOverlay {
    private final JFrame frame;
    private final OverlayPane overlay;
    private final List<Image> slides;
    private final long idleMs;
    private final long slideMs;

    private final Timer idleTimer;
    private final Timer slideTimer;
    private final Timer fadeTimer;


    // --- Timer and ring
    private javax.swing.Timer countdownTimer;
    private CountdownRing countdownRing;
    private JLabel countdownLabel;
    // --- Fonts
    private static final Font RADIO_BUTTON_FONT = new Font("Arial", Font.PLAIN, 25);
    private static final Font SCORE_FONT        = new Font("Arial", Font.BOLD, 25);
    private static final Font FEEDBACK_FONT     = new Font("Arial", Font.BOLD, 20);
    private static final Font BUTTON_FONT       = new Font("Arial", Font.PLAIN, 12);



    private int current = 0;
    private int next = 1;
    private float alpha = 1f;         // 1 = fully current, 0 = fully next
    private boolean visible = false;

    private final AWTEventListener globalActivityListener;
    private final AtomicBoolean blocked = new AtomicBoolean(false);

    private IdleSlideshowOverlay(JFrame frame,
                                 List<Image> slides,
                                 long idleMs,
                                 long slideMs) {
        this.frame = Objects.requireNonNull(frame, "frame");
        this.slides = Objects.requireNonNull(slides, "slides");
        this.idleMs = Math.max(2000, idleMs);
        this.slideMs = Math.max(2000, slideMs);

        this.overlay = new OverlayPane();
        this.overlay.setOpaque(false);

        // Use the frame's glass pane so we don't disturb layout
        frame.setGlassPane(overlay);
        overlay.setVisible(false);

        // Timers (Swing timers run on EDT)
        idleTimer = new Timer((int) this.idleMs, e -> showOverlay());
        idleTimer.setRepeats(false);

        slideTimer = new Timer((int) this.slideMs, e -> crossfadeToNext());
        slideTimer.setRepeats(true);

        fadeTimer = new Timer(16, e -> { // ~60 fps
            alpha -= 0.04f; // ~25 frames => ~400ms
            if (alpha <= 0f) {
                alpha = 0f;
                current = next;
                next = (current + 1) % slides.size();
                ((Timer) e.getSource()).stop();
            }
            overlay.repaint();
        });
        fadeTimer.setRepeats(true);

        // Any user activity resets idle and hides overlay
        globalActivityListener = evt -> {
            switch (evt.getID()) {
                case MouseEvent.MOUSE_MOVED:
                case MouseEvent.MOUSE_PRESSED:
                case MouseEvent.MOUSE_WHEEL:
                case KeyEvent.KEY_PRESSED:
                case KeyEvent.KEY_TYPED:
                    poke();
                    break;
                default:
                    // ignore
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(
            globalActivityListener,
            AWTEvent.MOUSE_EVENT_MASK
                | AWTEvent.MOUSE_MOTION_EVENT_MASK
                | AWTEvent.MOUSE_WHEEL_EVENT_MASK
                | AWTEvent.KEY_EVENT_MASK
        );

        // Dismiss slideshow on click
        overlay.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { poke(); }
        });
        overlay.setFocusTraversalKeysEnabled(false);

        // Start idle countdown immediately
        idleTimer.start();
    }

    /** Attach to a frame with a list of already-loaded images. */
    public static IdleSlideshowOverlay attachTo(
            JFrame frame,
            List<Image> slides,
            long idleMs,
            long slideMs) {
        if (slides == null || slides.isEmpty()) {
            throw new IllegalArgumentException("slides must not be empty");
        }
        return new IdleSlideshowOverlay(frame, slides, idleMs, slideMs);
    }

    /** Utility: load images from classpath folder + file names. */
    public static List<Image> resources(String base, String... names) {
        ArrayList<Image> out = new ArrayList<>();
        ArrayList<String> missing = new ArrayList<>();

        // Normalize base to leading+trailing slash (classpath absolute)
        if (base == null) base = "";
        if (!base.startsWith("/")) base = "/" + base;
        if (!base.endsWith("/"))  base = base + "/";

        for (String n : names) {
            String path = base + n; // e.g., /images/slideshow_menu/splash1.jpg
            URL url = IdleSlideshowOverlay.class.getResource(path);
            System.out.println("[Slideshow] Looking for " + path + " -> " + url);
            if (url == null) {
                missing.add(path);
                continue;
            }
            out.add(new ImageIcon(url).getImage());
        }

        if (out.isEmpty()) {
            throw new IllegalArgumentException(
                "No slideshow images found on classpath. Missing: " + missing
                + " (Ensure files exist under src/main/resources with the same paths.)"
            );
        }

        if (!missing.isEmpty()) {
            System.err.println("[Slideshow] Missing (but continuing): " + missing);
        }
        return out;
    }

    /** Reset idle timer; hide the overlay if showing. Call from serial RX too. */
    public void poke() {
        if (blocked.get()) return;
        idleTimer.restart();
        if (visible) hideOverlay();
    }

    /** Stop overlay and remove listeners/timers; call when disposing the frame. */
    public void shutdown() {
        blocked.set(true);
        Toolkit.getDefaultToolkit().removeAWTEventListener(globalActivityListener);
        stopAllTimers();
        overlay.setVisible(false);
    }

    // ------- public manual controls -------

    /** Show the slideshow immediately (ignores idle delay). */
    public void showNow() {
        if (visible || slides.isEmpty()) return;
        idleTimer.stop();
        visible = true;
        alpha = 1f;
        overlay.setVisible(true);
        overlay.requestFocusInWindow();
        slideTimer.restart();
        overlay.repaint();
    }

    /** Hide the slideshow immediately and restart idle countdown. */
    public void hideNow() {
        if (!visible) {
            idleTimer.restart();
            return;
        }
        hideOverlay(); // restarts idle timer
    }

    /** Is the slideshow currently visible? */
    public boolean isShowing() { return visible; }

    /** Change the idle delay (ms) and restart countdown. */
    public void setIdleDelayMs(long ms) {
        long m = Math.max(2000, ms);
        idleTimer.setInitialDelay((int) m);
        idleTimer.setDelay((int) m);
        idleTimer.restart();
    }

    /** Change the per-slide duration (ms). */
    public void setSlideDurationMs(long ms) {
        int m = (int) Math.max(2000, ms);
        slideTimer.setDelay(m);
        if (slideTimer.isRunning()) slideTimer.restart();
    }

    public long getIdleDelayMs()      { return idleTimer.getDelay(); }
    public long getSlideDurationMs()  { return slideTimer.getDelay(); }

    // ------- internals -------

    private void showOverlay() {
        if (visible || slides.isEmpty()) return;
        visible = true;
        alpha = 1f;
        overlay.setVisible(true);
        overlay.requestFocusInWindow();
        slideTimer.restart();
        overlay.repaint();
    }

    private void hideOverlay() {
        if (!visible) return;
        visible = false;
        stopAllTimers();
        overlay.setVisible(false);
        idleTimer.restart(); // start counting again
    }

    private void crossfadeToNext() {
        if (!visible || slides.size() <= 1) return;
        alpha = 1f;
        next = (current + 1) % slides.size();
        fadeTimer.restart();
    }

    private void stopAllTimers() {
        fadeTimer.stop();
        slideTimer.stop();
    }

    // ------- glass pane -------

    private final class OverlayPane extends JComponent {
        private final Color scrim = new Color(0, 0, 0, 160);
        private final Font captionFont = new Font("Arial", Font.BOLD, 24);

        @Override protected void paintComponent(Graphics g) {
            if (!visible) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();

            // dim the app
            g2.setColor(scrim);
            g2.fillRect(0, 0, w, h);

            // draw current and next with crossfade
            Image curImg = slides.get(current);
            drawImageLetterboxed(g2, curImg, w, h, 1f);

            if (slides.size() > 1 && alpha < 1f) {
                Image nextImg = slides.get(next);
                drawImageLetterboxed(g2, nextImg, w, h, 1f - alpha);
            }

            // caption
            String caption = "Touch any button or press a key to start";
            g2.setFont(captionFont);
            FontMetrics fm = g2.getFontMetrics();
            int cx = (w - fm.stringWidth(caption)) / 2;
            int cy = h - fm.getHeight() - 30;
            g2.setColor(new Color(255, 255, 255, 220));
            g2.drawString(caption, cx, cy);

            g2.dispose();
        }

        private void drawImageLetterboxed(Graphics2D g2, Image img, int w, int h, float a) {
            if (img == null) return;
            int iw = img.getWidth(null), ih = img.getHeight(null);
            if (iw <= 0 || ih <= 0) return;

            double ar = iw / (double) ih;
            int dw = w, dh = (int) (w / ar);
            if (dh > h) { dh = h; dw = (int) (h * ar); }

            int x = (w - dw) / 2;
            int y = (h - dh) / 2;

            Composite old = g2.getComposite();
            g2.setComposite(AlphaComposite.SrcOver.derive(a));
            g2.drawImage(img, x, y, dw, dh, null);
            g2.setComposite(old);
        }
    }
}
