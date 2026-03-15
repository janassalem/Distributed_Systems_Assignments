package server;

import java.util.*;

/**
 * Represents a team waiting to be matched against another team.
 */
public class Team {

    private final String name;
    private final ClientHandler creator;
    private final List<ClientHandler> members = new ArrayList<>();

    // Game settings chosen by the creator
    private String category;
    private String difficulty;
    private int    numQuestions;

    public Team(String name, ClientHandler creator, String category, String difficulty, int numQuestions) {
        this.name         = name;
        this.creator      = creator;
        this.category     = category;
        this.difficulty   = difficulty;
        this.numQuestions = numQuestions;
        members.add(creator);
    }

    public boolean addMember(ClientHandler client) {
        if (members.contains(client)) return false;
        members.add(client);
        return true;
    }

    public String              getName()        { return name; }
    public List<ClientHandler> getMembers()     { return Collections.unmodifiableList(members); }
    public int                 getSize()        { return members.size(); }
    public String              getCategory()    { return category; }
    public String              getDifficulty()  { return difficulty; }
    public int                 getNumQuestions(){ return numQuestions; }
    public ClientHandler       getCreator()     { return creator; }
}