package org.example;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {

    public static void main(String[] args) {
        int port = 8080;
        MyWebSocketServer server = new MyWebSocketServer(port);
        server.start();
        System.out.println("Server started on port " + port);
    }
}
