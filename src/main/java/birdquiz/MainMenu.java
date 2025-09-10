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
    private CircleButton songbirdsBtn;   // blue
    private CircleButton ducksBtn;       // green
    private CircleButton raptorsBtn;     // yellow
    private CircleButton shorebirdsBtn;  // white

    // --- MainMenu serial fields (new, robust path) ---
    private SerialPort menuPort;
    private javax.swing.Timer menuWatchdog;     // reconnect loop
    private long lastMenuSerialAttemptMs = 0L;  // throttle reconnect attempts
    private final StringBuilder menuSerialBuf = new StringBuilder(512);
    private final java.util.Map<String, Long> menuDebounce = new java.util.HashMap<>();
    private static final long MENU_DEBOUNCE_MS = 200;

    // Small status UI (optional)
    private JLabel menuSerialStatusLabel;   // “Serial: ...”
    private JButton menuResetSerialButton;  // if you add a button in future

    // Remember user choice; "auto" means auto-detect
    private String menuPortName = "auto";

    // Footer status
    private JLabel statusLabel;


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

        songbirdsBtn  = new CircleButton("Songbirds",  new Color(35,116,232), Color.WHITE,  "/images/hover-songbirds.png");
        ducksBtn      = new CircleButton("Ducks",      new Color(35,166, 92), Color.WHITE,  "/images/hover-ducks.png");
        raptorsBtn    = new CircleButton("Raptors",    new Color(236,202,49), Color.WHITE,  "/images/hover-raptors.png");
        shorebirdsBtn = new CircleButton("Shorebirds", Color.WHITE,           Color.BLACK,  "/images/hover-shorebirds.png");

        gbc.gridx = 0; gbc.gridy = 0; center.add(songbirdsBtn, gbc);
        gbc.gridx = 1; gbc.gridy = 0; center.add(ducksBtn, gbc);
        gbc.gridx = 2; gbc.gridy = 0; center.add(raptorsBtn, gbc);
        gbc.gridx = 1; gbc.gridy = 1; center.add(shorebirdsBtn, gbc);

        add(center, BorderLayout.CENTER);

        // Footer status (also used as serial status)
        statusLabel = new JLabel("Serial: none", SwingConstants.RIGHT);
        statusLabel.setBorder(new EmptyBorder(0, 12, 12, 12));
        add(statusLabel, BorderLayout.SOUTH);
        menuSerialStatusLabel = statusLabel;

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

        // --- Robust serial bring-up + watchdog ---
        openMenuSerial();
        if (menuWatchdog != null) {
            try { menuWatchdog.stop(); } catch (Exception ignore) {}
        }
        menuWatchdog = new javax.swing.Timer(3000, e -> {
            boolean needPort = true; // set false if user disables serial in a future settings UI
            boolean open = (menuPort != null && menuPort.isOpen());
            if (needPort && !open) reconnectMenuSerial();
        });
        menuWatchdog.start();

        // --- Window lifecycle ---
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (slideshow != null) { slideshow.shutdown(); slideshow = null; }
                if (menuWatchdog != null) menuWatchdog.stop();
                robustCloseMenuSerial();
            }
            @Override public void windowClosed(WindowEvent e) {
                if (slideshow != null) { slideshow.shutdown(); slideshow = null; }
                if (menuWatchdog != null) menuWatchdog.stop();
                robustCloseMenuSerial();
            }
            @Override public void windowActivated(WindowEvent e) {
                if (menuPort == null || !menuPort.isOpen()) reconnectMenuSerial();
            }
            @Override public void windowDeiconified(WindowEvent e) {
                if (menuPort == null || !menuPort.isOpen()) reconnectMenuSerial();
            }
        });

        // Idle slideshow overlay (2 min idle, 6 sec per slide)
        slideshow = IdleSlideshowOverlay.attachTo(
            this,
            IdleSlideshowOverlay.resources(
                "/images/slideshow_menu",
                "splash1.jpg",
                "splash2.jpg",
                "splash3.jpg"
            ),
            120_000L,
            6_000L
        );

        setVisible(true);
    }






        // =========================
    // Serial helpers
    // =========================

    private void setMenuSerialStatus(String text) {
        if (menuSerialStatusLabel != null) menuSerialStatusLabel.setText(text);
    }

    private void setMenuResetEnabled(boolean enabled) {
        if (menuResetSerialButton != null) menuResetSerialButton.setEnabled(enabled);
    }

    private SerialPort chooseAutoPort() {
        SerialPort[] ports = SerialPort.getCommPorts();
        SerialPort fallback = (ports.length > 0 ? ports[0] : null);

        for (SerialPort p : ports) {
            String sys  = String.valueOf(p.getSystemPortName()).toLowerCase();
            String desc = String.valueOf(p.getDescriptivePortName()).toLowerCase();
            String info = String.valueOf(p.getPortDescription()).toLowerCase();

            boolean looksUsb = sys.contains("usb") || sys.contains("com")
                            || desc.contains("usb") || info.contains("usb");
            boolean looksEsp = desc.contains("cp210") || desc.contains("ch340")
                            || desc.contains("silicon labs") || desc.contains("esp")
                            || info.contains("cp210") || info.contains("ch340")
                            || info.contains("silicon labs") || info.contains("esp");

            if (looksUsb && looksEsp) return p;
        }
        return fallback;
    }

    private void openMenuSerial() {
        try {
            SerialPort target = "auto".equalsIgnoreCase(menuPortName)
                    ? chooseAutoPort()
                    : SerialPort.getCommPort(menuPortName);

            if (target == null) {
                setMenuSerialStatus("Serial: none (no ports)");
                setMenuResetEnabled(true);
                return;
            }

            menuPort = target;
            menuPort.setBaudRate(115200);
            menuPort.setNumDataBits(8);
            menuPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
            menuPort.setParity(SerialPort.NO_PARITY);
            menuPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);
            try { menuPort.setDTR(); } catch (Throwable ignore) {}
            try { menuPort.setRTS(); } catch (Throwable ignore) {}

            if (!menuPort.openPort()) {
                setMenuSerialStatus("Serial: open failed (" + menuPort.getSystemPortName() + ")");
                setMenuResetEnabled(true);
                return;
            }

            setMenuSerialStatus("Serial: " + menuPort.getSystemPortName());
            setMenuResetEnabled(true);
            attachMenuSerialListener(menuPort);

            // Optional: say hello so the ESP can know it's MainMenu
            writeMenuSerial("hello_menu");
        } catch (Exception ex) {
            ex.printStackTrace();
            setMenuSerialStatus("Serial: error (" + ex.getMessage() + ")");
            setMenuResetEnabled(true);
            menuPort = null;
        }
    }

    private void attachMenuSerialListener(SerialPort port) {
        port.addDataListener(new com.fazecast.jSerialComm.SerialPortDataListener() {
            @Override public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
            }
            @Override public void serialEvent(com.fazecast.jSerialComm.SerialPortEvent ev) {
                if (ev.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) return;
                try {
                    int avail;
                    while ((avail = port.bytesAvailable()) > 0) {
                        byte[] buf = new byte[Math.min(avail, 256)];
                        int n = port.readBytes(buf, buf.length);
                        if (n <= 0) break;
                        for (int i = 0; i < n; i++) {
                            char c = (char)(buf[i] & 0xFF);
                            if (c == '\r') c = '\n';
                            menuSerialBuf.append(c);
                        }
                        processMenuSerialBuffer();
                        if (menuSerialBuf.length() > 4096) {
                            menuSerialBuf.delete(0, menuSerialBuf.length() - 1024);
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    setMenuSerialStatus("Serial: error (" + ex.getMessage() + ")");
                }
            }
        });
    }

    private void processMenuSerialBuffer() {
        int nl;
        while ((nl = menuSerialBuf.indexOf("\n")) >= 0) {
            String token = menuSerialBuf.substring(0, nl).trim().toLowerCase();
            menuSerialBuf.delete(0, nl + 1);
            if (token.isEmpty()) continue;
            if (token.startsWith("button pressed:") || token.startsWith("button released:")) continue;

            long now = System.currentTimeMillis();
            Long last = menuDebounce.get(token);
            if (last != null && (now - last) < MENU_DEBOUNCE_MS) continue;
            menuDebounce.put(token, now);

            handleMenuToken(token);
        }
    }

    private void handleMenuToken(String token) {
        // Any serial activity should keep the screen awake / pause slideshow
        if (slideshow != null) slideshow.poke();

        System.out.println("[MENU] token: " + token);
        setMenuSerialStatus("Serial: " + (menuPort != null ? menuPort.getSystemPortName() : "?") + " (" + token + ")");

        switch (token) {
            case "blue":   SwingUtilities.invokeLater(() -> songbirdsBtn.doClick());  break; // Songbirds
            case "green":  SwingUtilities.invokeLater(() -> ducksBtn.doClick());      break; // Ducks
            case "yellow": SwingUtilities.invokeLater(() -> raptorsBtn.doClick());    break; // Raptors
            case "white":
            case "submit":
            case "enter":
            case "ok":     SwingUtilities.invokeLater(() -> shorebirdsBtn.doClick()); break; // Shorebirds
            case "reconnect":
                SwingUtilities.invokeLater(this::reconnectMenuSerial);
                break;
            default:
                System.out.println("[MENU] unknown token: " + token);
        }
    }

    private void writeMenuSerial(String message) {
        try {
            if (menuPort != null && menuPort.isOpen()) {
                String m = message + "\n";
                menuPort.writeBytes(m.getBytes(), m.length());
            }
        } catch (Exception ignored) {}
    }

    private void reconnectMenuSerial() {
        long now = System.currentTimeMillis();
        if (now - lastMenuSerialAttemptMs < 1000) return; // throttle
        lastMenuSerialAttemptMs = now;

        setMenuSerialStatus("Serial: reconnecting…");
        setMenuResetEnabled(false);

        SwingUtilities.invokeLater(() -> {
            robustCloseMenuSerial();
            new javax.swing.Timer(200, e -> {
                openMenuSerial();
                setMenuResetEnabled(true);
                ((javax.swing.Timer)e.getSource()).stop();
            }).start();
        });
    }

    private void robustCloseMenuSerial() {
        try {
            if (menuPort == null) return;
            try { menuPort.removeDataListener(); } catch (Throwable ignore) {}
            try { menuPort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0); } catch (Throwable ignore) {}
            // quick drain
            byte[] buf = new byte[512];
            long end = System.currentTimeMillis() + 150;
            while (System.currentTimeMillis() < end) {
                int avail = menuPort.bytesAvailable();
                if (avail <= 0) { Thread.sleep(10); continue; }
                int toRead = Math.min(avail, buf.length);
                menuPort.readBytes(buf, toRead);
            }
            try { menuPort.clearDTR(); } catch (Throwable ignore) {}
            try { menuPort.clearRTS(); } catch (Throwable ignore) {}
            try { Thread.sleep(120); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (menuPort.isOpen()) menuPort.closePort();
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            menuPort = null;
            setMenuSerialStatus("Serial: closed");
            setMenuResetEnabled(true);
        }
    }





        // =========================
    // UI helpers
    // =========================

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

    // =========================
    // Click handling: launch quiz safely (async)
    // =========================
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
        final String portNameFinal = (menuPort != null && menuPort.isOpen())
                ? menuPort.getSystemPortName()
                : "none";

        // Release the menu port so the quiz can own it
        if (menuWatchdog != null) menuWatchdog.stop();
        robustCloseMenuSerial();

        // Launch the quiz
        launchQuizAsync(tableFinal, portNameFinal);
    }

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
                    // Re-open menu serial so the menu remains usable
                    openMenuSerial();
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

    // ---- Toast helper (rounded chip, auto-fades) ----
    private void showTemporaryMessage(String message) {
        final JWindow popup = new JWindow();
        popup.setBackground(new Color(0, 0, 0, 0)); // transparent window

        final JPanel toast = new JPanel() {
            private final int arc = 18;
            private final int shadow = 14;
            private final int yOffset = 3;
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
