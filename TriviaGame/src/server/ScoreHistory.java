package server;

import java.io.*;
import java.util.*;

/**
 * Handles reading and writing of per-user score history.
 *
 * File format (scores.txt):  username,score1,score2,...
 * Example:                   jana,150,200,180
 */
public class ScoreHistory {

    private final String filePath;

    public ScoreHistory(String filePath) {
        this.filePath = filePath;
    }

    /**
     * Load score history from file into the provided user map.
     * Called once on server startup after AuthManager has populated the users.
     */
    public void loadInto(Map<String, User> userMap) {
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                String username = parts[0].trim();
                User user = userMap.get(username);
                if (user == null) continue;
                List<Integer> history = new ArrayList<>();
                for (int i = 1; i < parts.length; i++) {
                    try { history.add(Integer.parseInt(parts[i].trim())); }
                    catch (NumberFormatException ignored) {}
                }
                user.loadHistory(history);
            }
        } catch (Exception e) {
            System.out.println("[ScoreHistory] Could not load scores file: " + e.getMessage());
        }
    }

    /**
     * Persist all users' score histories back to disk.
     * Called after every completed game.
     */
    public void saveAll(Collection<User> users) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, false))) {
            for (User u : users) {
                List<Integer> history = u.getScoreHistory();
                if (history.isEmpty()) continue;
                StringBuilder sb = new StringBuilder(u.getUsername());
                for (int s : history) sb.append(",").append(s);
                pw.println(sb);
            }
        } catch (Exception e) {
            System.out.println("[ScoreHistory] Could not save scores: " + e.getMessage());
        }
    }
}