package birdquiz;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.sql.*;
import java.util.*;
import javax.imageio.ImageIO;
import com.fazecast.jSerialComm.SerialPort;

public class BirdQuizGUI extends JFrame implements ActionListener {

// =========================================================================
// Section 1: DATABASE CONFIGURATION & QUIZ PARAMETERS
// =========================================================================

private static final String DB_URL      = "jdbc:mysql://localhost:3306/birds_db";
private static final String DB_USERNAME = "root";
private static final String DB_PASSWORD = "Neskowin71";

private static final int MAX_QUESTIONS = 5;
private static final int QUIZ_TIME_LIMIT_SECONDS = 60;
private static final double OPTIONS_PANEL_PERCENT = 70.0;
private static final long QUIZ_DEBOUNCE_MS = 200;
private static final long SUBMIT_COOLDOWN_MS = 800;

// Fonts
private static final Font RADIO_BUTTON_FONT = new Font("Arial", Font.PLAIN, 25);
private static final Font SCORE_FONT        = new Font("Arial", Font.BOLD, 25);
private static final Font FEEDBACK_FONT     = new Font("Arial", Font.BOLD, 20);
private static final Font BUTTON_FONT       = new Font("Arial", Font.PLAIN, 12);

// Quiz runtime state
private Connection connection;
private java.util.List<Question> questions;
private int currentQuestionIndex = 0;
private int score = 0;
private int totalQuestionsAnswered = 0;
private boolean quizStarted = false;
private boolean quizFinished = false;
private long quizStartMs = 0L;
private int remainingSeconds = QUIZ_TIME_LIMIT_SECONDS;

// =========================================================================
// Section 2: UI COMPONENTS
// =========================================================================
private JLabel imageLabel, feedbackLabel, scoreLabel, finalMessageLabel;
private JLabel toBeatLabel, serialStatusLabel, countdownLabel;
private JRadioButton[] options;
private JButton submitButton, exitButton, newQuizButton, menuButton, resetSerialButton;
private ButtonGroup group;
private JPanel submitPanel;
private CountdownRing countdownRing;
private javax.swing.Timer countdownTimer;

// =========================================================================
// Section 3: IMAGES AND RESOURCES
// =========================================================================
private final String[] imageNames = { "blue.png", "green.png", "yellow.png" };
private final String[] selectedImageNames = { "blue_selected.png", "green_selected.png", "yellow_selected.png" };
private final ImageIcon[] resizedImages = new ImageIcon[imageNames.length];
private final ImageIcon[] selectedResizedImages = new ImageIcon[imageNames.length];
private ImageIcon submitIcon, whiteIcon;
private final Map<String, ImageIcon> imageCache = new HashMap<>();

// =========================================================================
// Section 4: SERIAL COMMUNICATION, QUIZ CONTROL, BEST ENTRY MODEL
// =========================================================================
private SerialPort quizPort;
private final String quizPortName;
private final StringBuilder quizSerialBuf = new StringBuilder(512);
private final Map<String, Long> quizDebounceMap = new HashMap<>();
private javax.swing.Timer quizSerialWatchdog;
private long lastSerialAttemptMs = 0L;
private long lastSubmitTime = 0L;

private final String tableName;
private QuizScoreProcessor quizScoreProcessor;
private final String firstName;
private final String email;

// High score container
private static class BestEntry {
    final int score, total, seconds;
    BestEntry(int score, int total, int seconds) {
        this.score = score;
        this.total = total;
        this.seconds = seconds;
    }
}

// =========================================================================
// Section 5: CONSTRUCTORS & CLEANUP
// =========================================================================
public BirdQuizGUI(String tableName, String serialPortName, String firstName, String email) {
    super("Bird Quiz - " + tableName);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    setUndecorated(true);
    setExtendedState(JFrame.MAXIMIZED_BOTH);

    this.tableName    = tableName;
    this.quizPortName = serialPortName;
    this.firstName    = firstName;
    this.email        = email;
    this.quizScoreProcessor = new QuizScoreProcessor();

    setupUI();
    setVisible(true);

    SwingUtilities.invokeLater(() -> {
        initializeDatabaseConnection();
        if (!initializeQuestionsAndImages()) {
            if (submitButton != null) submitButton.setEnabled(false);
            if (newQuizButton != null) newQuizButton.setEnabled(true);
          // images are loaded at this point -> apply them to UI
     SwingUtilities.invokeLater(() -> {
    applySubmitIcon();
    applyOptionIconsIfAvailable();
});


            return;
        }
        openQuizSerial();
        setupQuizSerialWatchdog();

        for (WindowListener wl : getWindowListeners()) removeWindowListener(wl);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { cleanupOnClose(); }
            @Override public void windowClosed(WindowEvent e)  { cleanupOnClose(); }
        });

        restartQuiz();
    });
}

public BirdQuizGUI(String tableName, String serialPortName) {
    this(tableName, serialPortName, "", "");
}

private void cleanupOnClose() {
    if (quizSerialWatchdog != null) quizSerialWatchdog.stop();
    robustCloseQuizSerial();
}

private void setupQuizSerialWatchdog() {
    if (quizSerialWatchdog != null)
        try { quizSerialWatchdog.stop(); } catch (Exception ignore) {}
    quizSerialWatchdog = new javax.swing.Timer(3000, e -> {
        boolean havePortName = quizPortName != null && !quizPortName.isEmpty()
                && !"none".equalsIgnoreCase(quizPortName);
        boolean open = (quizPort != null && quizPort.isOpen());
        if (havePortName && !open) {
            setSerialStatus("Serial: reconnecting…");
            openQuizSerial();
        }
    });
    quizSerialWatchdog.start();
}


// =============================================================================
// Section 6: UI SETUP AND COMPONENT CONSTRUCTION
// =============================================================================

/** Main UI layout using GridBag. */
private void setupUI() {
    setLayout(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints();

    setupTopArea(gbc);
    setupOptionsPanel(gbc);
    setupSubmitPanel(gbc);
    setupFeedbackLabel(gbc);
    setupScoreLabel(gbc);
    setupFinalMessageLabel(gbc);
    setupBottomPanel(gbc);
    addComponentListenerToOptionsPanel();
}

/** Top: header bar (timer, to-beat) and main image. */
private void setupTopArea(GridBagConstraints gbc) {
    JPanel top = new JPanel(new BorderLayout());
    top.setOpaque(false);

    top.add(buildHeaderBar(), BorderLayout.NORTH);
    imageLabel = new JLabel("", SwingConstants.CENTER);
    top.add(imageLabel, BorderLayout.CENTER);

    gbc.gridx = 0; gbc.gridy = 0;
    gbc.gridwidth = 3;
    gbc.weightx = 1.0; gbc.weighty = 0.4;
    gbc.fill = GridBagConstraints.BOTH;
    add(top, gbc);
    gbc.gridwidth = 1;
}

/** Big radio buttons row. */
private void setupOptionsPanel(GridBagConstraints gbc) {
    JPanel optionsPanel = new JPanel(new GridBagLayout());
    optionsPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

    if (options == null || options.length != 3) options = new JRadioButton[3];
    if (group == null) group = new ButtonGroup();

    for (int i = 0; i < options.length; i++) {
        if (options[i] == null) options[i] = new JRadioButton("Option " + (i+1));
        options[i].setFont(RADIO_BUTTON_FONT);
        options[i].putClientProperty("index", i);
        options[i].addActionListener(this);
        group.add(options[i]);

        GridBagConstraints opt = new GridBagConstraints();
        opt.gridx = i; opt.gridy = 0;
        opt.insets = new Insets(10, 20, 10, 20);
        opt.anchor = GridBagConstraints.CENTER;
        optionsPanel.add(options[i], opt);
    }

    gbc.gridx = 0; gbc.gridy = 1;
    gbc.gridwidth = 3;
    gbc.weightx = 1.0; gbc.weighty = 0.2;
    gbc.fill = GridBagConstraints.NONE;
    add(optionsPanel, gbc);
    gbc.gridwidth = 1;
}

/** Submit button row. */
private void setupSubmitPanel(GridBagConstraints gbcParent) {
    submitPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
    submitPanel.setOpaque(false);

    if (submitButton == null) submitButton = new JButton("Submit");
    for (ActionListener al : submitButton.getActionListeners())
        submitButton.removeActionListener(al);
    submitButton.addActionListener(e -> handleSubmission());
    submitButton.setPreferredSize(new Dimension(200, 60));
    submitButton.setFont(new Font("Arial", Font.BOLD, 15));
    submitButton.setContentAreaFilled(false);
    submitButton.setBorderPainted(false);

    submitPanel.add(submitButton);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = 0; gbc.gridy = 2;
    gbc.gridwidth = 3;
    gbc.weightx = 1.0; gbc.weighty = 0.0;
    gbc.anchor = GridBagConstraints.CENTER;
    gbc.fill = GridBagConstraints.NONE;
    add(submitPanel, gbc);

    SwingUtilities.invokeLater(() -> {
        JRootPane root = getRootPane();
        if (root != null) root.setDefaultButton(submitButton);
    });
    applySubmitIcon();
}

/** Feedback label. */
private void setupFeedbackLabel(GridBagConstraints gbc) {
    if (feedbackLabel == null) feedbackLabel = new JLabel("", SwingConstants.CENTER);
    feedbackLabel.setFont(FEEDBACK_FONT);
    gbc.gridx = 0; gbc.gridy = 3;
    gbc.gridwidth = 3;
    gbc.weightx = 1.0; gbc.weighty = 0.1;
    gbc.insets = new Insets(0, 0, 10, 0);
    gbc.fill = GridBagConstraints.NONE;
    add(feedbackLabel, gbc);
    gbc.gridwidth = 1; gbc.insets = new Insets(0,0,0,0);
}

/** Score display (below feedback). */
private void setupScoreLabel(GridBagConstraints gbc) {
    if (scoreLabel == null) scoreLabel = new JLabel("Score: 0/0", SwingConstants.CENTER);
    scoreLabel.setFont(SCORE_FONT);
    gbc.gridx = 0; gbc.gridy = 4;
    gbc.gridwidth = 3;
    gbc.weightx = 1.0; gbc.weighty = 0.1;
    gbc.anchor = GridBagConstraints.CENTER;
    add(scoreLabel, gbc);
    gbc.gridwidth = 1;
}

/** Final message label for end-of-quiz. */
private void setupFinalMessageLabel(GridBagConstraints gbc) {
    if (finalMessageLabel == null) finalMessageLabel = new JLabel("", SwingConstants.CENTER);
    finalMessageLabel.setFont(SCORE_FONT);
    gbc.gridx = 0; gbc.gridy = 5;
    gbc.gridwidth = 3;
    gbc.weightx = 1.0; gbc.weighty = 0.1;
    add(finalMessageLabel, gbc);
    gbc.gridwidth = 1;
}

/** Main bottom row (New Quiz, Exit, Menu). */
private void setupBottomPanel(GridBagConstraints gbc) {
    JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));
    if (newQuizButton == null) {
        newQuizButton = new JButton("New Quiz");
        newQuizButton.addActionListener(e -> restartQuiz());
    }
    if (exitButton == null) {
        exitButton = new JButton("Exit Quiz");
        exitButton.addActionListener(e -> System.exit(0));
    }
    if (menuButton == null) {
        menuButton = new JButton("Menu");
        menuButton.addActionListener(e -> {
            try { robustCloseQuizSerial(); } catch (Throwable ignore) {}
            new MainMenu().setVisible(true);
            dispose();
        });
    }
    bottom.add(newQuizButton);
    bottom.add(exitButton);
    bottom.add(menuButton);

    gbc.gridx = 0; gbc.gridy = 6;
    gbc.gridwidth = 3;
    gbc.weightx = 1.0; gbc.weighty = 0.2;
    gbc.anchor = GridBagConstraints.CENTER;
    gbc.fill   = GridBagConstraints.NONE;
    add(bottom, gbc);
    gbc.gridwidth = 1;
}

/** Resizes option panel on window resize. */
private void addComponentListenerToOptionsPanel() {
    addComponentListener(new ComponentAdapter() {
        @Override public void componentResized(ComponentEvent e) {
            JPanel optionsPanel = getOptionsPanel();
            if (optionsPanel != null) {
                optionsPanel.setPreferredSize(new Dimension(
                    (int) (getWidth() * OPTIONS_PANEL_PERCENT / 100.0),
                    optionsPanel.getPreferredSize().height
                ));
                optionsPanel.revalidate();
                optionsPanel.repaint();
            }
        }
    });
}

/** Finds the active options panel. */
private JPanel getOptionsPanel() {
    for (Component comp : getContentPane().getComponents()) {
        if (comp instanceof JPanel) {
            JPanel p = (JPanel) comp;
            for (Component inner : p.getComponents()) {
                if (inner instanceof JRadioButton)
                    return p;
            }
        }
    }
    return null;
}

/** Header bar with timer ring, To Beat, Time label, etc. */
private JPanel buildHeaderBar() {
    JPanel bar = new JPanel(new GridBagLayout());
    bar.setOpaque(false);
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(6, 10, 6, 10);
    c.gridy = 0;

    // Left: To Beat chip
    toBeatLabel = new JLabel("To Beat: —");
    toBeatLabel.setFont(new Font("Arial", Font.BOLD, 14));
    toBeatLabel.setOpaque(true);
    toBeatLabel.setBackground(new Color(240,240,240));
    toBeatLabel.setBorder(BorderFactory.createEmptyBorder(6,10,6,10));
    c.gridx = 0; c.weightx = 0; c.anchor = GridBagConstraints.WEST;
    bar.add(toBeatLabel, c);

    // Middle spacer
    c.gridx = 1; c.weightx = 1; c.anchor = GridBagConstraints.CENTER;
    bar.add(Box.createHorizontalGlue(), c);

    // Right: timer ring + label
    if (countdownRing == null) {
        countdownRing = new CountdownRing();
        countdownRing.setPreferredSize(new Dimension(56, 56));
        countdownRing.setTotalSeconds(QUIZ_TIME_LIMIT_SECONDS);
        countdownRing.setSecondsRemaining(QUIZ_TIME_LIMIT_SECONDS);
    }
    c.gridx = 2; c.weightx = 0; c.anchor = GridBagConstraints.EAST;
    bar.add(countdownRing, c);

    if (countdownLabel == null) {
        countdownLabel = new JLabel("Time: " + formatMMSS(QUIZ_TIME_LIMIT_SECONDS));
        countdownLabel.setFont(new Font("Arial", Font.BOLD, 14));
        countdownLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
    }
    c.gridx = 3; c.weightx = 0; c.anchor = GridBagConstraints.EAST;
    bar.add(countdownLabel, c);

    return bar;
}

private void applySubmitIcon() {
    if (submitButton == null) return;
    if (submitIcon != null) submitButton.setIcon(submitIcon);
    if (whiteIcon != null) {
        submitButton.setPressedIcon(whiteIcon);
        submitButton.setRolloverIcon(whiteIcon);
    }
    submitButton.revalidate();
    submitButton.repaint();
}


// =============================================================================
// Section 7: DATABASE, IMAGE/RESOURCE INITIALIZATION
// =============================================================================

private void initializeDatabaseConnection() {
    try {
        if (connection != null && !connection.isClosed()) return;
        Class.forName("com.mysql.cj.jdbc.Driver");
        connection = DriverManager.getConnection(DB_URL, DB_USERNAME, DB_PASSWORD);
        System.out.println("[DB] Connected: " + DB_URL);
    } catch (ClassNotFoundException | SQLException e) {
        e.printStackTrace();
        JOptionPane.showMessageDialog(
                this,
                "Database error: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
        connection = null;
    }
}

// Load questions + button images. Returns true on success.
private boolean initializeQuestionsAndImages() {
    try {
        questions = fetchBirdQuestions();
        if (questions == null || questions.size() < MAX_QUESTIONS) {
            JOptionPane.showMessageDialog(
                    this,
                    "Not enough questions available.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return false;
        }
        loadAndResizeImages();
        return true;
    } catch (Exception e) {
        e.printStackTrace();
        JOptionPane.showMessageDialog(
                this,
                "Error initializing questions or images: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE
        );
        return false;
    }
}






// =============================================================================
// Section 7.5 helpers
// ==


/** Tries several reasonable classpath paths (and last-resort file path) for an image. */
private ImageIcon findImageIconRobust(String rawPath) {
    if (rawPath == null || rawPath.isEmpty()) return null;

    // Normalize
    String p = rawPath.replace('\\', '/');
    while (p.startsWith("./")) p = p.substring(2);
    if (p.startsWith("/")) p = p.substring(1); // store without leading slash for joins

    // Try a bunch of likely classpath locations
    String[] tryPaths = new String[] {
        // if DB already includes subfolder (e.g. "songbirds/robin.jpg")
        "/images/" + p,
        // flat under /images (if DB only has "robin.jpg")
        "/images/" + lastSegment(p),
        // specific subfolders (back-compat)
        "/images/songbirds/" + lastSegment(p),
        "/images/ducks/"     + lastSegment(p),
        "/images/raptors/"   + lastSegment(p),
        "/images/shorebirds/"+ lastSegment(p),
        // if the DB path was already absolute-like
        "/" + p
    };

    for (String tp : tryPaths) {
        java.net.URL url = getClass().getResource(tp);
        if (url != null) {
            System.out.println("[QUIZ] loaded image: " + tp);
            return new ImageIcon(url);
        }
    }

    // Optional: last-resort file-system (dev mode only)
    try {
        java.io.File f = new java.io.File(rawPath);
        if (f.exists() && f.isFile()) {
            return new ImageIcon(rawPath);
        }
    } catch (Exception ignore) {}

    System.out.println("[QUIZ] image not found for: " + rawPath);
    return null;
}

private static String lastSegment(String path) {
    int i = path.lastIndexOf('/');
    return (i >= 0 ? path.substring(i + 1) : path);
}








/** Apply the colored option button icons if we have them. Safe to call repeatedly. */
private void applyOptionIconsIfAvailable() {
    if (options == null || options.length == 0) return;

    // We expect 3 colors (blue, green, yellow). If any is missing, bail gracefully.
    boolean haveAll =
        resizedImages != null && selectedResizedImages != null &&
        resizedImages.length >= 3 && selectedResizedImages.length >= 3 &&
        resizedImages[0] != null && resizedImages[1] != null && resizedImages[2] != null &&
        selectedResizedImages[0] != null && selectedResizedImages[1] != null && selectedResizedImages[2] != null;

    if (!haveAll) {
        System.out.println("[QUIZ] Option icons not available yet.");
        return;
    }

    for (int i = 0; i < Math.min(3, options.length); i++) {
        JRadioButton rb = options[i];
        if (rb == null) continue;
        rb.setIcon(resizedImages[i]);
        rb.setSelectedIcon(selectedResizedImages[i]);
        rb.setContentAreaFilled(false);
        rb.setFocusPainted(false);
        rb.setBorderPainted(false);
        rb.setHorizontalTextPosition(SwingConstants.RIGHT);
        rb.setIconTextGap(14);
        // keep text (bird names) on the right; if you want icon-only, use rb.setText("");
    }
    // force a small layout refresh
    JPanel panel = getOptionsPanel();
    if (panel != null) { panel.revalidate(); panel.repaint(); }
}










// --- High score / "To Beat" helpers ---

/** Reads the best (highest score, then fastest) attempt for this quiz/table. */
private BestEntry fetchBestFromDb() {
    if (connection == null || tableName == null || tableName.isEmpty()) return null;

    final String sql =
        "SELECT score, total_questions, duration_seconds " +
        "FROM quiz_attempts " +
        "WHERE quiz_name = ? AND duration_seconds IS NOT NULL " +
        "ORDER BY score DESC, duration_seconds ASC, finished_at ASC " +
        "LIMIT 1";

    try (PreparedStatement ps = connection.prepareStatement(sql)) {
        ps.setString(1, tableName);
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new BestEntry(
                    rs.getInt("score"),
                    rs.getInt("total_questions"),
                    rs.getInt("duration_seconds")
                );
            }
        }
    } catch (SQLException ex) {
        ex.printStackTrace();
    }
    return null;
}

/** True if this run beats the previous best (higher score, or same score but faster). */
private boolean isNewRecord(int myScore, int myTotal, int mySeconds, BestEntry prev) {
    if (prev == null) return true;
    if (myScore > prev.score) return true;
    if (myScore < prev.score) return false;
    return mySeconds < prev.seconds;
}

/** Updates the “To Beat” chip on the header bar. */
private void refreshToBeatLabel() {
    if (toBeatLabel == null) return;
    BestEntry b = fetchBestFromDb();
    if (b == null) {
        toBeatLabel.setText("To Beat: —");
    } else {
        int mm = b.seconds / 60, ss = b.seconds % 60;
        toBeatLabel.setText(String.format("To Beat: %d/%d — %02d:%02d", b.score, b.total, mm, ss));
    }
}

// --- Persistence helpers ---

/** Saves the overall quiz result into quiz_results. */
private void saveQuizResultToDatabase() {
    if (connection == null) return;
    final String sql =
        "INSERT INTO quiz_results (first_name, email, quiz_table, score, total_questions) " +
        "VALUES (?, ?, ?, ?, ?)";
    try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
        pstmt.setString(1, firstName);
        pstmt.setString(2, email);
        pstmt.setString(3, tableName);
        pstmt.setInt(4, score);
        pstmt.setInt(5, totalQuestionsAnswered);
        pstmt.executeUpdate();
        System.out.println("[QUIZ] quiz_results row inserted.");
    } catch (SQLException e) {
        e.printStackTrace();
    }
}

/** Saves this attempt into quiz_attempts (one row per run). */
private void saveAttemptToAttemptsTable(int durationSeconds) {
    if (connection == null) return;
    final String sql =
        "INSERT INTO quiz_attempts (first_name, email, quiz_name, score, total_questions, duration_seconds) " +
        "VALUES (?, ?, ?, ?, ?, ?)";
    try (PreparedStatement ps = connection.prepareStatement(sql)) {
        ps.setString(1, firstName);
        ps.setString(2, email);
        ps.setString(3, tableName);
        ps.setInt(4, score);
        ps.setInt(5, totalQuestionsAnswered);
        ps.setInt(6, durationSeconds);
        ps.executeUpdate();
        System.out.println("[QUIZ] quiz_attempts row inserted: " + tableName +
                " score=" + score + "/" + totalQuestionsAnswered +
                " duration=" + durationSeconds + "s");
    } catch (SQLException ex) {
        ex.printStackTrace();
    }
}




// Fetches a list of Questions for the quiz from the DB.
// Builds the randomized quiz questions from DB rows.
private java.util.List<Question> fetchBirdQuestions() {
    java.util.List<Question> questionsList = new java.util.ArrayList<>();
    java.util.List<Bird> birds = new java.util.ArrayList<>();

    if (connection == null) {
        System.err.println("[QUIZ] fetchBirdQuestions: connection is null");
        return questionsList;
    }
    if (tableName == null || tableName.trim().isEmpty()) {
        System.err.println("[QUIZ] fetchBirdQuestions: tableName is null/empty");
        return questionsList;
    }

    final String sql = "SELECT name, male_image_location, category FROM " + tableName;

    try (PreparedStatement ps = connection.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {

        while (rs.next()) {
            String name   = rs.getString("name");
            String img    = rs.getString("male_image_location");
            String cat    = rs.getString("category");
            birds.add(new Bird(name, img, cat));
        }
        System.out.println("[QUIZ] fetched birds: " + birds.size() + " from table " + tableName);

        java.util.Collections.shuffle(birds);

        for (Bird bird : birds) {
            if (questionsList.size() >= MAX_QUESTIONS) break;

            String correctName   = bird.getName();
            String imageLocation = bird.getImageLocation();
            String category      = bird.getCategory();

            // Build 3 unique options: 1 correct + 2 distractors
            java.util.Set<String> opts = new java.util.HashSet<>();
            opts.add(correctName);

            // Prefer same-category distractors
            java.util.List<Bird> sameCat = new java.util.ArrayList<>();
            for (Bird b : birds) {
                if (b.getCategory() != null
                        && b.getCategory().equals(category)
                        && !b.getName().equals(correctName)) {
                    sameCat.add(b);
                }
            }
            java.util.Collections.shuffle(sameCat);
            for (Bird b : sameCat) {
                if (opts.size() >= 3) break;
                opts.add(b.getName());
            }

            // Fill remaining from anywhere
            for (Bird b : birds) {
                if (opts.size() >= 3) break;
                if (!opts.contains(b.getName()) && !b.getName().equals(correctName)) {
                    opts.add(b.getName());
                }
            }

            if (opts.size() == 3) {
                java.util.List<String> optionList = new java.util.ArrayList<>(opts);
                java.util.Collections.shuffle(optionList);
                questionsList.add(new Question(correctName, imageLocation, optionList));
            }
        }
    } catch (SQLException e) {
        e.printStackTrace();
        JOptionPane.showMessageDialog(
            this,
            "Database error reading questions from " + tableName + ":\n" + e.getMessage(),
            "Database Error",
            JOptionPane.ERROR_MESSAGE
        );
    }

    System.out.println("[QUIZ] built questions: " + questionsList.size());
    return questionsList;
}


// Hides/disables submit; usually at quiz finish/out-of-questions.
private void hideSubmitUI() {
    if (submitButton == null) return;
    submitButton.setEnabled(false);
    submitButton.setVisible(false);
    Container p = submitButton.getParent();
    if (p != null) { p.revalidate(); p.repaint(); }
    if (submitPanel != null) { submitPanel.setVisible(false); submitPanel.revalidate(); submitPanel.repaint(); }
}

// Disables submit and shows "New Quiz" button if quiz can't continue.
private void disableSubmitAndShowNewQuiz() {
    hideSubmitUI();
    if (newQuizButton != null)
        newQuizButton.setEnabled(true);
}

// Shows submit UI; used before each new question.
private void showSubmitUI() {
    if (submitButton == null) return;
    submitButton.setEnabled(true);
    submitButton.setVisible(true);
    JRootPane root = getRootPane();
    if (root != null) root.setDefaultButton(submitButton);
    Container p = submitButton.getParent();
    if (p != null) { p.revalidate(); p.repaint(); }
    if (submitPanel != null) { submitPanel.setVisible(true); submitPanel.revalidate(); submitPanel.repaint(); }
}

// Loads the current question's image/options into the UI.
private void loadImageAndOptions(Question question) {
    if (question == null) {
        if (imageLabel != null) {
            imageLabel.setIcon(null);
            imageLabel.setText("No question to display.");
        }
        return;
    }

    // ---------- Load image (robust, cached) ----------
    String raw = question.getImageLocation() == null ? "" : question.getImageLocation().trim();
    ImageIcon icon = imageCache.get(raw);
    if (icon == null) {
        icon = findImageIconRobust(raw);
        if (icon != null) imageCache.put(raw, icon);
    }

    if (icon == null) {
        if (imageLabel != null) {
            imageLabel.setIcon(null);
            imageLabel.setText("Failed to load image: " + raw);
        }
    } else {
        // Scale to fit: max ~70% height, reasonable width
        int imgW = icon.getIconWidth(), imgH = icon.getIconHeight();
        if (imgW > 0 && imgH > 0 && imageLabel != null) {
            int frameW = Math.max(1, getWidth());
            int frameH = Math.max(1, getHeight());
            int maxH = (int) (frameH * 0.70);
            int maxW = Math.max(1, frameW - 120);
            double r = (double) imgW / imgH;

            int w = Math.min(maxW, (int) Math.round(maxH * r));
            int h = (int) Math.round(w / r);
            if (h > maxH) { h = maxH; w = (int) Math.round(h * r); }

            Image scaled = icon.getImage().getScaledInstance(w, h, Image.SCALE_SMOOTH);
            imageLabel.setIcon(new ImageIcon(scaled));
            imageLabel.setText("");
            imageLabel.revalidate();
            imageLabel.repaint();
        } else if (imageLabel != null) {
            imageLabel.setIcon(icon);
            imageLabel.setText("");
        }
    }

    // ---------- Set multiple-choice option texts ----------
    java.util.List<String> opts = new java.util.ArrayList<>(question.getOptions());
    java.util.Collections.shuffle(opts);

    int count = (options != null) ? Math.min(options.length, opts.size()) : 0;
    for (int i = 0; i < count; i++) {
        if (options[i] != null) {
            options[i].setText(opts.get(i));
            options[i].setSelected(false);
            options[i].setPreferredSize(new Dimension(420, 56));
        }
    }
    // clear extras if any
    if (options != null && opts.size() < options.length) {
        for (int i = opts.size(); i < options.length; i++) {
            if (options[i] != null) {
                options[i].setText("");
                options[i].setSelected(false);
            }
        }
    }

    // ---------- Keep options panel width responsive ----------
    JPanel optionsPanel = getOptionsPanel();
    if (optionsPanel != null) {
        int desiredW = (int) (getWidth() * OPTIONS_PANEL_PERCENT / 100.0);
        optionsPanel.setPreferredSize(new Dimension(desiredW, optionsPanel.getPreferredSize().height));
        optionsPanel.revalidate();
        optionsPanel.repaint();
    }
}




// Ends quiz (user finished all questions).
private void finishQuiz() {
    if (quizFinished) return;
    quizFinished = true;

    // Stop timer & zero the ring
    stopCountdown();
    if (countdownRing != null) {
        try { countdownRing.setSecondsRemaining(0); } catch (Exception ignore) {}
    }

    // Duration (clamped to limit)
    final int durationSeconds;
    if (quizStarted && quizStartMs > 0L) {
        long ms = System.currentTimeMillis() - quizStartMs;
        durationSeconds = Math.min(QUIZ_TIME_LIMIT_SECONDS, (int)Math.max(0, ms / 1000));
    } else {
        durationSeconds = QUIZ_TIME_LIMIT_SECONDS; // fallback
    }

    // New high score?
    boolean newRecord = false;
    try {
        BestEntry prev = fetchBestFromDb(); // your helper
        newRecord = isNewRecord(score, totalQuestionsAnswered, durationSeconds, prev);
    } catch (Exception ex) {
        ex.printStackTrace();
    }
    String suffix = newRecord ? "  🏆 New High Score!" : "";

    // Final message + sfx
    if (score == MAX_QUESTIONS) {
        try { SoundUtil.playSound("bird_nerd.wav"); } catch (Exception ignore) {}
        if (finalMessageLabel != null) {
            finalMessageLabel.setText("You are a Bird Nerd!! Congrats on a perfect score!" + suffix);
        }
        sendSerialMessage("ledSequence");
    } else {
        if (finalMessageLabel != null) {
            finalMessageLabel.setText("Quiz completed! Your final score is " + score + "/" + totalQuestionsAnswered + suffix);
        }
    }

    // Persist results
    try { quizScoreProcessor.saveQuizResult(firstName, email, tableName, score, totalQuestionsAnswered); } catch (Exception ex) { ex.printStackTrace(); }
    try { saveQuizResultToDatabase(); } catch (Exception ex) { ex.printStackTrace(); }
    try { saveAttemptToAttemptsTable(durationSeconds); } catch (Exception ex) { ex.printStackTrace(); }

    // Refresh “To Beat”
    refreshToBeatLabel();

    // Hide Submit; enable New Quiz
    hideSubmitUI(); // removes default button, hides & disables submit, revalidates
    if (newQuizButton != null) {
        newQuizButton.setEnabled(true);
        newQuizButton.requestFocusInWindow();
    }
}


// Ends quiz (on time up).
private void timeUpFinish() {
    if (quizFinished) return;
    quizFinished = true;

    // Stop countdown and zero the ring
    stopCountdown();
    showTemporaryMessage("⏰ Time’s up!");
    if (countdownRing != null) {
        try { countdownRing.setSecondsRemaining(0); } catch (Exception ignore) {}
    }

    // On timeout, duration equals the cap
    final int durationSeconds = QUIZ_TIME_LIMIT_SECONDS;

    // Check if this creates a new record BEFORE saving
    boolean newRecord = false;
    try {
        BestEntry prev = fetchBestFromDb();
        newRecord = isNewRecord(score, totalQuestionsAnswered, durationSeconds, prev);
    } catch (Exception ex) {
        ex.printStackTrace();
    }
    String suffix = newRecord ? "  🏆 New High Score!" : "";

    // Final message
    if (finalMessageLabel != null) {
        finalMessageLabel.setText("Time's up! Final score: " + score + "/" + totalQuestionsAnswered + suffix);
    }

    // Persist results
    try { quizScoreProcessor.saveQuizResult(firstName, email, tableName, score, totalQuestionsAnswered); } catch (Exception ex) { ex.printStackTrace(); }
    try { saveQuizResultToDatabase(); } catch (Exception ex) { ex.printStackTrace(); }
    try { saveAttemptToAttemptsTable(durationSeconds); } catch (Exception ex) { ex.printStackTrace(); }

    // Refresh “To Beat”
    refreshToBeatLabel();

    // Hide Submit; enable New Quiz
    hideSubmitUI();
    if (newQuizButton != null) {
        newQuizButton.setEnabled(true);
        newQuizButton.requestFocusInWindow();
    }
}





// =============================================================================
// Section 8: QUIZ LOGIC & GAME FLOW
// =============================================================================

/** Full quiz/game restart/reset logic. */
private void restartQuiz() {
    try { stopCountdown(); } catch (Exception ignore) {}
    quizFinished = false;
    quizStarted = false;
    quizStartMs = 0L;

    remainingSeconds = QUIZ_TIME_LIMIT_SECONDS;
    try {
        if (countdownRing != null) {
            countdownRing.setTotalSeconds(QUIZ_TIME_LIMIT_SECONDS);
            countdownRing.setSecondsRemaining(QUIZ_TIME_LIMIT_SECONDS);
            countdownRing.repaint();
        }
        if (countdownLabel != null) {
            countdownLabel.setText("Time: " + String.format("%02d:%02d", remainingSeconds / 60, remainingSeconds % 60));
        }
    } catch (Exception ignore) {}

    currentQuestionIndex = 0;
    score = 0;
    totalQuestionsAnswered = 0;

    if (group != null) group.clearSelection();
    if (feedbackLabel != null) feedbackLabel.setText("");
    if (finalMessageLabel != null) { finalMessageLabel.setText(""); finalMessageLabel.setIcon(null); }
    if (scoreLabel != null) scoreLabel.setText("Score: 0/0");

    try {
        questions = fetchBirdQuestions();
        if (questions == null || questions.size() < MAX_QUESTIONS) {
            JOptionPane.showMessageDialog(this, "No questions available.", "Error", JOptionPane.ERROR_MESSAGE);
            if (submitButton != null) submitButton.setEnabled(false);
            if (submitPanel != null) submitPanel.setVisible(false);
            if (newQuizButton != null) newQuizButton.setEnabled(true);
            return;
        }
        Collections.shuffle(questions);
    } catch (Exception ex) {
        ex.printStackTrace();
        JOptionPane.showMessageDialog(this, "Error loading questions: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        if (submitButton != null) submitButton.setEnabled(false);
        if (submitPanel != null) submitPanel.setVisible(false);
        if (newQuizButton != null) newQuizButton.setEnabled(true);
        return;
    }

    if (submitButton != null) {
        for (ActionListener al : submitButton.getActionListeners()) submitButton.removeActionListener(al);
        submitButton.addActionListener(e -> handleSubmission());
        try {
            if (submitIcon != null) submitButton.setIcon(submitIcon);
            if (whiteIcon != null) {
                submitButton.setPressedIcon(whiteIcon);
                submitButton.setRolloverIcon(whiteIcon);
            }
        } catch (Exception ignore) {}
        submitButton.setEnabled(true);
        submitButton.setVisible(true);
        submitButton.setFocusable(true);

        JRootPane root = getRootPane();
        if (root != null) root.setDefaultButton(submitButton);

        java.awt.Container p = submitButton.getParent();
        if (p != null) { p.revalidate(); p.repaint(); }
    }
    if (submitPanel != null) { submitPanel.setVisible(true); submitPanel.revalidate(); submitPanel.repaint(); }
    if (newQuizButton != null) newQuizButton.setEnabled(false);

    nextQuestion();
    if (submitButton != null) submitButton.requestFocusInWindow();
}

/** Goes to the next question or finishes the quiz. */
private void nextQuestion() {
    if (quizFinished) {
        hideSubmitUI();
        if (newQuizButton != null) newQuizButton.setEnabled(true);
        return;
    }

    if (questions == null || questions.isEmpty()) {
        try {
            questions = fetchBirdQuestions();
            if (questions == null || questions.size() < MAX_QUESTIONS) {
                JOptionPane.showMessageDialog(this, "No questions available.", "Error", JOptionPane.ERROR_MESSAGE);
                disableSubmitAndShowNewQuiz();
                return;
            }
            Collections.shuffle(questions);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error loading questions: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            disableSubmitAndShowNewQuiz();
            return;
        }
    }

    if (!quizStarted) {
        quizStarted = true;
        quizStartMs = System.currentTimeMillis();
        remainingSeconds = QUIZ_TIME_LIMIT_SECONDS;
        updateCountdownUI();
        startCountdown();
    }

    if (currentQuestionIndex >= Math.min(questions.size(), MAX_QUESTIONS)) {
        finishQuiz();
        return;
    }

    showSubmitUI();
    if (newQuizButton != null) newQuizButton.setEnabled(false);

    if (group != null) group.clearSelection();
    if (feedbackLabel != null) feedbackLabel.setText("");
    if (finalMessageLabel != null) finalMessageLabel.setText("");
    if (scoreLabel != null) scoreLabel.setText("Score: " + score + "/" + totalQuestionsAnswered);

    loadImageAndOptions(questions.get(currentQuestionIndex));
}

/** Handles answer submission (logical and UI). */
private void handleSubmission() {
    System.out.println("[QUIZ] handleSubmission() entry: finished=" + quizFinished
            + " enabled=" + (submitButton != null && submitButton.isEnabled())
            + " visible=" + (submitButton != null && submitButton.isVisible()));

    if (quizFinished || questions == null || questions.isEmpty()) return;

    ButtonModel selectedModel = (group != null) ? group.getSelection() : null;
    if (selectedModel == null) {
        showTemporaryMessage("⚠ Please select an answer before submitting.");
        return;
    }

    String selectedOption = null;
    if (options != null) {
        for (JRadioButton option : options)
            if (option != null && option.isSelected())
                selectedOption = option.getText();
    }

    totalQuestionsAnswered++;
    String correct = questions.get(currentQuestionIndex).getCorrectName();
    boolean isCorrect = selectedOption != null && selectedOption.equals(correct);

    if (isCorrect) {
        score++;
        if (feedbackLabel != null) feedbackLabel.setText("Your answer was correct!");
        try { SoundUtil.playSound("right_answer.wav"); } catch (Exception ignored) {}
        sendSerialMessage("correct");
    } else {
        if (feedbackLabel != null) feedbackLabel.setText("Incorrect. The correct answer was: " + correct);
        try { SoundUtil.playSound("wrong_answer.wav"); } catch (Exception ignored) {}
        sendSerialMessage("wrong");
    }

    if (scoreLabel != null) scoreLabel.setText("Score: " + score + "/" + totalQuestionsAnswered);
    if (group != null) group.clearSelection();

    if (totalQuestionsAnswered < MAX_QUESTIONS && currentQuestionIndex < questions.size() - 1) {
        currentQuestionIndex++;
        nextQuestion();
    } else {
        finishQuiz();
    }
}

// =============================================================================
// Section 9: SWING EVENT DELEGATE
// =============================================================================

@Override
public void actionPerformed(ActionEvent e) {
    Object src = e.getSource();
    if (src == submitButton) {
        handleSubmission();
        return;
    }
    if (src instanceof JRadioButton) {
        try { SoundUtil.playSound("radio_button_selected.wav"); } catch (Exception ignored) {}
    }
}


// =============================================================================
// Section 10: TIMER & COUNTDOWN HELPERS
// =============================================================================

/** Starts the timer countdown for each quiz run. */
private void startCountdown() {
    stopCountdown(); // Fresh start
    remainingSeconds = Math.max(0, remainingSeconds);
    updateCountdownUI();

    countdownTimer = new javax.swing.Timer(1000, e -> {
        remainingSeconds = Math.max(0, remainingSeconds - 1);
        updateCountdownUI();
        if (remainingSeconds <= 0) {
            stopCountdown();
            timeUpFinish();
        }
    });
    countdownTimer.setInitialDelay(1000);
    countdownTimer.start();
}

/** Stops the quiz countdown (if running). */
private void stopCountdown() {
    if (countdownTimer != null) {
        try { countdownTimer.stop(); } catch (Exception ignore) {}
        countdownTimer = null;
    }
}

/** Updates the timer UI components. */
private void updateCountdownUI() {
    if (countdownRing != null) {
        try {
            countdownRing.setSecondsRemaining(remainingSeconds);
            countdownRing.repaint();
        } catch (Exception ignored) {}
    }
    if (countdownLabel != null) {
        countdownLabel.setText("Time: " + formatMMSS(remainingSeconds));
    }
}

/** Helper to format seconds as mm:ss. */
private String formatMMSS(int totalSeconds) {
    int mm = totalSeconds / 60, ss = totalSeconds % 60;
    return String.format("%02d:%02d", mm, ss);
}

// =============================================================================
// Section 11: SERIAL COMMUNICATION (for buttons, quizport)
// =============================================================================

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
        @Override
        public int getListeningEvents() { return SerialPort.LISTENING_EVENT_DATA_AVAILABLE; }
        @Override
        public void serialEvent(com.fazecast.jSerialComm.SerialPortEvent event) {
            try {
                if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) return;
                int available;
                while ((available = port.bytesAvailable()) > 0) {
                    byte[] buf = new byte[Math.min(available, 256)];
                    int n = port.readBytes(buf, buf.length);
                    if (n <= 0) break;
                    for (int i = 0; i < n; i++) {
                        char c = (char) (buf[i] & 0xFF);
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
    System.out.println("[QUIZ] ESP token: " + token);
    setSerialStatus("Serial: " + quizPortName + " (" + token + ")");

    switch (token) {
        case "blue":   selectOptionIndex(0); break;
        case "green":  selectOptionIndex(1); break;
        case "yellow": selectOptionIndex(2); break;
        case "white":  selectOptionIndex(3); break;
        case "submit":
        case "enter":
        case "ok":
            if (System.currentTimeMillis() - lastSubmitTime > SUBMIT_COOLDOWN_MS) {
                lastSubmitTime = System.currentTimeMillis();
                SwingUtilities.invokeLater(() -> {
                    System.out.println("[QUIZ] ESP -> submitButton.doClick()");
                    submitButton.doClick();
                });
            }
            break;
        default:
            System.out.println("[QUIZ] Unknown token: " + token);
    }
}

private void selectOptionIndex(int idx) {
    try {
        if (options != null && idx >= 0 && idx < options.length && options[idx] != null) {
            final int i = idx;
            SwingUtilities.invokeLater(() -> options[i].doClick(0));
        }
    } catch (Exception ex) { ex.printStackTrace(); }
}

private void sendSerialMessage(String message) {
    try {
        if (quizPort != null && quizPort.isOpen()) {
            String m = message + "\n";
            quizPort.writeBytes(m.getBytes(), m.length());
        }
    } catch (Exception ignored) {}
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

private void setSerialStatus(String text) {
    if (serialStatusLabel != null) serialStatusLabel.setText(text);
}

private void setResetEnabled(boolean enabled) {
    if (resetSerialButton != null) resetSerialButton.setEnabled(enabled);
}

private void reconnectSerial() {
    long now = System.currentTimeMillis();
    if (now - lastSerialAttemptMs < 1000) return;
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

// =============================================================================
// Section 12: IMAGE/RESOURCE HELPERS
// =============================================================================

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

private static InputStream resourceStreamOrThrow(String absolutePath) throws IOException {
    InputStream in = BirdQuizGUI.class.getResourceAsStream(absolutePath);
    if (in == null)
        throw new FileNotFoundException("Resource not found on classpath: " + absolutePath);
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

// =============================================================================
// Section 13: MODELS: QUESTION & BIRD
// =============================================================================

public static class Question {
    private final String correctName;
    private final String imageLocation;
    private final java.util.List<String> options;

    public Question(String correctName, String imageLocation, java.util.List<String> options) {
        this.correctName = correctName; this.imageLocation = imageLocation; this.options = options;
    }
    public String getCorrectName()   { return correctName; }
    public String getImageLocation() { return imageLocation; }
    public java.util.List<String> getOptions() { return options; }
}

public static class Bird {
    private final String name, imageLocation, category;

    public Bird(String name, String imageLocation, String category) {
        this.name = name;
        this.imageLocation = imageLocation;
        this.category = category;
    }
    public String getName() { return name; }
    public String getImageLocation() { return imageLocation; }
    public String getCategory() { return category; }
}

// =============================================================================
// Section 14: TOAST MESSAGE UTILITY
// =============================================================================

/**
 * Displays a non-blocking temporary "toast" message above the main window.
 */
private void showTemporaryMessage(String message) {
    final JWindow popup = new JWindow();
    popup.setBackground(new Color(0, 0, 0, 0));
    final JPanel toast = new JPanel() {
        private final int arc = 18, shadow = 14, yOffset = 3;
        @Override protected void paintComponent(Graphics g) {
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
        Point parentOnScreen = this.getLocationOnScreen();
        int x = parentOnScreen.x + (this.getWidth() - popup.getWidth()) / 2;
        int y = parentOnScreen.y + this.getHeight() - popup.getHeight() - 60;
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
        float alpha = 1f;
        Object val = toast.getClientProperty("alphaVal");
        if (val instanceof Float) alpha = (Float) val;
        alpha -= 0.07f;
        toast.putClientProperty("alphaVal", alpha);
        toast.repaint();
        if (alpha <= 0f) {
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

// =============================================================================
// Section 15: COUNTDOWNRING INNER CLASS (TIMER RING DISPLAY)
// =============================================================================

@SuppressWarnings("serial")
private static class CountdownRing extends JComponent {
    private int total = 60, remaining = 60;
    void setTotalSeconds(int s) { total = Math.max(1, s); repaint(); }
    void setSecondsRemaining(int s) { remaining = Math.max(0, Math.min(s, total)); repaint(); }
    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        int d = Math.min(w, h) - 4; int x = (w - d)/2; int y = (h - d)/2;

        Color bg = new Color(0,0,0,40);
        Color ok = new Color(50,160,90);
        Color warn = new Color(240,170,50);
        Color urgent = new Color(220,70,70);
        Color ring = remaining <= 10 ? urgent : (remaining <= 30 ? warn : ok);

        g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(bg); g2.drawOval(x, y, d, d);

        float pct = total > 0 ? (remaining / (float) total) : 0f;
        int angle = Math.round(360f * pct);
        g2.setColor(ring); g2.drawArc(x, y, d, d, 90, -angle);

        String txt = String.format("%02d:%02d", remaining/60, remaining%60);
        g2.setFont(new Font("Arial", Font.BOLD, 14));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(new Color(0,0,0,180));
        g2.drawString(txt, w/2 - fm.stringWidth(txt)/2, h/2 + fm.getAscent()/2 - 2);
        g2.dispose();
    }
    @Override public Dimension getPreferredSize() { return new Dimension(72,72); }
}

// END OF BirdQuizGUI class
}
