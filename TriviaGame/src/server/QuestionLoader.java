package server;

import java.io.*;
import java.util.*;

public class QuestionLoader {

    public static List<Question> loadQuestions(String file) {

        List<Question> questions = new ArrayList<>();

        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;

            while((line = br.readLine()) != null) {

                String[] parts = line.split("\\|");

                String category = parts[0];
                String difficulty = parts[1];
                String text = parts[2];
                String[] choices = parts[3].split(",");
                String answer = parts[4];

                questions.add(new Question(category,difficulty,text,choices,answer));
            }

        } catch(Exception e){
            e.printStackTrace();
        }

        return questions;
    }
}
