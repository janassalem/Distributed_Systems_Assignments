package server;

import java.util.*;
import java.util.concurrent.*;

/**
 * Runs one complete game for a list of players.
 * Can be constructed with:
 *   - a questions file path  (quick match / single player — all questions used)
 *   - a pre-filtered question list (team game — creator chose category/difficulty/count)
 */
public class GameManager {

    private final int QUESTION_TIME_SECONDS;
    private final int POINTS_PER_CORRECT;

    private static final int[] TIME_MILESTONES = {15, 10, 5, 3, 2, 1};

    private final List<ClientHandler> players;
    private final ScoreHistory        scoreHistory;
    private final List<Question>      questions;

    // Tracks per-player per-question result: true=correct, false=wrong/no answer
    // Map<player, List<Boolean>> indexed by question order
    private final Map<ClientHandler, List<Boolean>> playerResults = new LinkedHashMap<>();

    private final Set<ClientHandler> activePlayers = ConcurrentHashMap.newKeySet();

    // Whether a question window is currently open (for "no active question" error)
    private volatile boolean questionActive = false;

    // ---- Constructors -------------------------------------------------------

    /** Quick match / single player: load all questions from file. */
    public GameManager(List<ClientHandler> players, ScoreHistory scoreHistory,
                       String questionsFile, GameConfig config) {
        this(players, scoreHistory, QuestionLoader.loadQuestions(questionsFile), config);
    }

    /** Team game: caller provides pre-filtered question list. */
    public GameManager(List<ClientHandler> players, ScoreHistory scoreHistory,
                       List<Question> questions, GameConfig config) {
        this.players      = players;
        this.scoreHistory = scoreHistory;
        this.questions    = questions;
        QUESTION_TIME_SECONDS = config.getQuestionTimeSeconds();
        POINTS_PER_CORRECT    = config.getPointsPerCorrect();
        activePlayers.addAll(players);
        for (ClientHandler p : players) playerResults.put(p, new ArrayList<>());
    }

    // ---- Entry point --------------------------------------------------------

    public void startGame() {
        broadcast("=============================");
        broadcast("       GAME IS STARTING!     ");
        broadcast("=============================");
        broadcast("Players: " + playerNames());
        broadcast("Total questions: " + questions.size());
        broadcast("");

        for (int i = 0; i < questions.size(); i++) {
            if (activePlayers.isEmpty()) {
                System.out.println("[Game] All players disconnected. Ending game.");
                return;
            }
            runQuestion(i + 1, questions.get(i));
        }

        endGame();
    }

    // ---- Single question round ----------------------------------------------

    private void runQuestion(int number, Question q) {
        broadcast("-----------------------------");
        broadcast("Question " + number + " of " + questions.size()
                + "  [" + q.getCategory() + " | " + q.getDifficulty() + "]");
        broadcast(q.getText());
        String[] choices = q.getChoices();
        String[] labels  = {"A", "B", "C", "D"};
        for (int i = 0; i < choices.length && i < labels.length; i++) {
            broadcast("  " + labels[i] + ") " + choices[i]);
        }
        broadcast("You have " + QUESTION_TIME_SECONDS + " seconds to answer!");

        Set<ClientHandler>        answered    = ConcurrentHashMap.newKeySet();
        Map<ClientHandler, String> submissions = new ConcurrentHashMap<>();

        long deadlineMs = System.currentTimeMillis() + QUESTION_TIME_SECONDS * 1000L;
        questionActive = true;

        List<Thread> readers = new ArrayList<>();
        for (ClientHandler player : activePlayers) {
            Thread t = new Thread(() -> {
                while (System.currentTimeMillis() < deadlineMs) {
                    long remaining = deadlineMs - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    String answer = player.readAnswerWithTimeout(remaining);
                    if (answer == null) break;
                    if (answer.equals("-")) {
                        activePlayers.remove(player);
                        broadcast(player.getUser().getName() + " has left the game.");
                        break;
                    }
                    if (!answered.contains(player)) {
                        answered.add(player);
                        submissions.put(player, answer.toUpperCase());
                        player.sendMessage("Answer received: " + answer.toUpperCase());
                    } else {
                        // Req 11: answer submitted but already answered this round
                        player.sendMessage("[!] You already submitted an answer for this question.");
                    }
                }
            });
            t.setDaemon(true);
            readers.add(t);
            t.start();
        }

        runCountdown(deadlineMs);
        questionActive = false;

        for (Thread t : readers) {
            try { t.join(QUESTION_TIME_SECONDS * 1000L + 500L); }
            catch (InterruptedException ignored) {}
        }

        broadcast("");
        broadcast("Time is up! Evaluating answers...");
        evaluateAnswers(q, submissions);
        broadcastScores();

        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
    }

    // ---- Countdown ----------------------------------------------------------

    private void runCountdown(long deadlineMs) {
        Set<Integer> fired = new HashSet<>();
        while (true) {
            long remaining = deadlineMs - System.currentTimeMillis();
            if (remaining <= 0) break;
            int secondsLeft = (int) Math.ceil(remaining / 1000.0);
            for (int milestone : TIME_MILESTONES) {
                if (secondsLeft <= milestone && !fired.contains(milestone)) {
                    fired.add(milestone);
                    broadcast("  " + milestone + " second" + (milestone == 1 ? "" : "s") + " left...");
                }
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    // ---- Answer evaluation --------------------------------------------------

    private void evaluateAnswers(Question q, Map<ClientHandler, String> submissions) {
        String correct = q.getCorrectAnswer().toUpperCase();
        broadcast("Correct answer: " + correct
                + "  (" + q.getChoices()[correct.charAt(0) - 'A'] + ")");
        broadcast("");

        for (ClientHandler player : players) {
            if (!activePlayers.contains(player)) continue;
            String submitted = submissions.get(player);
            String name      = player.getUser().getName();
            boolean gotIt;
            if (submitted == null) {
                player.sendMessage("  " + name + ": No answer submitted.");
                gotIt = false;
            } else if (submitted.equals(correct)) {
                player.getUser().addScore(POINTS_PER_CORRECT);
                player.sendMessage("  " + name + ": CORRECT! +" + POINTS_PER_CORRECT + " points");
                gotIt = true;
            } else {
                player.sendMessage("  " + name + ": Wrong (you answered " + submitted + ")");
                gotIt = false;
            }
            playerResults.get(player).add(gotIt);
        }
    }

    // ---- Score display ------------------------------------------------------

    private void broadcastScores() {
        broadcast("");
        broadcast("-- Current Scores --");
        for (ClientHandler player : players) {
            if (activePlayers.contains(player)) {
                broadcast("  " + player.getUser().getName() + ": " + player.getUser().getScore());
            }
        }
    }

    // ---- End of game --------------------------------------------------------

    private void endGame() {
        broadcast("=============================");
        broadcast("         GAME OVER!          ");
        broadcast("=============================");
        broadcast("");
        broadcast("Final Leaderboard:");

        List<ClientHandler> sorted = new ArrayList<>(players);
        sorted.sort((a, b) -> b.getUser().getScore() - a.getUser().getScore());
        int rank = 1;
        for (ClientHandler player : sorted) {
            broadcast("  #" + rank++ + "  " + player.getUser().getName()
                    + " — " + player.getUser().getScore() + " points");
        }

        // Per-question breakdown (Req 9)
        broadcast("");
        broadcast("-- Question Breakdown --");
        for (ClientHandler player : players) {
            List<Boolean> results = playerResults.get(player);
            if (results == null || results.isEmpty()) continue;
            StringBuilder sb = new StringBuilder();
            sb.append("  ").append(player.getUser().getName()).append(": ");
            int correct = 0;
            for (int i = 0; i < results.size(); i++) {
                boolean got = results.get(i);
                sb.append("Q").append(i + 1).append(got ? "[+]" : "[-]").append(" ");
                if (got) correct++;
            }
            sb.append("  (").append(correct).append("/").append(results.size()).append(" correct)");
            broadcast(sb.toString());
        }

        broadcast("");
        broadcast("Thanks for playing!");

        for (ClientHandler player : players) player.getUser().finalizeScore();
        scoreHistory.saveAll(collectUsers());
        System.out.println("[Game] Game finished. Scores saved.");
        for (ClientHandler player : players) player.gameFinished();
    }

    // ---- Disconnect handling ------------------------------------------------

    public void handlePlayerDisconnect(ClientHandler client) {
        if (activePlayers.remove(client)) {
            String name = (client.getUser() != null) ? client.getUser().getName() : "A player";
            broadcast(name + " has disconnected.");
            broadcastScores();
        }
    }

    // ---- Helpers ------------------------------------------------------------

    public boolean isQuestionActive() { return questionActive; }

    private void broadcast(String message) {
        for (ClientHandler player : activePlayers) player.sendMessage(message);
    }

    private String playerNames() {
        StringBuilder sb = new StringBuilder();
        for (ClientHandler p : players) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getUser().getName());
        }
        return sb.toString();
    }

    private Collection<User> collectUsers() {
        List<User> users = new ArrayList<>();
        for (ClientHandler p : players) users.add(p.getUser());
        return users;
    }
}