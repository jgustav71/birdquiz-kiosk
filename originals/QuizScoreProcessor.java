package birdquiz;

import java.sql.*;

public class QuizScoreProcessor {

    private static final String DB_URL = "jdbc:mysql://localhost:3306/birds_db";
    private static final String DB_USERNAME = "root";
    private static final String DB_PASSWORD = "Neskowin71!!!";

    private Connection connection;

    public QuizScoreProcessor() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            connection = DriverManager.getConnection(DB_URL, DB_USERNAME, DB_PASSWORD);
        } catch (ClassNotFoundException | SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveQuizResult(String firstName, String email, String quizName, double percentageScore, int totalQuestionsAnswered) {
        String insertQuery = "INSERT INTO quiz_results (first_name, email, quiz_name, ducks_average_score, raptors_average_score, songbirds_average_score, all_quizzes_average_score) VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(insertQuery)) {
            stmt.setString(1, firstName);
            stmt.setString(2, email);
            stmt.setString(3, quizName);

            // Insert percentage score based on the quiz name
            stmt.setDouble(4, quizName.equals("ducks") ? percentageScore : 0);
            stmt.setDouble(5, quizName.equals("raptors") ? percentageScore : 0);
            stmt.setDouble(6, quizName.equals("songbirds") ? percentageScore : 0);

            // Insert all quizzes average score (in this context, the same as the individual percentage score)
            stmt.setDouble(7, percentageScore);

            stmt.executeUpdate();
            System.out.println("Quiz result saved successfully.");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}