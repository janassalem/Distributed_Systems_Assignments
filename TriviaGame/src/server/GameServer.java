package server;

import java.io.File;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class GameServer {

    private static final int PORT = 5000;

    private static final String BASE_DIR       = resolveBaseDir();
    private static final String USERS_FILE     = BASE_DIR + "Data/users.txt";
    private static final String SCORES_FILE    = BASE_DIR + "Data/scores.txt";
    private static final String QUESTIONS_FILE = BASE_DIR + "Data/questions.txt";
    private static final String CONFIG_FILE    = BASE_DIR + "Data/config.txt";

    private static String resolveBaseDir() {
        try {
            String path = GameServer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            File loc = new File(path);
            String dir = loc.isDirectory() ? loc.getAbsolutePath() : loc.getParent();
            return dir.endsWith("/") ? dir : dir + "/";
        } catch (Exception e) { return ""; }
    }

    // ---- Shared state -------------------------------------------------------

    private final List<ClientHandler> quickLobby = new CopyOnWriteArrayList<>();
    private final AuthManager  authManager;
    private final ScoreHistory scoreHistory;
    private final GameConfig   config;
    private final TeamManager  teamManager;
    // All questions preloaded — used by quick match and for team filtering
    private final List<Question> allQuestions;

    public GameServer() {
        System.out.println("[Server] Data directory: " + BASE_DIR);
        config       = new GameConfig(CONFIG_FILE);
        authManager  = new AuthManager(USERS_FILE);
        scoreHistory = new ScoreHistory(SCORES_FILE);
        scoreHistory.loadInto(authManager.getUserMap());
        teamManager  = new TeamManager();
        allQuestions = QuestionLoader.loadQuestions(QUESTIONS_FILE);
        System.out.println("[Server] Loaded " + allQuestions.size() + " questions.");
    }

    // ---- Single player ------------------------------------------------------

    public void startSinglePlayer(ClientHandler client) {
        System.out.println("[Server] Single player game: " + client.getUser().getUsername());
        new Thread(() -> {
            GameManager game = new GameManager(
                    Collections.singletonList(client), scoreHistory, allQuestions, config);
            client.setCurrentGame(game);
            game.startGame();
            client.setCurrentGame(null);
        }).start();
    }

    // ---- Quick match lobby --------------------------------------------------

    public synchronized void joinQuickMatch(ClientHandler client) {
        quickLobby.add(client);
        System.out.println("[Server] Quick lobby: " + client.getUser().getUsername()
                + " | size=" + quickLobby.size());
        broadcastQuickLobby("Players in lobby: " + quickLobby.size()
                + "/" + config.getMinPlayers());

        if (quickLobby.size() >= config.getMinPlayers()) {
            int count = Math.min(quickLobby.size(), config.getMaxPlayers());
            List<ClientHandler> gamePlayers = new ArrayList<>(quickLobby.subList(0, count));
            quickLobby.subList(0, count).clear();
            System.out.println("[Server] Starting quick match with " + count + " players.");
            new Thread(() -> {
                GameManager game = new GameManager(
                        gamePlayers, scoreHistory, allQuestions, config);
                for (ClientHandler c : gamePlayers) c.setCurrentGame(game);
                game.startGame();
                for (ClientHandler c : gamePlayers) c.setCurrentGame(null);
            }).start();
        }
    }

    // ---- Teams --------------------------------------------------------------

    public TeamManager  getTeamManager()  { return teamManager; }
    public List<Question> getAllQuestions(){ return allQuestions; }
    public GameConfig   getConfig()       { return config; }
    public ScoreHistory getScoreHistory() { return scoreHistory; }

    public synchronized void startTeamGame(List<ClientHandler> allPlayers,
                                           List<Question> questions) {
        System.out.println("[Server] Starting team game with " + allPlayers.size() + " players.");
        new Thread(() -> {
            GameManager game = new GameManager(allPlayers, scoreHistory, questions, config);
            for (ClientHandler c : allPlayers) c.setCurrentGame(game);
            game.startGame();
            for (ClientHandler c : allPlayers) c.setCurrentGame(null);
        }).start();
    }

    // ---- Disconnect ---------------------------------------------------------

    public synchronized void removeClient(ClientHandler client) {
        quickLobby.remove(client);
    }

    // ---- Main ---------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        GameServer server = new GameServer();
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("[Server] Trivia Server started on port " + PORT);
        System.out.println("[Server] Min players: " + server.config.getMinPlayers()
                + "  Max: " + server.config.getMaxPlayers());

        while (true) {
            Socket socket = serverSocket.accept();
            System.out.println("[Server] New connection from " + socket.getRemoteSocketAddress());
            try {
                ClientHandler client = new ClientHandler(socket, server.authManager, server);
                new Thread(client).start();
            } catch (Exception e) {
                System.out.println("[Server] Failed to create handler: " + e.getMessage());
            }
        }
    }

    private void broadcastQuickLobby(String msg) {
        for (ClientHandler c : quickLobby) c.sendMessage(msg);
    }
}