package server;

import java.util.*;

public class User {

    private String name;
    private String username;
    private String password;
    private int sessionScore;                   // score for the current game session
    private List<Integer> scoreHistory = Collections.synchronizedList(new ArrayList<>());        // history of final scores from past games

    public User(String name, String username, String password) {
        this.name         = name;
        this.username     = username;
        this.password     = password;
        this.sessionScore = 0;
        this.scoreHistory = new ArrayList<>();
    }

    public String getName()     { return name; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public int    getScore()    { return sessionScore; }

    public synchronized void addScore(int points) { sessionScore += points; }

    /** Called at the end of a game to snapshot the score into history and reset. */
    public synchronized void finalizeScore() {
        scoreHistory.add(sessionScore);
        sessionScore = 0;
    }

    public List<Integer> getScoreHistory() { return Collections.unmodifiableList(scoreHistory); }

    /** Load historical scores from a pre-parsed list (called by ScoreHistory on startup). */
    public void loadHistory(List<Integer> history) {
        scoreHistory.clear();
        scoreHistory.addAll(history);
    }
}