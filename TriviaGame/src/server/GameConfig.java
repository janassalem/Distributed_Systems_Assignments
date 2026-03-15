package server;

import java.io.*;
import java.util.*;

/**
 * Loads game configuration from Data/config.txt.
 * Format:  key=value  (lines starting with # are ignored)
 */
public class GameConfig {

    private int minPlayers         = 2;
    private int maxPlayers         = 4;
    private int questionTimeSeconds = 15;
    private int pointsPerCorrect   = 10;

    public GameConfig(String filePath) {
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length < 2) continue;
                String key = parts[0].trim();
                String val = parts[1].trim();
                switch (key) {
                    case "min_players":          minPlayers          = Integer.parseInt(val); break;
                    case "max_players":          maxPlayers          = Integer.parseInt(val); break;
                    case "question_time_seconds": questionTimeSeconds = Integer.parseInt(val); break;
                    case "points_per_correct":   pointsPerCorrect    = Integer.parseInt(val); break;
                }
            }
            System.out.println("[Config] Loaded: min=" + minPlayers + " max=" + maxPlayers
                    + " time=" + questionTimeSeconds + "s pts=" + pointsPerCorrect);
        } catch (Exception e) {
            System.out.println("[Config] Could not load config file, using defaults: " + e.getMessage());
        }
    }

    public int getMinPlayers()          { return minPlayers; }
    public int getMaxPlayers()          { return maxPlayers; }
    public int getQuestionTimeSeconds() { return questionTimeSeconds; }
    public int getPointsPerCorrect()    { return pointsPerCorrect; }
}