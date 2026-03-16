package server;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {

    // username -> User object
    private Map<String, User> users = new ConcurrentHashMap<>();  // safe across multiple threads.
    private final String filePath;

    // Return codes
    public static final int OK           = 200;
    public static final int UNAUTHORIZED = 401;  // wrong password
    public static final int NOT_FOUND    = 404;  // username doesn't exist
    public static final int CONFLICT     = 409;  // username already taken

    public AuthManager(String file) {
        this.filePath = file;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;  // skip empty lines

                // Format: name,username,password
                String[] parts = line.split(",", 3);
                if (parts.length < 3) continue;  // skip malformed lines

                String name     = parts[0].trim();
                String username = parts[1].trim();
                String password = parts[2].trim();

                // Add the user to the in-memory map
                users.put(username, new User(name, username, password));
            }
        } catch (FileNotFoundException fnfe) {
            System.out.println("[AuthManager] Users file not found: " + file);
        } catch (IOException ioe) {
            System.out.println("[AuthManager] Error reading users file: " + ioe.getMessage());
        } catch (Exception e) {
            System.out.println("[AuthManager] Unexpected error loading users file: " + e.getMessage());
        }
    }

    /** Returns OK, NOT_FOUND, or UNAUTHORIZED. */
    public int login(String username, String password) {
        if (!users.containsKey(username)) return NOT_FOUND;
        if (!users.get(username).getPassword().equals(password)) return UNAUTHORIZED;
        return OK;
    }

    /** Returns OK or CONFLICT. */
    public int register(String name, String username, String password) {
        if (users.containsKey(username)) return CONFLICT;
        User u = new User(name, username, password);
        users.put(username, u);
        persistUser(name, username, password);
        return OK;
    }

    /** Returns the User object after a successful login/register, null otherwise. */
    public User getUser(String username) {
        return users.get(username);
    }

    // Append the new user to the users file so it persists across restarts.
    private synchronized void persistUser(String name, String username, String password) { // one user writes at a time
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, true))) {
            pw.println(name + "," + username + "," + password);
        } catch (Exception e) {
            System.out.println("[AuthManager] Could not save new user: " + e.getMessage());
        }
    }

    /** Returns the live user map (username -> User). Used by ScoreHistory on startup. */
    public Map<String, User> getUserMap() {
        return users;
    }
}