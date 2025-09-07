package birdquiz;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import com.fazecast.jSerialComm.SerialPort;

public class MainMenu extends JFrame implements ActionListener {

    private JButton songbirdsBtn; // blue
    private JButton ducksBtn;     // green
    private JButton raptorsBtn;   // yellow
    private JButton shorebirdsBtn; // white

    private SerialPort serialPort;
    private final StringBuilder serialBuf = new StringBuilder();
    private String connectedPortName = "none";

    private JLabel statusLabel;

    public MainMenu() {
        setTitle("Bird Quiz - Main Menu");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLayout(new BorderLayout());

        JPanel center = new JPanel(new GridBagLayout());
        center.setBorder(new EmptyBorder(30, 30, 10, 30));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(20, 20, 20, 20);

        songbirdsBtn = makeCircleButton("Songbirds", new Color(35,116,232)); // blue
        ducksBtn     = makeCircleButton("Ducks",     new Color(35,166,92));  // green
        raptorsBtn   = makeCircleButton("Raptors",   new Color(236,202,49)); // yellow

        gbc.gridx = 0; gbc.gridy = 0; center.add(songbirdsBtn, gbc);
        gbc.gridx = 1; gbc.gridy = 0; center.add(ducksBtn, gbc);
        gbc.gridx = 2; gbc.gridy = 0; center.add(raptorsBtn, gbc);

        shorebirdsBtn = new JButton("Shorebirds");
        shorebirdsBtn.setFont(new Font("Arial", Font.BOLD, 28));
        shorebirdsBtn.setPreferredSize(new Dimension(320, 90));
        shorebirdsBtn.setFocusPainted(false);
        shorebirdsBtn.setBackground(Color.WHITE);
        shorebirdsBtn.setOpaque(true);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3;
        center.add(shorebirdsBtn, gbc);

        add(center, BorderLayout.CENTER);

        statusLabel = new JLabel("Serial: none", SwingConstants.RIGHT);
        statusLabel.setBorder(new EmptyBorder(0, 12, 12, 12));
        add(statusLabel, BorderLayout.SOUTH);

        songbirdsBtn.addActionListener(this);
        ducksBtn.addActionListener(this);
        raptorsBtn.addActionListener(this);
        shorebirdsBtn.addActionListener(this);

        autoConnectSerial();

        // Ensure serial closes if window is closed directly
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                closeSerial();
            }
            @Override public void windowClosing(WindowEvent e) {
                closeSerial();
            }
        });

        setVisible(true);
    }

    // --- circle button look ---
    private JButton makeCircleButton(String text, Color fill) {
        return new CircleButton(text, fill);
    }

    // --- open first available serial @115200 and listen for tokens ---
    private void autoConnectSerial() {
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length == 0) {
            System.out.println("[MENU] No serial ports found.");
            statusLabel.setText("Serial: none (no ports)");
            return;
        }

        for (SerialPort p : ports) {
            try {
                System.out.println("[MENU] Trying " + p.getSystemPortName());
                p.setBaudRate(115200);
                p.setNumDataBits(8);
                p.setNumStopBits(1);
                p.setParity(SerialPort.NO_PARITY);
                p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 50, 0);

                if (!p.openPort()) {
                    System.out.println("[MENU] Failed to open " + p.getSystemPortName());
                    continue;
                }

                serialPort = p;
                connectedPortName = serialPort.getSystemPortName();
                statusLabel.setText("Serial: " + connectedPortName);
                System.out.println("[MENU] Opened " + connectedPortName);

                attachSerialListener(serialPort);
                return; // use first that opens
            } catch (Exception ex) {
                ex.printStackTrace();
                if (p.isOpen()) p.closePort();
            }
        }
        statusLabel.setText("Serial: none (open failed)");
    }

    private void attachSerialListener(SerialPort port) {
        port.addDataListener(new com.fazecast.jSerialComm.SerialPortDataListener() {
            @Override public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
            }
            @Override public void serialEvent(com.fazecast.jSerialComm.SerialPortEvent event) {
                if (event.getEventType() == SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
                    try {
                        byte[] buf = new byte[port.bytesAvailable()];
                        int n = port.readBytes(buf, buf.length);
                        if (n > 0) {
                            serialBuf.append(new String(buf, 0, n));
                            processSerialBuffer();
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
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
            handleEspCommand(line);
        }
    }

    private void handleEspCommand(String token) {
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
            case "submit":
                System.out.println("[MENU] ESP -> submit (Shorebirds)");
                SwingUtilities.invokeLater(() -> shorebirdsBtn.doClick());
                break;
            default:
                System.out.println("[MENU] ESP unknown token: " + token);
        }
    }

    private void closeSerial() {
        try {
            if (serialPort != null) {
                // remove listener to be safe
                serialPort.removeDataListener();
                if (serialPort.isOpen()) {
                    System.out.println("[MENU] Closing " + serialPort.getSystemPortName());
                    serialPort.closePort();
                }
            }
        } catch (Exception ignore) {}
    }

    // --- click handling: play category sound, close serial, launch quiz ---
    @Override
    public void actionPerformed(ActionEvent e) {
        Object src = e.getSource();
        String table = null;
        String sound = null;

        if (src == songbirdsBtn)      { table = "songbirds";  sound = "songbirds.wav"; }
        else if (src == ducksBtn)     { table = "ducks";      sound = "ducks.wav"; }
        else if (src == raptorsBtn)   { table = "raptors";    sound = "raptors.wav"; }
        else if (src == shorebirdsBtn){ table = "shore_birds"; sound = "shorebirds.wav"; } // add file if you have it

        if (table != null) {
            // play menu sound (non-blocking helper)
            try { if (sound != null) SoundUtil.playSound(sound); } catch (Exception ignore) {}

            // CRITICAL: free the port so the quiz can open it
            String portName = connectedPortName;
            closeSerial();

            try {
                new BirdQuizGUI(table, portName, "", "").setVisible(true);
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this, "Failed to open quiz: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                // If launching fails, try to re-open serial so menu still works
                autoConnectSerial();
                return;
            }
            dispose(); // close the menu
        }
    }

    // --- circular painted button class ---
    private static class CircleButton extends JButton {
        private final Color fill;
        CircleButton(String text, Color fill) {
            super(text);
            this.fill = fill;
            setPreferredSize(new Dimension(240, 240));
            setFont(new Font("Arial", Font.BOLD, 24));
            setForeground(Color.BLACK);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int d = Math.min(getWidth(), getHeight());
            int x = (getWidth() - d) / 2;
            int y = (getHeight() - d) / 2;
            g2.setColor(fill);
            g2.fillOval(x, y, d, d);
            g2.setColor(getForeground());
            FontMetrics fm = g2.getFontMetrics(getFont());
            String text = getText();
            int tw = fm.stringWidth(text);
            int th = fm.getAscent();
            g2.setFont(getFont());
            g2.drawString(text, getWidth() / 2 - tw / 2, getHeight() / 2 + th / 4);
            g2.dispose();
        }
        @Override
        public boolean contains(int x, int y) {
            int d = Math.min(getWidth(), getHeight());
            int cx = getWidth() / 2, cy = getHeight() / 2;
            int rx = x - cx, ry = y - cy;
            return (rx * rx + ry * ry) <= (d / 2) * (d / 2);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(MainMenu::new);
    }
}
