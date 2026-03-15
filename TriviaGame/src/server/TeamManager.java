package server;

import java.util.*;

/**
 * Server-wide singleton that manages teams waiting to be matched.
 * Teams are created at runtime and deleted when the server shuts down.
 */
public class TeamManager {

    // teamName -> Team (only teams still waiting for an opponent)
    private final Map<String, Team> waitingTeams = new LinkedHashMap<>();

    // teamName -> opponent team name (both filled when a match is found)
    private final Map<String, String> matchedPairs = new HashMap<>();

    /** Create a new team. Returns null if the name is already taken. */
    public synchronized Team createTeam(String name, ClientHandler creator,
                                        String category, String difficulty, int numQuestions) {
        if (waitingTeams.containsKey(name)) return null;
        Team team = new Team(name, creator, category, difficulty, numQuestions);
        waitingTeams.put(name, team);
        return team;
    }

    /** Join an existing team by name. Returns the Team, or null if not found / already full. */
    public synchronized Team joinTeam(String name, ClientHandler client) {
        Team team = waitingTeams.get(name);
        if (team == null) return null;
        team.addMember(client);
        return team;
    }

    /** List all waiting team names (for display to players browsing to join). */
    public synchronized List<String> getWaitingTeamNames() {
        return new ArrayList<>(waitingTeams.keySet());
    }

    /**
     * Try to match two waiting teams.
     * Rules: both teams must have equal sizes.
     * Returns a list of [teamA, teamB] if a valid match is found, else null.
     *
     * A team can be matched against any other team that is already waiting.
     * The creator of teamA chose the game settings; those settings are used.
     */
    public synchronized List<Team> tryMatch(String teamName) {
        Team teamA = waitingTeams.get(teamName);
        if (teamA == null) return null;

        for (Map.Entry<String, Team> entry : waitingTeams.entrySet()) {
            Team teamB = entry.getValue();
            if (teamB.getName().equals(teamName)) continue;   // same team
            if (teamB.getSize() == teamA.getSize()) {
                // Found a match — remove both from waiting
                waitingTeams.remove(teamA.getName());
                waitingTeams.remove(teamB.getName());
                List<Team> pair = new ArrayList<>();
                pair.add(teamA);
                pair.add(teamB);
                return pair;
            }
        }
        return null;   // no equally-sized opponent yet
    }

    /** Remove a team (e.g. creator disconnected). */
    public synchronized void removeTeam(String name) {
        waitingTeams.remove(name);
    }

    public synchronized Team getTeam(String name) {
        return waitingTeams.get(name);
    }
}