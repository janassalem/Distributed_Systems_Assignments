package server;

public class Question {

    private final String category;
    private final String difficulty;
    private final String text;
    private final String[] choices;
    private final String correctAnswer;

    public Question(String category, String difficulty,
                    String text, String[] choices, String correctAnswer) {
        this.category      = category;
        this.difficulty    = difficulty;
        this.text          = text;
        this.choices       = choices;
        this.correctAnswer = correctAnswer;
    }

    public String   getCategory()     { return category; }
    public String   getDifficulty()   { return difficulty; }
    public String   getText()         { return text; }
    public String[] getChoices()      { return choices; }
    public String   getCorrectAnswer(){ return correctAnswer; }
}