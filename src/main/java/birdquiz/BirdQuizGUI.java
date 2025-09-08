package birdquiz;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.List;

import javax.imageio.ImageIO;

import com.fazecast.jSerialComm.SerialPort;

public class BirdQuizGUI extends JFrame implements ActionListener {

    // ===== DB & quiz config =====
    private static final String DB_URL = "jdbc:mysql://localhost:3306/birds_db";
    private static final String DB_USERNAME = "root";
    private static final String DB_PASSWORD = "Neskowin71";
    private static final int MAX_QUESTIONS = 5;
    private IdleSlideshowOverlay slideshow;

    private static final Font RADIO_BUTTON_FONT = new Font("Arial", Font.PLAIN, 16);
    private static final Font BOTTOM_BUTTON_FONT = new Font("Arial", Font.BOLD, 30);
    private static final double OPTIONS_PANEL_PERCENT = 70.0;
    private static final long SUBMIT_COOLDOWN = 1000;

    // ===== DB objects =====
    private Connection connection;
    private java.util.List<Question> questions = new ArrayList<>();
    private int currentQuestionIndex = 0;
    private int score = 0;
    private int totalQuestionsAnswered = 0;

    // ===== Serial (quiz-owned) =====
    private SerialPort quizPort;
    private final String quizPortName;
    private final StringBuilder quizSerialBuf = new StringBuilder(512);
    private final Map<String, Long> quizDebounceMap = new HashMap<>();
    private static final long QUIZ_DEBOUNCE_MS = 200;

    // ===== Serial UI & watchdog =====
    private JLabel serialStatusLabel;
    private JButton resetSerialButton;
    private javax.swing.Timer quizSerialWatchdog;
    private long lastSerialAttemptMs = 0;

    // ===== UI =====
    private JLabel imageLabel, feedbackLabel, scoreLabel, finalMessageLabel;
    private JRadioButton[] options;
    private JButton submitButton, exitButton, newQuizButton, menuButton;
    private ButtonGroup group;
    private JPanel optionsPanel;

    // Option icons
    private final String[] imageNames = {"blue.png", "green.png", "yellow.png"};
    private final String[] selectedImageNames = {"blue_selected.png", "green_selected.png", "yellow_selected.png"};
    private final ImageIcon[] resizedImages = new ImageIcon[imageNames.length];
    private final ImageIcon[] selectedResizedImages = new ImageIcon[imageNames.length];
    private ImageIcon submitIcon, whiteIcon;

    // ===== misc =====
    private final Map<String, ImageIcon> imageCache = new HashMap<>();
    private final String tableName;
    private final QuizScoreProcessor quizScoreProcessor;
    private final String firstName;
    private final String email;
    private long lastSubmitTime = 0;

    // ===== Constructor =====
    public BirdQuizGUI(String tableName, String serialPortName, String firstName, String email) {
        super("Bird Quiz - " + tableName);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        this.tableName = tableName;
        this.quizPortName = serialPortName;
        this.firstName = firstName;
        this.email = email;
        this.quizScoreProcessor = new QuizScoreProcessor();


          // attach slideshow: idle 30s, slide every 6s
         slideshow = IdleSlideshowOverlay.attachTo(
         this,
         IdleSlideshowOverlay.resources("/images/slideshow",
                "splash1.jpg", "splash2.jpg", "splash3.jpg"),
         10_000L,
         6_000L
         );



        // Serial first (non-blocking)
        openQuizSerial();

        // Always release on close
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (quizSerialWatchdog != null) quizSerialWatchdog.stop();
                robustCloseQuizSerial();
            }
           @Override public void windowClosed(WindowEvent e) {
    if (quizSerialWatchdog != null) quizSerialWatchdog.stop();
    robustCloseQuizSerial();
    if (slideshow != null) slideshow.shutdown();
}
        });

        // DB + UI
        initializeDatabaseConnection();
        initializeQuestionsAndImages();
        setupUI();

        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setUndecorated(true);
        setVisible(true);

        nextQuestion();
        addComponentListenerToOptionsPanel();
    }

    // Convenience ctor
    public BirdQuizGUI(String tableName, String serialPortName) {
        this(tableName, serialPortName, "", "");
    }

    // ====================== SERIAL INTEGRATION ======================

    private void setSerialStatus(String text) {
        if (serialStatusLabel != null) serialStatusLabel.setText(text);
    }

    private void setResetEnabled(boolean enabled) {
        if (resetSerialButton != null) resetSerialButton.setEnabled(enabled);
    }

    private void openQuizSerial() {
        if (quizPortName == null || quizPortName.isEmpty() || "none".equalsIgnoreCase(quizPortName)) {
            setSerialStatus("Serial: none (no port)");
            setResetEnabled(false);
            return;
        }
        try {
            quizPort = SerialPort.getCommPort(quizPortName);
            quizPort.setBaudRate(115200);
            quizPort.setNumDataBits(8);
            quizPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
            quizPort.setParity(SerialPort.NO_PARITY);
            quizPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);
            try { quizPort.setDTR(); } catch (Throwable ignore) {}
            try { quizPort.setRTS(); } catch (Throwable ignore) {}

            if (!quizPort.openPort()) {
                setSerialStatus("Serial: open failed (" + quizPortName + ")");
                setResetEnabled(true);
                System.out.println("[QUIZ] Failed to open " + quizPortName);
                return;
            }

            System.out.println("[QUIZ] Opened " + quizPortName);
            setSerialStatus("Serial: " + quizPort.getSystemPortName());
            setResetEnabled(true);

            attachQuizSerialListener(quizPort);

        } catch (Exception ex) {
            ex.printStackTrace();
            setSerialStatus("Serial: error (" + ex.getMessage() + ")");
            setResetEnabled(true);
            quizPort = null;
        }
    }

    private void attachQuizSerialListener(SerialPort port) {
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
                            quizSerialBuf.append(c);
                        }
                        processQuizSerialBuffer();
                        if (quizSerialBuf.length() > 4096) {
                            quizSerialBuf.delete(0, quizSerialBuf.length() - 1024);
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    setSerialStatus("Serial: error (" + ex.getMessage() + ")");
                }
            }
        });
    }

    private void processQuizSerialBuffer() {
        int nl;
        while ((nl = quizSerialBuf.indexOf("\n")) >= 0) {
            String line = quizSerialBuf.substring(0, nl).trim().toLowerCase();
            quizSerialBuf.delete(0, nl + 1);
            if (line.isEmpty()) continue;

            if (line.startsWith("button pressed:") || line.startsWith("button released:")) continue;

            long now = System.currentTimeMillis();
            Long last = quizDebounceMap.get(line);
            if (last != null && (now - last) < QUIZ_DEBOUNCE_MS) continue;
            quizDebounceMap.put(line, now);

            handleQuizEspToken(line);
        }
    }

    private void handleQuizEspToken(String token) {
        if (slideshow != null) slideshow.poke();
        System.out.println("[QUIZ] ESP token: " + token);
        setSerialStatus("Serial: " + quizPortName + " (" + token + ")");

        switch (token) {
            case "blue":   selectOptionIndex(0); break;
            case "green":  selectOptionIndex(1); break;
            case "yellow": selectOptionIndex(2); break;
            case "white":  selectOptionIndex(3); break; // if you add a 4th option later
            case "submit":
            case "enter":
            case "ok":
                SwingUtilities.invokeLater(this::fireSubmitThrottled);
                break;
            case "next":
                SwingUtilities.invokeLater(this::nextQuestion);
                break;
            default:
                System.out.println("[QUIZ] Unknown token: " + token);
        }
    }

    private void selectOptionIndex(int idx) {
        try {
            if (options != null && idx >= 0 && idx < options.length && options[idx] != null) {
                final int i = idx;
                SwingUtilities.invokeLater(() -> options[i].setSelected(true));
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void reconnectSerial() {
        long now = System.currentTimeMillis();
        if (now - lastSerialAttemptMs < 1000) return; // throttle
        lastSerialAttemptMs = now;

        setSerialStatus("Serial: reconnecting…");
        setResetEnabled(false);

        SwingUtilities.invokeLater(() -> {
            robustCloseQuizSerial();
            new javax.swing.Timer(150, ae -> {
                openQuizSerial();
                setResetEnabled(true);
                ((javax.swing.Timer) ae.getSource()).stop();
            }).start();
        });
    }

    private void robustCloseQuizSerial() {
        try {
            if (quizPort == null) return;

            try { quizPort.removeDataListener(); } catch (Throwable ignore) {}
            try { quizPort.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0); } catch (Throwable ignore) {}

            drainPort(quizPort);

            try { quizPort.clearDTR(); } catch (Throwable ignore) {}
            try { quizPort.clearRTS(); } catch (Throwable ignore) {}

            try { Thread.sleep(150); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            if (quizPort.isOpen()) {
                System.out.println("[QUIZ] Closing " + quizPort.getSystemPortName());
                quizPort.closePort();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            quizPort = null;
            setSerialStatus("Serial: closed");
            setResetEnabled(true);
            try { Thread.sleep(120); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

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

    // ====================== DB & UI ======================

    private void initializeDatabaseConnection() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            System.out.println("Database driver loaded");
            connection = DriverManager.getConnection(DB_URL, DB_USERNAME, DB_PASSWORD);
            System.out.println("Database connection established");
        } catch (ClassNotFoundException | SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Database error: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void initializeQuestionsAndImages() {
        try {
            questions = fetchBirdQuestions();
            if (questions.size() < MAX_QUESTIONS) {
                throw new SQLException("Not enough questions available.");
            }
            loadAndResizeImages();
        } catch (SQLException | IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "Error initializing questions or images: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static InputStream resourceStreamOrThrow(String absolutePath) throws IOException {
        InputStream in = BirdQuizGUI.class.getResourceAsStream(absolutePath);
        if (in == null) {
            throw new FileNotFoundException("Resource not found on classpath: " + absolutePath);
        }
        return in;
    }

    private static ImageIcon scaleIcon(BufferedImage src, int w, int h) {
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(src, 0, 0, w, h, null);
        g2.dispose();
        return new ImageIcon(scaled);
    }

    private void loadAndResizeImages() throws IOException {
        final String base = "/buttons/";
        for (int i = 0; i < imageNames.length; i++) {
            String normalPath = base + imageNames[i];
            String selectedPath = base + selectedImageNames[i];
            try (InputStream is = resourceStreamOrThrow(normalPath);
                 InputStream sis = resourceStreamOrThrow(selectedPath)) {
                BufferedImage normal = ImageIO.read(is);
                BufferedImage selected = ImageIO.read(sis);
                resizedImages[i] = scaleIcon(normal, 50, 50);
                selectedResizedImages[i] = scaleIcon(selected, 50, 50);
            }
        }

        String submitPath = base + "white.png";
        String submitSelectedPath = base + "white_selected.png";
        try (InputStream is = resourceStreamOrThrow(submitPath);
             InputStream sis = resourceStreamOrThrow(submitSelectedPath)) {
            BufferedImage normal = ImageIO.read(is);
            BufferedImage selected = ImageIO.read(sis);
            submitIcon = scaleIcon(normal, 50, 50);
            whiteIcon  = scaleIcon(selected, 50, 50);
        }
    }

    private void setupUI() {
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        setupImageLabel(gbc);
        setupOptionsPanel(gbc);
        setupSubmitPanel(gbc);
        setupFeedbackLabel(gbc);
        setupFinalMessageLabel(gbc);
        setupScoreLabel(gbc);
        setupBottomPanel(gbc);

        // Auto-reconnect watchdog every 3s if we lose the port
        quizSerialWatchdog = new javax.swing.Timer(3000, e -> {
            boolean havePortName = quizPortName != null && !quizPortName.isEmpty()
                    && !"none".equalsIgnoreCase(quizPortName);
            boolean open = (quizPort != null && quizPort.isOpen());
            if (havePortName && !open) {
                setSerialStatus("Serial: reconnecting…");
                reconnectSerial();
            }
        });
        quizSerialWatchdog.start();
    }

    private void setupImageLabel(GridBagConstraints gbc) {
        imageLabel = new JLabel("", SwingConstants.CENTER);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.weighty = 0.4;
        gbc.fill = GridBagConstraints.BOTH;
        add(imageLabel, gbc);
        gbc.gridwidth = 1;
    }

    private void setupOptionsPanel(GridBagConstraints gbc) {
        optionsPanel = new JPanel(new GridBagLayout());
        optionsPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        options = new JRadioButton[3];
        group = new ButtonGroup();

        for (int i = 0; i < options.length; i++) {
            options[i] = createRadioButtonWithIcons(i);
            options[i].setFont(new Font("Arial", Font.PLAIN, 25));
            group.add(options[i]);

            GridBagConstraints optGbc = new GridBagConstraints();
            optGbc.gridx = i;
            optGbc.gridy = 0;
            optGbc.insets = new Insets(10, 20, 10, 20);
            optGbc.anchor = GridBagConstraints.CENTER;
            optGbc.fill = GridBagConstraints.HORIZONTAL;
            optionsPanel.add(options[i], optGbc);
        }

        gbc.gridy = 1;
        gbc.weighty = 0.2;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.gridwidth = 3;
        add(optionsPanel, gbc);
    }

    private void setupSubmitPanel(GridBagConstraints gbc) {
        submitButton = new JButton("Submit", submitIcon);
        submitButton.setPreferredSize(new Dimension(200, 60));
        submitButton.setHorizontalTextPosition(SwingConstants.RIGHT);
        submitButton.setVerticalTextPosition(SwingConstants.CENTER);
        submitButton.setFont(new Font("Arial", Font.BOLD, 15));
        submitButton.setContentAreaFilled(false);
        submitButton.setBorderPainted(false);
        submitButton.addActionListener(this);

        JPanel submitPanel = new JPanel();
        submitPanel.add(submitButton);

        gbc.gridy = 2;
        gbc.weightx = 1.0;
        gbc.gridwidth = 3;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.NONE;
        add(submitPanel, gbc);
    }

    private void setupFeedbackLabel(GridBagConstraints gbc) {
        feedbackLabel = new JLabel("", SwingConstants.CENTER);
        feedbackLabel.setFont(new Font("Arial", Font.BOLD, 20));
        gbc.gridy = 3;
        gbc.weighty = 0.1;
        gbc.insets = new Insets(0, 0, 10, 0);
        add(feedbackLabel, gbc);
    }

    private void setupFinalMessageLabel(GridBagConstraints gbc) {
        finalMessageLabel = new JLabel("", SwingConstants.CENTER);
        finalMessageLabel.setFont(new Font("Arial", Font.BOLD, 25));
        gbc.gridy = 5;
        gbc.weighty = 0.1;
        add(finalMessageLabel, gbc);
    }

    private void setupScoreLabel(GridBagConstraints gbc) {
        scoreLabel = new JLabel("Score: 0/0", SwingConstants.CENTER);
        scoreLabel.setFont(new Font("Arial", Font.BOLD, 25));
        gbc.gridy = 4;
        gbc.weighty = 0.1;
        gbc.anchor = GridBagConstraints.CENTER;
        add(scoreLabel, gbc);
    }

    private void setupBottomPanel(GridBagConstraints gbc) {
        JPanel bottomPanel = createBottomPanel();
        gbc.gridy = 6;
        gbc.weighty = 0.2;
        gbc.gridwidth = 3;
        add(bottomPanel, gbc);
    }

    private JRadioButton createRadioButtonWithIcons(int index) {
        JRadioButton radioButton = new JRadioButton();
        radioButton.setIcon(resizedImages[index]);
        radioButton.setSelectedIcon(selectedResizedImages[index]);
        radioButton.putClientProperty("index", index);

        radioButton.setFont(new Font("Arial", Font.PLAIN, 25));
        radioButton.setHorizontalTextPosition(SwingConstants.RIGHT);
        radioButton.setVerticalTextPosition(SwingConstants.CENTER);
        radioButton.setIconTextGap(10);
        radioButton.setPreferredSize(new Dimension(300, 50));

        radioButton.addActionListener(this);
        radioButton.addActionListener(e -> {
            System.out.println("Radio button " + radioButton.getClientProperty("index") + " selected.");
            try { SoundUtil.playSound("radio_button_selected.wav"); } catch (Exception ignored) {}
        });
        return radioButton;
    }

    private JPanel createBottomPanel() {
        JPanel bottomPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbcBottom = new GridBagConstraints();
        Dimension buttonSize = new Dimension(120, 50);

        gbcBottom.insets = new Insets(10, 12, 10, 12);
        gbcBottom.gridy = 0;

        // Serial status (left)
        serialStatusLabel = new JLabel("Serial: initializing...");
        serialStatusLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        gbcBottom.gridx = 0;
        gbcBottom.anchor = GridBagConstraints.WEST;
        bottomPanel.add(serialStatusLabel, gbcBottom);

        // Filler
        gbcBottom.gridx = 1;
        gbcBottom.weightx = 1.0;
        bottomPanel.add(Box.createHorizontalGlue(), gbcBottom);

        // Reset Serial (right)
        resetSerialButton = new JButton("Reset Serial");
        resetSerialButton.setFont(new Font("Arial", Font.PLAIN, 12));
        resetSerialButton.setPreferredSize(buttonSize);
        resetSerialButton.addActionListener(e -> reconnectSerial());
        gbcBottom.gridx = 2;
        gbcBottom.weightx = 0.0;
        gbcBottom.anchor = GridBagConstraints.EAST;
        bottomPanel.add(resetSerialButton, gbcBottom);

        // Row 1: control buttons
        gbcBottom.gridy = 1;

        newQuizButton = new JButton("New Quiz");
        newQuizButton.setPreferredSize(buttonSize);
        newQuizButton.setFont(new Font("Arial", Font.PLAIN, 12));
        newQuizButton.addActionListener(e -> restartQuiz());
        gbcBottom.gridx = 0;
        gbcBottom.anchor = GridBagConstraints.CENTER;
        bottomPanel.add(newQuizButton, gbcBottom);

        exitButton = new JButton("Exit Quiz");
        exitButton.setPreferredSize(buttonSize);
        exitButton.setFont(new Font("Arial", Font.PLAIN, 12));
        exitButton.addActionListener(e -> {
            robustCloseQuizSerial();
            System.exit(0);
        });
        gbcBottom.gridx = 1;
        bottomPanel.add(exitButton, gbcBottom);

        menuButton = new JButton("Menu");
        menuButton.setPreferredSize(buttonSize);
        menuButton.setFont(new Font("Arial", Font.PLAIN, 12));
        menuButton.addActionListener(e -> returnToMainMenu());
        gbcBottom.gridx = 2;
        bottomPanel.add(menuButton, gbcBottom);

        return bottomPanel;
    }

    private void addComponentListenerToOptionsPanel() {
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (optionsPanel != null) {
                    optionsPanel.setPreferredSize(
                        new Dimension((int) (getWidth() * OPTIONS_PANEL_PERCENT / 100.0),
                                      optionsPanel.getPreferredSize().height));
                    optionsPanel.revalidate();
                    optionsPanel.repaint();
                }
            }
        });
    }

    // ====================== Quiz flow ======================

    @Override
    public void actionPerformed(ActionEvent e) {
        Object src = e.getSource();
        if (src == submitButton) {
            fireSubmitThrottled();
        } else if (src instanceof JRadioButton) {
            JRadioButton rb = (JRadioButton) src;
            System.out.println("Radio button " + rb.getClientProperty("index") + " selected via Action Event");
        }
    }


private void restartQuiz() {
    currentQuestionIndex = 0;
    score = 0;
    totalQuestionsAnswered = 0;
    try {
        questions = fetchBirdQuestions();
        if (questions == null || questions.size() < MAX_QUESTIONS) {
            throw new SQLException("Not enough questions available.");
        }
        Collections.shuffle(questions);
    } catch (SQLException e) {
        e.printStackTrace();
        JOptionPane.showMessageDialog(this, "Database error: " + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        return;
    }

    feedbackLabel.setText("");
    scoreLabel.setText("Score: 0/0");
    finalMessageLabel.setText("");
    submitButton.setEnabled(true);
    newQuizButton.setEnabled(false);

    nextQuestion();
}





    private void fireSubmitThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastSubmitTime < SUBMIT_COOLDOWN) return;
        lastSubmitTime = now;
        handleSubmission();
        try { SoundUtil.playSound("submit_button_pressed.wav"); } catch (Exception ignored) {}
    }

    private void handleSubmission() {
        ButtonModel selectedModel = group.getSelection();
        if (selectedModel == null) {
            showTemporaryMessage("⚠ Please select an answer before submitting.");
            return;
        }

        String selectedOption = null;
        for (JRadioButton option : options) {
            if (option.isSelected()) {
                selectedOption = option.getText();
                break;
            }
        }

        totalQuestionsAnswered++;

        if (selectedOption != null &&
            selectedOption.equals(questions.get(currentQuestionIndex).getCorrectName())) {
            score++;
            feedbackLabel.setText("Your answer was correct!");
            try { SoundUtil.playSound("right_answer.wav"); } catch (Exception ignored) {}
            sendSerialMessage("correct");
        } else {
            feedbackLabel.setText("Incorrect. The correct answer was: " +
                    questions.get(currentQuestionIndex).getCorrectName());
            try { SoundUtil.playSound("wrong_answer.wav"); } catch (Exception ignored) {}
            sendSerialMessage("wrong");
        }

        scoreLabel.setText("Score: " + score + "/" + totalQuestionsAnswered);
        group.clearSelection();

        if (totalQuestionsAnswered < MAX_QUESTIONS) {
            currentQuestionIndex++;
            nextQuestion();
        } else {
            if (score == MAX_QUESTIONS) {
                try { SoundUtil.playSound("bird_nerd.wav"); } catch (Exception ignored) {}
                finalMessageLabel.setText("You are a Bird Nerd!! Congrats on a perfect score!");
                sendSerialMessage("ledSequence");
            } else {
                finalMessageLabel.setText("Quiz completed! Your final score is " +
                        score + "/" + totalQuestionsAnswered);
            }

            quizScoreProcessor.saveQuizResult(firstName, email, tableName, score, totalQuestionsAnswered);
            saveQuizResultToDatabase();

            submitButton.setEnabled(false);
            newQuizButton.setEnabled(true);
        }
    }

    private void sendSerialMessage(String message) {
        SerialPort p = quizPort;
        if (p != null && p.isOpen()) {
            try {
                String formatted = message + "\n";
                p.writeBytes(formatted.getBytes(), formatted.length());
                System.out.println("Sent to device: " + message);
            } catch (Exception ex) {
                ex.printStackTrace();
                setSerialStatus("Serial: write error");
            }
        }
    }

    private void saveQuizResultToDatabase() {
        String sql = "INSERT INTO quiz_results (first_name, email, quiz_table, score, total_questions) " +
                     "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, firstName);
            pstmt.setString(2, email);
            pstmt.setString(3, tableName);
            pstmt.setInt(4, score);
            pstmt.setInt(5, totalQuestionsAnswered);
            pstmt.executeUpdate();
            System.out.println("Quiz result successfully stored in the database");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void nextQuestion() {
        if (currentQuestionIndex < questions.size()) {
            loadImageAndOptions(questions.get(currentQuestionIndex));
        }
    }

    private void loadImageAndOptions(Question question) {
        String imgLocation = question.getImageLocation();
        System.out.println("Image filename from DB: " + imgLocation);

        ImageIcon imageIcon = imageCache.get(imgLocation);
        if (imageIcon == null) {
            String[] basePaths = {
                "/images/",
                "/images/songbirds/",
                "/images/ducks/",
                "/images/raptors/",
                "/images/shorebirds/"
            };
            for (String base : basePaths) {
                String fullPath = base + imgLocation;
                java.net.URL imageUrl = getClass().getResource(fullPath);
                if (imageUrl != null) {
                    imageIcon = new ImageIcon(imageUrl);
                    imageCache.put(imgLocation, imageIcon);
                    System.out.println("Image found at: " + fullPath);
                    break;
                }
            }
        }

        if (imageIcon == null) {
            imageLabel.setText("Failed to load image: " + imgLocation);
            imageLabel.setIcon(null);
            return;
        }

        int imageWidth = imageIcon.getIconWidth();
        int imageHeight = imageIcon.getIconHeight();
        double aspectRatio = (double) imageWidth / imageHeight;

        int width = getWidth();
        int height = (int) (width / aspectRatio);
        if (height > getHeight() * 0.7) {
            height = (int) (getHeight() * 0.7);
            width = (int) (height * aspectRatio);
        }

        Image scaledImage = imageIcon.getImage().getScaledInstance(width, height, Image.SCALE_SMOOTH);
        imageLabel.setIcon(new ImageIcon(scaledImage));

        java.util.List<String> optionsList = new ArrayList<>(question.getOptions());
        Collections.shuffle(optionsList);
        for (int i = 0; i < options.length; i++) {
            options[i].setText(optionsList.get(i));
            options[i].setSelected(false);
            options[i].setPreferredSize(new Dimension(400, 50));
        }

        if (optionsPanel != null) {
            optionsPanel.setPreferredSize(new Dimension(
                (int) (getWidth() * OPTIONS_PANEL_PERCENT / 100.0),
                optionsPanel.getPreferredSize().height));
            optionsPanel.revalidate();
            optionsPanel.repaint();
        }
    }

    // ====================== Toast ======================

    private void showTemporaryMessage(String message) {
        final JWindow popup = new JWindow();
        popup.setBackground(new Color(0, 0, 0, 0));

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

                for (int i = shadow; i > 0; i--) {
                    float ringAlpha = 0.20f * (i / (float) shadow);
                    g2.setColor(new Color(0f, 0f, 0f, ringAlpha));
                    g2.fillRoundRect(i, i + yOffset, w - 2 * i, h - 2 * i, arc + i, arc + i);
                }

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

        try {
            Point parent = this.getLocationOnScreen();
            int x = parent.x + (this.getWidth() - popup.getWidth()) / 2;
            int y = parent.y + this.getHeight() - popup.getHeight() - 60;
            popup.setLocation(x, y);
        } catch (IllegalComponentStateException ex) {
            Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
            int x = (scr.width - popup.getWidth()) / 2;
            int y = (int) (scr.height * 0.85) - popup.getHeight() / 2;
            popup.setLocation(x, y);
        }

        popup.setAlwaysOnTop(true);
        popup.setVisible(true);

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

    // ====================== Navigation ======================

    private void returnToMainMenu() {
        robustCloseQuizSerial();
        SwingUtilities.invokeLater(() -> new MainMenu().setVisible(true));
        dispose();
    }

    // ====================== Data models ======================

    public static final class Question {
        private final String correctName;
        private final String imageLocation;
        private final java.util.List<String> options;

        public Question(String correctName, String imageLocation, java.util.List<String> options) {
            this.correctName   = java.util.Objects.requireNonNull(correctName, "correctName");
            this.imageLocation = java.util.Objects.requireNonNull(imageLocation, "imageLocation");
            java.util.Objects.requireNonNull(options, "options");
            this.options = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(options));
        }
        public String getCorrectName()   { return correctName; }
        public String getImageLocation() { return imageLocation; }
        public java.util.List<String> getOptions() { return options; }
        @Override public String toString() { return "Question{" + correctName + " | " + imageLocation + " | " + options + "}"; }
    }

    public static final class Bird {
        private final String name;
        private final String imageLocation;
        private final String category;

        public Bird(String name, String imageLocation, String category) {
            this.name          = java.util.Objects.requireNonNull(name, "name");
            this.imageLocation = java.util.Objects.requireNonNull(imageLocation, "imageLocation");
            this.category      = java.util.Objects.requireNonNull(category, "category");
        }
        public String getName()          { return name; }
        public String getImageLocation() { return imageLocation; }
        public String getCategory()      { return category; }
        @Override public String toString() { return "Bird{" + name + " | " + category + " | " + imageLocation + "}"; }
    }

    // ====================== Queries ======================

    private java.util.List<Question> fetchBirdQuestions() {
        java.util.List<Question> questionsList = new ArrayList<>();
        java.util.List<Bird> birds = new ArrayList<>();
        String fetchAllSql = "SELECT name, male_image_location, category FROM " + tableName;

        try (PreparedStatement st = connection.prepareStatement(fetchAllSql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                birds.add(new Bird(
                        rs.getString("name"),
                        rs.getString("male_image_location"),
                        rs.getString("category")
                ));
            }
            Collections.shuffle(birds);

            for (Bird bird : birds) {
                if (questionsList.size() >= MAX_QUESTIONS) break;

                String correctName = bird.getName();
                String imageLocation = bird.getImageLocation();
                String category = bird.getCategory();

                Set<String> optionsSet = new HashSet<>();
                optionsSet.add(correctName);

               java.util.List<Bird> categoryBirds = new java.util.ArrayList<>();
                for (Bird b : birds) {
                    if (b.getCategory().equals(category) && !b.getName().equals(correctName)) {
                        categoryBirds.add(b);
                    }
                }
                Collections.shuffle(categoryBirds);
                for (Bird b : categoryBirds) {
                    if (optionsSet.size() >= 3) break;
                    optionsSet.add(b.getName());
                }

                for (Bird b : birds) {
                    if (optionsSet.size() >= 3) break;
                    if (!optionsSet.contains(b.getName()) && !b.getName().equals(correctName)) {
                        optionsSet.add(b.getName());
                    }
                }

                if (optionsSet.size() == 3) {
                    java.util.List<String> optionsList = new java.util.ArrayList<>(optionsSet);
                    Collections.shuffle(optionsList);
                    questionsList.add(new Question(correctName, imageLocation, optionsList));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return questionsList;
    }
}
