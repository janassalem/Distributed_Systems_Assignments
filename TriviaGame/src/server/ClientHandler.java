package server;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/**
 * Handles one connected client.
 * Lifecycle:
 *   1. Auth phase      — login or register
 *   2. Main menu       — Single Player | Multiplayer
 *   3a. Single player  — game starts immediately
 *   3b. Quick match    — wait in lobby until MIN_PLAYERS reached
 *   3c. Teams          — create or join a team, wait for equal-size opponent
 *   4. Game phase      — GameManager owns the input stream
 *   5. Loop back to 2  — player can play again after game ends
 */
public class ClientHandler implements Runnable {

    private final Socket       socket;
    private final BufferedReader in;
    private final PrintWriter  out;
    private final AuthManager  authManager;
    private final GameServer   gameServer;

    private User user;
    private volatile GameManager    currentGame = null;
    private volatile CountDownLatch gameLatch   = null;

    // Name of the team this player is waiting in (null if not in a team)
    private volatile String waitingTeamName = null;

    public ClientHandler(Socket socket, AuthManager authManager, GameServer gameServer) throws Exception {
        this.socket      = socket;
        this.authManager = authManager;
        this.gameServer  = gameServer;
        this.in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.out = new PrintWriter(socket.getOutputStream(), true);
    }

    // =========================================================================
    // Main thread
    // =========================================================================

    @Override
    public void run() {
        try {
            sendMessage("=============================");
            sendMessage("  Welcome to Trivia Game!   ");
            sendMessage("=============================");
            sendMessage("Type '-' at any time to quit.");

            if (!authPhase()) return;

            // Main menu loop — player can play multiple games
            while (true) {
                if (!mainMenuPhase()) return;   // player typed '-'
            }

        } catch (Exception e) {
            // socket closed or network error
        } finally {
            handleDisconnect();
        }
    }

    // =========================================================================
    // Main menu (Req 5)
    // =========================================================================

    /**
     * Shows the main menu and routes to the chosen mode.
     * Returns false if the player wants to quit, true otherwise.
     */
    private boolean mainMenuPhase() throws IOException, InterruptedException {
        sendMessage("");
        sendMessage("===== MAIN MENU =====");
        sendMessage("[1] Single Player");
        sendMessage("[2] Multiplayer");
        sendMessage("[3] View Score History");
        sendMessage("Choice: ");

        String choice = readLine();
        if (choice == null || choice.equals("-")) return false;

        switch (choice) {
            case "1": return singlePlayerFlow();
            case "2": return multiplayerMenuPhase();
            case "3": showScoreHistory(); return true;
            default:
                sendMessage("Invalid choice. Please enter 1, 2, or 3.");
                return true;
        }
    }

    // =========================================================================
    // Single player (Req 5)
    // =========================================================================

    private boolean singlePlayerFlow() throws InterruptedException {
        sendMessage("Starting single player game...");
        gameLatch = new CountDownLatch(1);
        gameServer.startSinglePlayer(this);
        gameLatch.await();
        return true;
    }

    // =========================================================================
    // Multiplayer menu (Req 5 + 6)
    // =========================================================================

    private boolean multiplayerMenuPhase() throws IOException, InterruptedException {
        while (true) {
            sendMessage("");
            sendMessage("===== MULTIPLAYER =====");
            sendMessage("[1] Quick Match  (join public lobby)");
            sendMessage("[2] Create Team");
            sendMessage("[3] Join Team");
            sendMessage("[4] Back");
            sendMessage("Choice: ");

            String choice = readLine();
            if (choice == null || choice.equals("-")) return false;

            switch (choice) {
                case "1": return quickMatchFlow();
                case "2": return createTeamFlow();
                case "3": return joinTeamFlow();
                case "4": return true;
                default: sendMessage("Invalid choice.");
            }
        }
    }

    // ---- Quick match --------------------------------------------------------

    private boolean quickMatchFlow() throws InterruptedException {
        sendMessage("Joining public lobby...");
        gameLatch = new CountDownLatch(1);
        gameServer.joinQuickMatch(this);
        gameLatch.await();
        return true;
    }

    // ---- Create team (Req 6) ------------------------------------------------

    private boolean createTeamFlow() throws IOException, InterruptedException {
        sendMessage("");
        sendMessage("-- Create Team --");

        // Team name
        sendMessage("Team name: ");
        String teamName = readLine();
        if (teamName == null || teamName.equals("-")) return false;
        if (teamName.isEmpty()) { sendMessage("Team name cannot be empty."); return true; }

        // Category
        Set<String> categories = getAvailableCategories();
        sendMessage("Available categories: " + String.join(", ", categories));
        sendMessage("Category (or 'Any'): ");
        String category = readLine();
        if (category == null || category.equals("-")) return false;

        // Difficulty
        sendMessage("Difficulty (Easy / Medium / Hard / Any): ");
        String difficulty = readLine();
        if (difficulty == null || difficulty.equals("-")) return false;

        // Number of questions
        int maxAvailable = filterQuestions(category, difficulty).size();
        sendMessage("Number of questions (1-" + maxAvailable + "): ");
        String numStr = readLine();
        if (numStr == null || numStr.equals("-")) return false;
        int numQuestions;
        try {
            numQuestions = Integer.parseInt(numStr.trim());
            if (numQuestions < 1 || numQuestions > maxAvailable) {
                sendMessage("Invalid number. Must be between 1 and " + maxAvailable + ".");
                return true;
            }
        } catch (NumberFormatException e) {
            sendMessage("Please enter a valid number.");
            return true;
        }

        // Create the team
        Team team = gameServer.getTeamManager().createTeam(
                teamName, this, category, difficulty, numQuestions);
        if (team == null) {
            sendMessage("ERROR: Team name '" + teamName + "' is already taken. Choose another.");
            return true;
        }

        waitingTeamName = teamName;
        sendMessage("Team '" + teamName + "' created! Waiting for an opponent team of equal size...");
        sendMessage("Tell your teammates to join team '" + teamName + "'.");
        sendMessage("(Type '-' to cancel and return to menu)");

        // Poll until a match is found or player quits
        gameLatch = new CountDownLatch(1);

        // Separate thread polls for a match so the main thread can stay alert
        Thread poller = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                List<Team> match = gameServer.getTeamManager().tryMatch(teamName);
                if (match != null) {
                    Team teamA = match.get(0);
                    Team teamB = match.get(1);
                    // Check equal sizes (Req 6)
                    if (teamA.getSize() != teamB.getSize()) {
                        broadcastTeam(teamA, "ERROR: Teams are not equal in size. Game cannot start.");
                        broadcastTeam(teamB, "ERROR: Teams are not equal in size. Game cannot start.");
                        gameServer.getTeamManager().removeTeam(teamA.getName());
                        gameServer.getTeamManager().removeTeam(teamB.getName());
                        return;
                    }
                    // Build combined player list and filtered questions
                    List<ClientHandler> allPlayers = new ArrayList<>();
                    allPlayers.addAll(teamA.getMembers());
                    allPlayers.addAll(teamB.getMembers());

                    List<Question> filtered = filterQuestions(teamA.getCategory(), teamA.getDifficulty());
                    Collections.shuffle(filtered);
                    if (filtered.size() > teamA.getNumQuestions())
                        filtered = filtered.subList(0, teamA.getNumQuestions());

                    broadcastTeam(teamA, "Opponent found: Team '" + teamB.getName() + "'! Game starting...");
                    broadcastTeam(teamB, "Matched against Team '" + teamA.getName() + "'! Game starting...");

                    // Set latches for all non-creator players too
//                    for (ClientHandler c : allPlayers) {
//                        if (c != ClientHandler.this) {
//                            c.gameLatch = new CountDownLatch(1);
//                        }
//                    }

                    gameServer.startTeamGame(allPlayers, filtered);
                    return;
                }
            }
        });
        poller.setDaemon(true);
        poller.start();

        gameLatch.await();
        poller.interrupt();
        waitingTeamName = null;
        return true;
    }

    // ---- Join team (Req 6) --------------------------------------------------

    private boolean joinTeamFlow() throws IOException, InterruptedException {
        sendMessage("");
        sendMessage("-- Join Team --");

        List<String> waiting = gameServer.getTeamManager().getWaitingTeamNames();
        if (waiting.isEmpty()) {
            sendMessage("No teams are currently waiting. Create one or try Quick Match.");
            return true;
        }
        sendMessage("Waiting teams: " + String.join(", ", waiting));
        sendMessage("Team name to join: ");

        String teamName = readLine();
        if (teamName == null || teamName.equals("-")) return false;

        gameLatch = new CountDownLatch(1);

        Team team = gameServer.getTeamManager().joinTeam(teamName, this);
        if (team == null) {
            gameLatch = null;
            sendMessage("Team '" + teamName + "' not found.");
            return true;
        }

        waitingTeamName = teamName;
        sendMessage("Joined team '" + teamName + "' (" + team.getSize() + " member(s)). Waiting for the game to start...");


        gameLatch.await();
        waitingTeamName = null;
        return true;
    }

    // =========================================================================
    // Score history display (Req 10 bonus)
    // =========================================================================

    private void showScoreHistory() {
        List<Integer> history = user.getScoreHistory();
        if (history.isEmpty()) {
            sendMessage("No score history yet.");
        } else {
            sendMessage("-- Your Score History --");
            int total = 0;
            for (int i = 0; i < history.size(); i++) {
                sendMessage("  Game " + (i + 1) + ": " + history.get(i) + " points");
                total += history.get(i);
            }
            sendMessage("  Average: " + (total / history.size()) + " points");
        }
    }

    // =========================================================================
    // Called by GameManager when game ends
    // =========================================================================

    public void gameFinished() {
        CountDownLatch latch = gameLatch;
        if (latch != null) latch.countDown();
    }

    // =========================================================================
    // Auth phase
    // =========================================================================

    private boolean authPhase() throws IOException {
        while (true) {
            sendMessage("\n[1] Login");
            sendMessage("[2] Register");
            sendMessage("Choice: ");
            String choice = readLine();
            if (choice == null || choice.equals("-")) return false;
            if (choice.equals("1")) { if (doLogin())    return true; }
            else if (choice.equals("2")) { if (doRegister()) return true; }
            else sendMessage("Invalid choice. Please enter 1 or 2.");
        }
    }

    private boolean doLogin() throws IOException {
        sendMessage("Username: ");
        String username = readLine();
        if (username == null || username.equals("-")) return false;
        sendMessage("Password: ");
        String password = readLine();
        if (password == null || password.equals("-")) return false;

        int result = authManager.login(username, password);
        switch (result) {
            case AuthManager.OK:
                user = authManager.getUser(username);
                sendMessage("Login successful. Welcome back, " + user.getName() + "!");
                return true;
            case AuthManager.NOT_FOUND:
                sendMessage("ERROR 404 - Username not found. Please register first.");
                return false;
            case AuthManager.UNAUTHORIZED:
                sendMessage("ERROR 401 - Incorrect password. Please try again.");
                return false;
            default:
                sendMessage("Login failed. Please try again.");
                return false;
        }
    }

    private boolean doRegister() throws IOException {
        sendMessage("Full name: ");
        String name = readLine();
        if (name == null || name.equals("-")) return false;
        sendMessage("Username: ");
        String username = readLine();
        if (username == null || username.equals("-")) return false;
        sendMessage("Password: ");
        String password = readLine();
        if (password == null || password.equals("-")) return false;

        int result = authManager.register(name, username, password);
        switch (result) {
            case AuthManager.OK:
                user = authManager.getUser(username);
                sendMessage("Registration successful. Welcome, " + user.getName() + "!");
                return true;
            case AuthManager.CONFLICT:
                sendMessage("ERROR 409 - Username '" + username + "' is already taken. Choose another.");
                return false;
            default:
                sendMessage("Registration failed. Please try again.");
                return false;
        }
    }

    // =========================================================================
    // Game phase — called by GameManager
    // =========================================================================

    public String readAnswerWithTimeout(long timeoutMs) {
        try {
            socket.setSoTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
            String line = in.readLine();
            socket.setSoTimeout(0);
            return (line == null) ? null : line.trim();
        } catch (SocketTimeoutException e) {
            try { socket.setSoTimeout(0); } catch (Exception ignored) {}
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // Disconnect handling
    // =========================================================================

    private void handleDisconnect() {
        String who = (user != null) ? user.getUsername()
                : socket.getRemoteSocketAddress().toString();
        System.out.println("[Server] Player disconnected: " + who);

        // Release latch if waiting in lobby or game
        CountDownLatch latch = gameLatch;
        if (latch != null) latch.countDown();

        // Remove from waiting team
        if (waitingTeamName != null) {
            gameServer.getTeamManager().removeTeam(waitingTeamName);
        }

        if (currentGame != null) currentGame.handlePlayerDisconnect(this);
        gameServer.removeClient(this);
        try { socket.close(); } catch (Exception ignored) {}
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    public void sendMessage(String msg)          { out.println(msg); }
    public User getUser()                        { return user; }
    public void setCurrentGame(GameManager game) { this.currentGame = game; }

    private String readLine() throws IOException {
        String line = in.readLine();
        return (line == null) ? null : line.trim();
    }

    /** Filter the full question bank by category and difficulty. "Any" matches all. */
    private List<Question> filterQuestions(String category, String difficulty) {
        List<Question> result = new ArrayList<>();
        for (Question q : gameServer.getAllQuestions()) {
            boolean catMatch  = category.equalsIgnoreCase("Any")
                    || q.getCategory().equalsIgnoreCase(category);
            boolean diffMatch = difficulty.equalsIgnoreCase("Any")
                    || q.getDifficulty().equalsIgnoreCase(difficulty);
            if (catMatch && diffMatch) result.add(q);
        }
        return result;
    }

    private Set<String> getAvailableCategories() {
        Set<String> cats = new LinkedHashSet<>();
        for (Question q : gameServer.getAllQuestions()) cats.add(q.getCategory());
        return cats;
    }

    private void broadcastTeam(Team team, String msg) {
        for (ClientHandler c : team.getMembers()) c.sendMessage(msg);
    }
}