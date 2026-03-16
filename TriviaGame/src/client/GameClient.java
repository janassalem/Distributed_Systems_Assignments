package client;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class GameClient {

    public static void main(String[] args) {

        try {
            Socket socket = new Socket("localhost", 5000);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));

            PrintWriter out = new PrintWriter(
                    socket.getOutputStream(), true);

            Scanner scanner = new Scanner(System.in);

            System.out.println("Connected to server");

            // Server drives everything — just print what it sends.
            Thread readerThread = new Thread(() -> {
                try {
                    String serverMsg;
                    while ((serverMsg = in.readLine()) != null) {
                        System.out.println(serverMsg);
                    }
                } catch (Exception e) {
                    // ignore
                }
                System.out.println("Server disconnected. Exiting...");
                System.exit(0);
            });
            readerThread.setDaemon(true);
            readerThread.start();

            // Send whatever the user types
            while (scanner.hasNextLine()) {
                out.println(scanner.nextLine());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}