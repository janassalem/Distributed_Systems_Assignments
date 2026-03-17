package server;

import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

public class GameServer {

    private static final int PORT = 5001;

    private static final String BASE_DIR = "";
    private static final String USERS_FILE     = BASE_DIR + "Data/user.txt";
    private static final String SCORES_FILE    = BASE_DIR + "Data/scores.txt";
    private static final String QUESTIONS_FILE = BASE_DIR + "Data/questions.txt";
    private static final String CONFIG_FILE    = BASE_DIR + "Data/Config.txt";

    // ---- Thread pool for games ----
    private final ExecutorService gamePool = Executors.newCachedThreadPool();    //track many threads instead making them manually

    // ---- Shared state ----
    private final List<ClientHandler> quickLobby =
            Collections.synchronizedList(new ArrayList<>());    //better than CopyOnWrite as we need many writes

    private final AuthManager authManager;
    private final ScoreHistory scoreHistory;
    private final GameConfig config;
    private final TeamManager teamManager;
    private final List<Question> allQuestions;

    public GameServer() {

        System.out.println("[Server] Starting Trivia Server...");

        config = new GameConfig(CONFIG_FILE);

        authManager = new AuthManager(USERS_FILE);

        scoreHistory = new ScoreHistory(SCORES_FILE);
        scoreHistory.loadInto(authManager.getUserMap());

        teamManager = new TeamManager();

        allQuestions = QuestionLoader.loadQuestions(QUESTIONS_FILE);

        System.out.println("[Server] Loaded " + allQuestions.size() + " questions.");
    }

    // -------------------------------------------------
    // Single Player
    // -------------------------------------------------

    public void startSinglePlayer(ClientHandler client) {

        System.out.println("[Server] Single player game: "
                + client.getUser().getUsername());

        gamePool.submit(() ->
                runGame(Collections.singletonList(client), allQuestions));
    }

    // -------------------------------------------------
    // Quick Match
    // -------------------------------------------------

    public synchronized void joinQuickMatch(ClientHandler client) {

        quickLobby.add(client);

        System.out.println("[Server] Quick lobby: "
                + client.getUser().getUsername()
                + " | size=" + quickLobby.size());

        broadcastQuickLobby("Players in lobby: "
                + quickLobby.size()
                + "/" + config.getMinPlayers());

        if (quickLobby.size() >= config.getMinPlayers()) {

            int count = Math.min(quickLobby.size(), config.getMaxPlayers());

            List<ClientHandler> gamePlayers =
                    new ArrayList<>(quickLobby.subList(0, count));

            quickLobby.subList(0, count).clear();

            System.out.println("[Server] Starting quick match with "
                    + count + " players.");

            gamePool.submit(() -> runGame(gamePlayers, allQuestions));

            if (!quickLobby.isEmpty()) {  // when player remain in the lobby get notified by this msg ex:( max 4 and 5 joined so game start with 4 and 1 remain in lobby)
                broadcastQuickLobby("Waiting for more players...");
            }
        }
    }

    // -------------------------------------------------
    // Team Games
    // -------------------------------------------------

    public synchronized void startTeamGame(List<ClientHandler> players,
                                           List<Question> questions) {

        System.out.println("[Server] Starting team game with "
                + players.size() + " players.");

        gamePool.submit(() -> runGame(players, questions));
    }

    // -------------------------------------------------
    // Run Game (Shared logic)
    // -------------------------------------------------

    private void runGame(List<ClientHandler> players,
                         List<Question> questions) {

        GameManager game =
                new GameManager(players, scoreHistory, questions, config);

        for (ClientHandler c : players) {
            c.setCurrentGame(game);
        }

        game.startGame();

        for (ClientHandler c : players) {
            c.setCurrentGame(null);
        }
    }

    // -------------------------------------------------
    // Disconnect
    // -------------------------------------------------

    public synchronized void removeClient(ClientHandler client) {

        quickLobby.remove(client);

        System.out.println("[Server] Client removed from lobby.");
    }

    // -------------------------------------------------
    // Getters
    // -------------------------------------------------

    public TeamManager getTeamManager() {
        return teamManager;
    }

    public List<Question> getAllQuestions() {
        return allQuestions;
    }

    public GameConfig getConfig() {
        return config;
    }

    public ScoreHistory getScoreHistory() {
        return scoreHistory;
    }

    // -------------------------------------------------
    // Lobby Broadcast
    // -------------------------------------------------

    private void broadcastQuickLobby(String message) {

        synchronized (quickLobby) {

            for (ClientHandler c : quickLobby) {
                c.sendMessage(message);
            }
        }
    }

    // -------------------------------------------------
    // Server Start
    // -------------------------------------------------

    public static void main(String[] args) {

        GameServer server = new GameServer();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {

            System.out.println("[Server] Trivia Server started on port "
                    + PORT);

            System.out.println("[Server] Min players: "
                    + server.config.getMinPlayers()
                    + "  Max: "
                    + server.config.getMaxPlayers());

            while (true) {

                Socket socket = serverSocket.accept();

                System.out.println("[Server] New connection from "
                        + socket.getRemoteSocketAddress());

                try {

                    ClientHandler client =
                            new ClientHandler(socket,
                                    server.authManager,
                                    server);

                    new Thread(client).start();

                } catch (Exception e) {

                    System.out.println("[Server] Failed to create handler: "
                            + e.getMessage());
                }
            }

        } catch (Exception e) {

            System.out.println("[Server] Server crashed: "
                    + e.getMessage());
        }
    }
}