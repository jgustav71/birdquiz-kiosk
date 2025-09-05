package birdquiz;
import birdquiz.UserSession;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import com.fazecast.jSerialComm.SerialPort;



public class MainMenu extends JFrame implements ActionListener {
    // Declare UI components
    private JButton ducksButton;
    private JButton raptorsButton;
    private JButton songbirdsButton;
    private JComboBox<String> serialPortComboBox;
    private JTextField firstNameField;
    private JTextField emailField;

    public MainMenu() {
        setTitle("Select Your Quiz and Serial Port");
        setSize(700, 700);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null); // Center the frame

        // Create a panel to hold the components with padding
        JPanel contentPanel = new JPanel(new GridLayout(6, 1, 10, 10)); // (rows, cols, hgap, vgap) for spacing
        contentPanel.setBorder(new EmptyBorder(20, 20, 20, 20)); // Padding around the content

        // Initialize serial port dropdown
        serialPortComboBox = new JComboBox<>();
        fillSerialPortComboBox();

        // Initialize text fields for user input
        firstNameField = new JTextField();
        emailField = new JTextField();

        // Initialize the buttons
        ducksButton = new JButton("Ducks Quiz");
        raptorsButton = new JButton("Raptors Quiz");
        songbirdsButton = new JButton("Songbirds Quiz");

        // Add components to content panel
        contentPanel.add(new JLabel("Select Serial Port:"));
        contentPanel.add(serialPortComboBox);
        contentPanel.add(new JLabel("Enter First Name:"));
        contentPanel.add(firstNameField);
        contentPanel.add(new JLabel("Enter Email:"));
        contentPanel.add(emailField);
        contentPanel.add(ducksButton);
        contentPanel.add(raptorsButton);
        contentPanel.add(songbirdsButton);

        // Add the content panel to the frame
        add(contentPanel, BorderLayout.CENTER);

        // Register action listeners
        ducksButton.addActionListener(this);
        raptorsButton.addActionListener(this);
        songbirdsButton.addActionListener(this);
    }

    private void fillSerialPortComboBox() {
        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort port : ports) {
            serialPortComboBox.addItem(port.getSystemPortName());
        }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        String firstName = firstNameField.getText().trim();
        String email = emailField.getText().trim();
        String selectedPort = (String) serialPortComboBox.getSelectedItem();

        if (firstName.isEmpty() || email.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter your first name and email.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Store user details in UserSession
        UserSession userSession = UserSession.getInstance();
        userSession.setFirstName(firstName);
        userSession.setEmail(email);

        // Debug print to verify user data
        System.out.println("Stored User Session Data:");
        System.out.println("First Name: " + userSession.getFirstName());
        System.out.println("Email: " + userSession.getEmail());

        if (e.getSource() == ducksButton) {
            SoundUtil.playSound("ducks.wav");
            System.out.println("Ducks Quiz selected");
            new BirdQuizGUI("ducks", selectedPort, firstName, email).setVisible(true);
            dispose(); // Close the menu after selection
        } else if (e.getSource() == raptorsButton) {
            SoundUtil.playSound("raptors.wav");
            System.out.println("Raptors Quiz selected");
            new BirdQuizGUI("raptors", selectedPort, firstName, email).setVisible(true);
            dispose(); // Close the menu after selection
        } else if (e.getSource() == songbirdsButton) {
            SoundUtil.playSound("songbirds.wav");
            System.out.println("Songbirds Quiz selected");
            new BirdQuizGUI("songbirds", selectedPort, firstName, email).setVisible(true);
            dispose(); // Close the menu after selection
        } else {
            // Optional: Handle unexpected actions
            JOptionPane.showMessageDialog(this, "Unknown action performed", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MainMenu mainMenu = new MainMenu();
            mainMenu.setVisible(true);
        });
    }
}