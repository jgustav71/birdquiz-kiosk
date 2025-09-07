package birdquiz;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.sql.*;
import java.util.*;
import javax.imageio.ImageIO;
import com.fazecast.jSerialComm.*;

public class BirdQuizGUI extends JFrame implements ActionListener {
    private static final String DB_URL = "jdbc:mysql://localhost:3306/birds_db";
    private static final String DB_USERNAME = "root";
    private static final String DB_PASSWORD = "Neskowin71";
    private static final int MAX_QUESTIONS = 5;
    private static final Font RADIO_BUTTON_FONT = new Font("Arial", Font.PLAIN, 16);
    private static final Font BOTTOM_BUTTON_FONT = new Font("Arial", Font.BOLD, 30);

    private Connection connection;
    private java.util.List<Question> questions;
    private int currentQuestionIndex = 0;
    private int score = 0;
    private int totalQuestionsAnswered = 0;

    private JLabel imageLabel, feedbackLabel, scoreLabel, finalMessageLabel;
    private JRadioButton[] options;
    private JButton submitButton, exitButton, newQuizButton, menuButton;
    private ButtonGroup group;

    private String[] imageNames = {"blue.png", "green.png", "yellow.png"};
    private String[] selectedImageNames = {"blue_selected.png", "green_selected.png", "yellow_selected.png"};
    private ImageIcon[] resizedImages = new ImageIcon[imageNames.length];
    private ImageIcon[] selectedResizedImages = new ImageIcon[imageNames.length];
    private ImageIcon submitIcon, whiteIcon;
    private static final double OPTIONS_PANEL_PERCENT = 70.0;

    private Map<String, ImageIcon> imageCache = new HashMap<>();
    private String tableName;

    private QuizScoreProcessor quizScoreProcessor;
    private String firstName;
    private String email;

    private long lastSubmitTime = 0;
    private static final long SUBMIT_COOLDOWN = 1000;

    private SerialPort serialPort;
    private StringBuffer serialBuffer = new StringBuffer();

    public BirdQuizGUI(String tableName, String serialPortName, String firstName, String email) {
        this.tableName = tableName;
        this.firstName = firstName;
        this.email = email;

        quizScoreProcessor = new QuizScoreProcessor();

        initializeDatabaseConnection();
        initializeQuestionsAndImages();
        setupUI();

        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setUndecorated(true);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setVisible(true);

        nextQuestion();
        setupSerial(serialPortName);
        addComponentListenerToOptionsPanel();
    }

    public BirdQuizGUI(String tableName, String serialPortName) {
        this(tableName, serialPortName, "", "");
    }

    private void initializeDatabaseConnection() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            System.out.println("Database driver loaded");
            connection = DriverManager.getConnection(DB_URL, DB_USERNAME, DB_PASSWORD);
            System.out.println("Database connection established");
        } catch (ClassNotFoundException | SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Database error: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
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
            JOptionPane.showMessageDialog(this, "Error initializing questions or images: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
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
    final String base = "/buttons/"; // absolute, rooted at classpath
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
        whiteIcon = scaleIcon(selected, 50, 50);
    }
}


    private void setupUI() {
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        setupImageLabel(gbc);
        setupOptionsPanel(gbc);
        setupSubmitPanel(gbc);
        setupFeedbackLabel(gbc);
        setupFinalMessageLabel(gbc); // Added line
        setupScoreLabel(gbc);
        setupBottomPanel(gbc);
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
        JPanel optionsPanel = new JPanel(new GridBagLayout());
        optionsPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20)); // Adjust border for layout
        options = new JRadioButton[3];
        group = new ButtonGroup();
    
        for (int i = 0; i < options.length; i++) {
            options[i] = createRadioButtonWithIcons(i);
            options[i].setFont(new Font("Arial", Font.PLAIN, 25)); // Set font size to 25
            group.add(options[i]);
    
            GridBagConstraints optGbc = new GridBagConstraints();
            optGbc.gridx = i; // Position buttons horizontally
            optGbc.gridy = 0; // Single row
            optGbc.insets = new Insets(10, 20, 10, 20); // Adjust insets for spacing
            optGbc.anchor = GridBagConstraints.CENTER;
            optGbc.fill = GridBagConstraints.HORIZONTAL; // Ensure the button stretches horizontally
    
            optionsPanel.add(options[i], optGbc);
        }
    
        gbc.gridy = 1; // Set position for the optionsPanel
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
        gbc.fill = GridBagConstraints.CENTER;
        gbc.gridwidth = 3;
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
        gbc.gridy = 5; // Position it after the score label
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
        addRadioButtonListener(radioButton);
        
        // Set the larger font size
        radioButton.setFont(new Font("Arial", Font.PLAIN, 25)); // Set the font size to 25
        radioButton.setHorizontalTextPosition(SwingConstants.RIGHT);
    

        radioButton.setVerticalTextPosition(SwingConstants.CENTER);
        radioButton.setIconTextGap(10);
        
        // Adjust the preferred size to avoid text cut-off
        Dimension preferredSize = new Dimension(300, 50);  // Increase width to ensure enough space
        radioButton.setPreferredSize(preferredSize);
        
        return radioButton;
    }

    private void addRadioButtonListener(JRadioButton radioButton) {
        radioButton.addActionListener(this);
        radioButton.addActionListener(e -> {
            System.out.println("Radio button " + radioButton.getClientProperty("index") + " selected.");
            SoundUtil.playSound("radio_button_selected.wav"); // Assuming you have a SoundUtil for playing sounds
        });
    }

private void setupSerial(String portName) {
    try {
        // Debug: List all available ports
        System.out.println("Scanning available serial ports...");
        for (SerialPort port : SerialPort.getCommPorts()) {
            System.out.println("Available port: " + port.getSystemPortName());
        }

        serialPort = SerialPort.getCommPort(portName);
        if (serialPort.openPort()) {
            serialPort.setBaudRate(115200);
            serialPort.setNumDataBits(8);
            serialPort.setNumStopBits(1);
            serialPort.setParity(SerialPort.NO_PARITY);
            serialPort.addDataListener(new SerialPortDataListener() {
                private StringBuilder serialBuffer = new StringBuilder();
                
                @Override
                public int getListeningEvents() {
                    return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
                }

                @Override
                public void serialEvent(SerialPortEvent event) {
                    if (event.getEventType() == SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
                        try {
                            byte[] newData = new byte[serialPort.bytesAvailable()];
                            serialPort.readBytes(newData, newData.length);
                            String receivedData = new String(newData);

                            serialBuffer.append(receivedData);

                            int index;
                            while ((index = serialBuffer.indexOf("\n")) != -1) {
                                String completeCommand = serialBuffer.substring(0, index).trim();
                                serialBuffer.delete(0, index + 1);

                                // Debugging output
                                System.out.println("Received Serial Data: " + completeCommand);
                                handleSerialInput(completeCommand);
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            });
            System.out.println("Serial port initialized on " + portName);
        } else {
            throw new Exception("Failed to open the serial port.");
        }
    } catch (Exception ex) {
        ex.printStackTrace();
        JOptionPane.showMessageDialog(this, "Error initializing serial port: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
}






    private JPanel createBottomPanel() {
        JPanel bottomPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbcBottom = new GridBagConstraints();
        Dimension buttonSize = new Dimension(120, 50);
        gbcBottom.gridwidth = 1;
        gbcBottom.insets = new Insets(20, 10, 20, 10);
        newQuizButton = new JButton("New Quiz");
        newQuizButton.setPreferredSize(buttonSize);
        newQuizButton.setFont(new Font("Arial", Font.PLAIN, 12));
        newQuizButton.addActionListener(e -> restartQuiz());
        gbcBottom.gridx = 0;
        gbcBottom.gridy = 1;
        bottomPanel.add(newQuizButton, gbcBottom);
        exitButton = new JButton("Exit Quiz");
        exitButton.setPreferredSize(buttonSize);
        exitButton.setFont(new Font("Arial", Font.PLAIN, 12));
        exitButton.addActionListener(e -> System.exit(0));
        gbcBottom.gridx = 1;
        bottomPanel.add(exitButton, gbcBottom);
        menuButton = new JButton("Menu");
        menuButton.setPreferredSize(buttonSize);
        menuButton.setFont(new Font("Arial", Font.PLAIN, 12));
        menuButton.addActionListener(e -> {
            new MainMenu().setVisible(true);
            dispose();
        });
        gbcBottom.gridx = 2;
        bottomPanel.add(menuButton, gbcBottom);
        return bottomPanel;
    }

    private JPanel getOptionsPanel() {
        for (Component comp : getContentPane().getComponents()) {
            if (comp instanceof JPanel) {
                JPanel panel = (JPanel) comp;
                for (Component innerComp : panel.getComponents()) {
                    if (innerComp instanceof JRadioButton) {
                        return panel;
                    }
                }
            }
        }
        return null; // Return null if no optionsPanel is found
    }

    private void addComponentListenerToOptionsPanel() {
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                JPanel optionsPanel = getOptionsPanel();
                if (optionsPanel != null) {
                    optionsPanel.setPreferredSize(new Dimension((int) (getWidth() * OPTIONS_PANEL_PERCENT / 100.0), optionsPanel.getPreferredSize().height));
                    optionsPanel.revalidate();
                    optionsPanel.repaint();
                }
            }
        });
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
            JOptionPane.showMessageDialog(this, "Database error: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        feedbackLabel.setText("");
        scoreLabel.setText("Score: 0/0");
        finalMessageLabel.setText(""); // Clear any previous final message
        submitButton.setEnabled(true);
        newQuizButton.setEnabled(false);
        nextQuestion();
    }

    private java.util.List<Question> fetchBirdQuestions() {
        java.util.List<Question> questionsList = new ArrayList<>();
        java.util.List<Bird> birds = new ArrayList<>();
        String fetchAllSql = "SELECT name, male_image_location, category FROM " + tableName;
    
        try (PreparedStatement fetchAllStmt = connection.prepareStatement(fetchAllSql);
             ResultSet rs = fetchAllStmt.executeQuery()) {
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
    
                java.util.List<Bird> categoryBirds = new ArrayList<>();
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
                    java.util.List<String> optionsList = new ArrayList<>(optionsSet);
                    Collections.shuffle(optionsList);
                    questionsList.add(new Question(correctName, imageLocation, optionsList));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return questionsList;
    }

private void handleSerialInput(String input) {
    input = input.trim().toLowerCase();

    // Ignore debug messages like "Button pressed: yellow"
    if (input.startsWith("button pressed:") || input.startsWith("button released:") || input.isEmpty()) {
        return;
    }

    Runnable selectRadioButtonTask = createRadioButtonTask(input);

    if (selectRadioButtonTask != null) {
        SwingUtilities.invokeLater(() -> {
            selectRadioButtonTask.run();
            JPanel optionsPanel = getOptionsPanel();
            if (optionsPanel != null) {
                optionsPanel.revalidate();
                optionsPanel.repaint();
            }
        });
    } else {
        System.err.println("Unknown input: " + input);
    }
}


    private Runnable createRadioButtonTask(String input) {
        switch (input) {
            case "yellow":
                return () -> {
                    options[2].setSelected(true);
                    options[2].doClick();
                };
            case "blue":
                return () -> {
                    options[0].setSelected(true);
                    options[0].doClick();
                };
            case "green":
                return () -> {
                    options[1].setSelected(true);
                    options[1].doClick();
                };
            case "submit":
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastSubmitTime > SUBMIT_COOLDOWN) {
                    submitButton.doClick();
                    lastSubmitTime = currentTime;
                }
                return null;
            default:
                System.err.println("Unknown input: " + input);
                return null;
        }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == submitButton) {
            System.out.println("Submit button clicked.");
            handleSubmission();
            SoundUtil.playSound("submit_button_pressed.wav");
        } else if (e.getSource() instanceof JRadioButton) {
            JRadioButton radioButton = (JRadioButton) e.getSource();
            int index = (int) radioButton.getClientProperty("index");
            System.out.println("Radio button " + index + " selected via Action Event");
        }
    }

    private void handleSubmission() {
        ButtonModel selectedModel = group.getSelection();
        if (selectedModel == null) {
            JOptionPane.showMessageDialog(this, "Please select an answer before submitting.", "No Selection", JOptionPane.WARNING_MESSAGE);
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
    
        if (selectedOption != null && selectedOption.equals(questions.get(currentQuestionIndex).getCorrectName())) {
            score++;
            feedbackLabel.setText("Your answer was correct!");
            SoundUtil.playSound("right_answer.wav");
            sendSerialMessage("correct");
        } else {
            feedbackLabel.setText("Incorrect. The correct answer was: " + questions.get(currentQuestionIndex).getCorrectName());
            SoundUtil.playSound("wrong_answer.wav");
            sendSerialMessage("wrong");
        }
    
        scoreLabel.setText("Score: " + score + "/" + totalQuestionsAnswered);
        group.clearSelection();
    
        if (totalQuestionsAnswered < MAX_QUESTIONS) {
            currentQuestionIndex++;
            nextQuestion();
        } else {
            if (score == MAX_QUESTIONS) {
                SoundUtil.playSound("bird_nerd.wav");
                finalMessageLabel.setText("You are a Bird Nerd!! Congrats on a perfect score!");
                sendSerialMessage("ledSequence");
            } else {
                finalMessageLabel.setText("Quiz completed! Your final score is " + score + "/" + totalQuestionsAnswered);
            }
    
            quizScoreProcessor.saveQuizResult(firstName, email, tableName, score, totalQuestionsAnswered);
            saveQuizResultToDatabase();
    
            submitButton.setEnabled(false);
            newQuizButton.setEnabled(true);
        }
    }
    
    private void sendSerialMessage(String message) {
        if (serialPort != null && serialPort.isOpen()) {
            String formattedMessage = message + "\n";
            serialPort.writeBytes(formattedMessage.getBytes(), formattedMessage.length());
            System.out.println("Sent to Arduino: " + message);
        }
    }



    private void saveQuizResultToDatabase() {
        String sql = "INSERT INTO quiz_results (first_name, email, quiz_table, score, total_questions) VALUES (?, ?, ?, ?, ?)";
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
    String imgLocation = question.getImageLocation(); // e.g. "chickadee.jpg"
    System.out.println("Image filename from DB: " + imgLocation);

    ImageIcon imageIcon = imageCache.get(imgLocation);

    if (imageIcon == null) {
        // Search through /images and its subfolders
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
        return;
    }

    // --- scaling + options (same as you had before) ---
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
        Dimension preferredSize = new Dimension(400, 50);
        options[i].setPreferredSize(preferredSize);
    }

    JPanel optionsPanel = getOptionsPanel();
    if (optionsPanel != null) {
        optionsPanel.setPreferredSize(new Dimension(
            (int) (getWidth() * OPTIONS_PANEL_PERCENT / 100.0),
            optionsPanel.getPreferredSize().height));
        optionsPanel.revalidate();
        optionsPanel.repaint();
    }
}










    public static class Question {
        private final String correctName;
        private final String imageLocation;
        private final java.util.List<String> options;
    
        public Question(String correctName, String imageLocation, java.util.List<String> options) {
            this.correctName = correctName;
            this.imageLocation = imageLocation;
            this.options = options;
        }
    
        public String getCorrectName() {
            return correctName;
        }
    
        public String getImageLocation() {
            return imageLocation;
        }
    
        public java.util.List<String> getOptions() {
            return options;
        }
    }
    
    public static class Bird {
        private final String name;
        private final String imageLocation;
        private final String category;
    
        public Bird(String name, String imageLocation, String category) {
            this.name = name;
            this.imageLocation = imageLocation;
            this.category = category;
        }
    
        public String getName() {
            return name;
        }
    
        public String getImageLocation() {
            return imageLocation;
        }
    
        public String getCategory() {
            return category;
        }
    }




    
}


