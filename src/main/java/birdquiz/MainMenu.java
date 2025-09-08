package birdquiz;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

import com.fazecast.jSerialComm.SerialPort;

public class MainMenu extends JFrame implements ActionListener {
    // Idle slideshow overlay for the menu
     private IdleSlideshowOverlay slideshow;

    // ---- UI: category buttons ----
    private CircleButton songbirdsBtn;  // blue
    private CircleButton ducksBtn;      // green
    private CircleButton raptorsBtn;    // yellow
    private CircleButton shorebirdsBtn; // white

    // ---- Serial state ----
    private SerialPort serialPort;
    private final StringBuilder serialBuf = new StringBuilder(512);
    private String connectedPortName = "none";

    // ---- Debounce for ESP tokens ----
    private final Map<String, Long> debounceMap = new HashMap<>();
    private static final long DEBOUNCE_MS = 200;

    // ---- UI: status + watchdog ----
    private JLabel statusLabel;
    private Timer serialWatchdog; // tries reconnect periodically

    public MainMenu() {
        setTitle("Bird Quiz - Main Menu");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLayout(new BorderLayout());

        // --- Center: grid of big circular buttons ---
        JPanel center = new JPanel(new GridBagLayout());
        center.setBorder(new EmptyBorder(30, 30, 10, 30));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(20, 20, 20, 20);

        songbirdsBtn = new CircleButton("Songbirds", new Color(35,116,232), Color.WHITE,
                "/images/hover-songbirds.png");
        ducksBtn     = new CircleButton("Ducks",     new Color(35,166, 92), Color.WHITE,
                "/images/hover-ducks.png");
        raptorsBtn   = new CircleButton("Raptors",   new Color(236,202,49), Color.WHITE,
                "/images/hover-raptors.png");
        shorebirdsBtn = new CircleButton("Shorebirds", Color.WHITE, Color.BLACK,
                "/images/hover-shorebirds.png");

        gbc.gridx = 0; gbc.gridy = 0; center.add(songbirdsBtn, gbc);
        gbc.gridx = 1; gbc.gridy = 0; center.add(ducksBtn, gbc);
        gbc.gridx = 2; gbc.gridy = 0; center.add(raptorsBtn, gbc);
        gbc.gridx = 1; gbc.gridy = 1; center.add(shorebirdsBtn, gbc);

        add(center, BorderLayout.CENTER);

        statusLabel = new JLabel("Serial: none", SwingConstants.RIGHT);
        statusLabel.setBorder(new EmptyBorder(0, 12, 12, 12));
        add(statusLabel, BorderLayout.SOUTH);

        // Hover + press feedback + SFX
        installHoverAndPress(songbirdsBtn);
        installHoverAndPress(ducksBtn);
        installHoverAndPress(raptorsBtn);
        installHoverAndPress(shorebirdsBtn);

        // Click handlers
        songbirdsBtn.addActionListener(this);
        ducksBtn.addActionListener(this);
        raptorsBtn.addActionListener(this);
        shorebirdsBtn.addActionListener(this);

        // Serial open + watchdog
        autoConnectSerial();
        serialWatchdog = new Timer(3000, e -> {
            if (serialPort == null || !serialPort.isOpen()) {
                statusLabel.setText("Serial: reconnecting…");
                showTemporaryMessage("Serial: reconnecting…");
                autoConnectSerial();
            }
        });
        serialWatchdog.start();

        // Reconnect when menu regains focus; release on close
        addWindowListener(new WindowAdapter() {
    @Override public void windowOpened(WindowEvent e)       { ensureSerial(); }
    @Override public void windowActivated(WindowEvent e)    { ensureSerial(); }
    @Override public void windowDeiconified(WindowEvent e)  { ensureSerial(); }

    @Override public void windowClosing(WindowEvent e) {
        if (slideshow != null) { slideshow.shutdown(); slideshow = null; }
        closeSerial();
    }

    @Override public void windowClosed(WindowEvent e) {
        if (slideshow != null) { slideshow.shutdown(); slideshow = null; }
        closeSerial();
    }
});


        setVisible(true);

// Attach an idle slideshow overlay: show after 2 minutes idle, switch every 6 seconds
slideshow = IdleSlideshowOverlay.attachTo(
    this,
    IdleSlideshowOverlay.resources(
        "/images/slideshow_menu",     // put your images here (classpath)
        "splash1.jpg",
        "splash2.jpg",
        "splash3.jpg"
    ),
    120_000L,   // idle delay (2 minutes)
    6_000L      // slide duration (6 seconds)
);



    }

    private void ensureSerial() {
        if (serialWatchdog != null && !serialWatchdog.isRunning()) serialWatchdog.start();
        if (serialPort == null || !serialPort.isOpen()) autoConnectSerial();
    }

    // ---- Hover + Press visual/audio feedback ----
    private void installHoverAndPress(CircleButton btn) {
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                btn.setHovered(true);
                btn.repaint();
                try { SoundUtil.playSound("ui-hover.wav"); } catch (Exception ignore) {}
            }
            @Override public void mouseExited(MouseEvent e) {
                btn.setHovered(false);
                btn.repaint();
            }
            @Override public void mousePressed(MouseEvent e) {
                btn.setPressed(true);
                btn.repaint();
                try { SoundUtil.playSound("ui-click.wav"); } catch (Exception ignore) {}
            }
            @Override public void mouseReleased(MouseEvent e) {
                btn.setPressed(false);
                btn.repaint();
            }
        });
    }

    // ---- Serial connection (listener-based), with CR/LF normalize + debounce ----
    private void autoConnectSerial() {
        // Try the last known good port first (if we have one)
        if (connectedPortName != null && !"none".equalsIgnoreCase(connectedPortName)) {
            SerialPort preferred = SerialPort.getCommPort(connectedPortName);
            if (tryOpenPort(preferred)) return;
        }

        // Fallback: scan available ports and open the first that works
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length == 0) {
            System.out.println("[MENU] No serial ports found.");
            statusLabel.setText("Serial: none (no ports)");
            showTemporaryMessage("No serial ports found");
            return;
        }
        for (SerialPort p : ports) {
            if (tryOpenPort(p)) return;
        }

        statusLabel.setText("Serial: none (open failed)");
        showTemporaryMessage("Serial: open failed");
    }

    private boolean tryOpenPort(SerialPort p) {
        if (p == null) return false;
        try {
            System.out.println("[MENU] Trying " + p.getSystemPortName());
            p.setBaudRate(115200);
            p.setNumDataBits(8);
            p.setNumStopBits(SerialPort.ONE_STOP_BIT);
            p.setParity(SerialPort.NO_PARITY);
            p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);
            try { p.setDTR(); } catch (Throwable ignore) {}
            try { p.setRTS(); } catch (Throwable ignore) {}
            if (!p.openPort()) {
                System.out.println("[MENU] Failed to open " + p.getSystemPortName());
                return false;
            }

            serialPort = p;
            connectedPortName = serialPort.getSystemPortName();
            statusLabel.setText("Serial: " + connectedPortName);
            System.out.println("[MENU] Opened " + connectedPortName);
            showTemporaryMessage("Connected to " + connectedPortName);

            attachSerialListener(serialPort);
            return true;
        } catch (Exception ex) {
            ex.printStackTrace();
            try { if (p.isOpen()) p.closePort(); } catch (Exception ignore) {}
            return false;
        }
    }

    private void attachSerialListener(SerialPort port) {
        port.addDataListener(new com.fazecast.jSerialComm.SerialPortDataListener() {
            @Override public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
            }
            @Override public void serialEvent(com.fazecast.jSerialComm.SerialPortEvent event) {
                try {
                    if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) return;
                    int available;
                    while ((available = port.bytesAvailable()) > 0) {
                        byte[] buf = new byte[Math.min(available, 256)];
                        int n = port.readBytes(buf, buf.length);
                        if (n <= 0) break;
                        for (int i = 0; i < n; i++) {
                            char c = (char)(buf[i] & 0xFF);
                            if (c == '\r') c = '\n';
                            serialBuf.append(c);
                        }
                        processSerialBuffer();
                        if (serialBuf.length() > 4096) {
                            serialBuf.delete(0, serialBuf.length() - 1024);
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
    }

    private void processSerialBuffer() {
        int nl;
        while ((nl = serialBuf.indexOf("\n")) >= 0) {
            String line = serialBuf.substring(0, nl).trim().toLowerCase();
            serialBuf.delete(0, nl + 1);
            if (line.isEmpty()) continue;
            if (line.startsWith("button pressed:") || line.startsWith("button released:")) continue;

            long now = System.currentTimeMillis();
            Long last = debounceMap.get(line);
            if (last != null && (now - last) < DEBOUNCE_MS) continue;
            debounceMap.put(line, now);

            handleEspCommand(line);
        }
    }
  // Any ESP/serial activity should cancel or hide the slideshow
    private void handleEspCommand(String token) {
          if (slideshow != null) slideshow.poke();
        switch (token) {
            case "blue":
                System.out.println("[MENU] ESP -> blue (Songbirds)");
                SwingUtilities.invokeLater(() -> songbirdsBtn.doClick());
                break;
            case "green":
                System.out.println("[MENU] ESP -> green (Ducks)");
                SwingUtilities.invokeLater(() -> ducksBtn.doClick());
                break;
            case "yellow":
                System.out.println("[MENU] ESP -> yellow (Raptors)");
                SwingUtilities.invokeLater(() -> raptorsBtn.doClick());
                break;
            case "submit": // your ESP sends "submit" for the bottom button
            case "white":  // (optional) if firmware uses white for bottom
                System.out.println("[MENU] ESP -> submit (Shorebirds)");
                SwingUtilities.invokeLater(() -> shorebirdsBtn.doClick());
                break;
            default:
                System.out.println("[MENU] ESP unknown token: " + token);
        }
    }

    private void closeSerial() {
        try { if (serialWatchdog != null) serialWatchdog.stop(); } catch (Exception ignore) {}
        try {
            if (serialPort != null) {
                robustClose(serialPort);
                serialPort = null;
            }
        } catch (Exception ignore) {}
    }

    // ---- click handling: launch quiz safely (async) ----
    @Override
    public void actionPerformed(ActionEvent e) {
        Object src = e.getSource();
        String table = null;
        String sound = null;

        if (src == songbirdsBtn)      { table = "songbirds";   sound = "songbirds.wav"; }
        else if (src == ducksBtn)     { table = "ducks";       sound = "ducks.wav"; }
        else if (src == raptorsBtn)   { table = "raptors";     sound = "raptors.wav"; }
        else if (src == shorebirdsBtn){ table = "shore_birds"; sound = "shorebirds.wav"; }

        if (table == null) return;

        try { if (sound != null) SoundUtil.playSound(sound); } catch (Exception ignore) {}

        final String tableFinal = table;
        final String portNameFinal = connectedPortName; // may be "none" if not connected

        showTemporaryMessage("Launching " + tableFinal +
                (portNameFinal != null && !"none".equalsIgnoreCase(portNameFinal) ? " (" + portNameFinal + ")" : "") + "…");

        // Release serial first (robustly) so quiz can own it
        robustClose(serialPort);
        serialPort = null;

        // Launch the quiz without blocking the EDT
        launchQuizAsync(tableFinal, portNameFinal);
    }

    // === Safe async launcher (with small loading chip + full stack dialog) ===
    private void launchQuizAsync(String table, String portName) {
        final JDialog loading = new JDialog(this, false);
        loading.setUndecorated(true);
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(10, 16, 10, 16));
        p.setBackground(new Color(40, 40, 40));
        JLabel l = new JLabel("Launching " + table + "…");
        l.setForeground(Color.WHITE);
        l.setFont(new Font("Arial", Font.PLAIN, 14));
        p.add(l, BorderLayout.CENTER);
        loading.setContentPane(p);
        loading.pack();
        loading.setLocationRelativeTo(this);
        loading.setAlwaysOnTop(true);
        loading.setVisible(true);

        new Thread(() -> {
            BirdQuizGUI quiz = null;
            Throwable error = null;
            try {
                // BirdQuizGUI(String tableName, String serialPortName, String firstName, String email)
                quiz = new BirdQuizGUI(table, portName, "", "");
            } catch (Throwable ex) {
                error = ex;
            }
            final BirdQuizGUI quizFinal = quiz;
             if (slideshow != null) { slideshow.shutdown(); slideshow = null; }

            final Throwable errFinal = error;
            SwingUtilities.invokeLater(() -> {
                loading.dispose();
                if (errFinal != null) {
                    showStackDialog("Failed to open quiz", errFinal);
                    autoConnectSerial(); // leave menu usable
                    return;
                }
                if (quizFinal != null) {
                    quizFinal.setLocationRelativeTo(null);
                    quizFinal.setVisible(true);
                    quizFinal.toFront();
                    dispose(); // close the menu only after quiz is visible
                }
            });
        }, "QuizLauncher").start();
    }

    private void showStackDialog(String title, Throwable t) {
        // Console + dialog
        t.printStackTrace();
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        JTextArea area = new JTextArea(sw.toString(), 18, 80);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(area);

        JDialog d = new JDialog(this, title, true);
        d.setAlwaysOnTop(true);
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        d.setContentPane(sp);
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    // ---- Robust close without PURGE_* (portable across jSerialComm versions) ----
    private void robustClose(SerialPort p) {
        if (p == null) return;
        try {
            try { p.removeDataListener(); } catch (Throwable ignore) {}
            try { p.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0); } catch (Throwable ignore) {}

            drainPort(p);

            try { p.clearDTR(); } catch (Throwable ignore) {}
            try { p.clearRTS(); } catch (Throwable ignore) {}

            try { Thread.sleep(150); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            if (p.isOpen()) {
                System.out.println("[MENU] Closing " + p.getSystemPortName());
                p.closePort();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // Read & discard pending bytes without PURGE_* constants
    private static void drainPort(SerialPort p) {
        if (p == null) return;
        byte[] buf = new byte[1024];
        long end = System.currentTimeMillis() + 150;
        try {
            while (System.currentTimeMillis() < end) {
                int avail = p.bytesAvailable();
                if (avail <= 0) { Thread.sleep(10); continue; }
                int toRead = Math.min(avail, buf.length);
                p.readBytes(buf, toRead);
            }
        } catch (Throwable ignore) {}
    }

    // ---- circular button with hover image + lighter hue on press ----
    private static class CircleButton extends JButton {
        private final Color baseFill;
        private final Color textColor;
        private boolean hovered = false;
        private boolean pressed = false;
        private BufferedImage hoverOverlay; // optional

        CircleButton(String text, Color fill, Color textColor, String hoverOverlayPath) {
            super(text);
            this.baseFill = fill;
            this.textColor = textColor;
            setPreferredSize(new Dimension(240, 240));
            setFont(new Font("Arial", Font.BOLD, 24));
            setForeground(textColor);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            try {
                if (hoverOverlayPath != null) {
                    var url = getClass().getResource(hoverOverlayPath);
                    if (url != null) hoverOverlay = ImageIO.read(url);
                }
            } catch (Exception ignore) {}
        }

        void setHovered(boolean v) { this.hovered = v; }
        void setPressed(boolean v) { this.pressed = v; }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = Math.min(getWidth(), getHeight()) - 8;
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2;

            // shadow
            g2.setColor(new Color(0,0,0,30));
            g2.fillOval(x + 3, y + 3, d, d);

            // fill (lighter when pressed)
            Color fill = pressed ? lighten(baseFill, 0.18f) : baseFill;
            g2.setColor(fill);
            g2.fillOval(x, y, d, d);

            // border
            g2.setStroke(new BasicStroke(3f));
            g2.setColor(Color.WHITE);
            g2.drawOval(x, y, d, d);

            // text
            g2.setColor(textColor);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            String t = getText();
            int tw = fm.stringWidth(t);
            int th = fm.getAscent();
            g2.drawString(t, getWidth()/2 - tw/2, getHeight()/2 + th/4);

            // hover overlay image or soft highlight
            if (hovered) {
                if (hoverOverlay != null) {
                    int sz = (int)(d * 0.5);
                    int ox = getWidth()/2 - sz/2;
                    int oy = getHeight()/2 - sz/2;
                    g2.setComposite(AlphaComposite.SrcOver.derive(0.85f));
                    g2.drawImage(hoverOverlay, ox, oy, sz, sz, null);
                } else {
                    g2.setComposite(AlphaComposite.SrcOver.derive(0.12f));
                    g2.setColor(Color.WHITE);
                    g2.fillOval(x, y, d, d);
                }
            }

            g2.dispose();
        }

        @Override
        public boolean contains(int px, int py) {
            int d = Math.min(getWidth(), getHeight()) - 8;
            int cx = getWidth() / 2, cy = getHeight() / 2;
            int r = d / 2;
            int dx = px - cx, dy = py - cy;
            return dx*dx + dy*dy <= r*r;
        }

        private static Color lighten(Color c, float amt) {
            float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
            hsb[2] = Math.min(1f, hsb[2] + amt); // brighten value
            int rgb = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        }
    }

    // ---- Toast helper (nice rounded chip, auto-fades) ----
    private void showTemporaryMessage(String message) {
        final JWindow popup = new JWindow();
        popup.setBackground(new Color(0, 0, 0, 0)); // transparent window

        final JPanel toast = new JPanel() {
            private final int arc = 18;     // corner radius
            private final int shadow = 14;  // shadow thickness
            private final int yOffset = 3;  // drop shadow offset
            @Override
            protected void paintComponent(Graphics g) {
                float alpha = 1f;
                Object val = getClientProperty("alphaVal");
                if (val instanceof Float) alpha = (Float) val;

                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setComposite(AlphaComposite.SrcOver.derive(alpha));

                int w = getWidth(), h = getHeight();

                // soft shadow
                for (int i = shadow; i > 0; i--) {
                    float ringAlpha = 0.20f * (i / (float) shadow);
                    g2.setColor(new Color(0f, 0f, 0f, ringAlpha));
                    g2.fillRoundRect(i, i + yOffset, w - 2 * i, h - 2 * i, arc + i, arc + i);
                }

                // body
                g2.setColor(new Color(0.18f, 0.18f, 0.18f, 0.92f));
                g2.fillRoundRect(shadow, shadow, w - 2 * shadow, h - 2 * shadow, arc, arc);

                g2.dispose();
            }
        };
        toast.setOpaque(false);
        toast.setLayout(new BorderLayout());

        JLabel label = new JLabel(message);
        label.setForeground(Color.WHITE);
        label.setFont(new Font("Arial", Font.PLAIN, 15));
        label.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        toast.add(label, BorderLayout.CENTER);

        popup.setContentPane(toast);
        popup.pack();

        // position: bottom-center of the frame (with fallback)
        try {
            Point parent = this.getLocationOnScreen();
            int x = parent.x + (this.getWidth() - popup.getWidth()) / 2;
            int y = parent.y + this.getHeight() - popup.getHeight() - 60;
            popup.setLocation(x, y);
        } catch (IllegalComponentStateException ignored) {
            Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
            int x = (scr.width - popup.getWidth()) / 2;
            int y = (int) (scr.height * 0.85) - popup.getHeight() / 2;
            popup.setLocation(x, y);
        }

        popup.setAlwaysOnTop(true);
        popup.setVisible(true);

        // fade out after ~2s; fade ~900ms
        javax.swing.Timer fade = new javax.swing.Timer(30, null);
        fade.addActionListener(e -> {
            float a = 1f;
            Object v = toast.getClientProperty("alphaVal");
            if (v instanceof Float) a = (Float) v;
            a -= 0.07f;
            toast.putClientProperty("alphaVal", a);
            toast.repaint();
            if (a <= 0f) {
                ((javax.swing.Timer) e.getSource()).stop();
                popup.dispose();
            }
        });

        new javax.swing.Timer(2000, e -> {
            toast.putClientProperty("alphaVal", 1f);
            fade.start();
            ((javax.swing.Timer) e.getSource()).stop();
        }).start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MainMenu::new);
    }
}
