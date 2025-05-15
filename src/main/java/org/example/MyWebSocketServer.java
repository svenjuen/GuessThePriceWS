package org.example;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

public class MyWebSocketServer extends WebSocketServer {
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> connections = new ConcurrentHashMap<>();
    private final List<Item> items = new ArrayList<>();
    private GameState gameState = new GameState();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private ScheduledFuture<?> currentTask;

    public MyWebSocketServer(int port) {
        super(new InetSocketAddress(port));
        initializeItems();
    }

    private void initializeItems() {
        items.add(new Item("1", "Smartphone", "Neuwertiges Smartphone, 128GB Speicher", 1099.99, "phone.jpg"));
        items.add(new Item("2", "Kaffeemaschine", "Premium Kaffeemaschine mit Milchaufschäumer", 449.50, "coffee.jpg"));
        items.add(new Item("3", "Bürostuhl", "Ergonomischer Bürostuhl, schwarz", 389.00, "chair.jpg"));
        items.add(new Item("4", "Kopfhörer", "Noise-Cancelling Kopfhörer", 179.99, "headphones.jpg"));
        items.add(new Item("5", "Fahrrad", "Mountainbike, 21 Gänge", 1349.00, "bike.jpg"));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("New connection: " + conn.getRemoteSocketAddress());
        connections.put(conn, null);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Connection closed: " + conn.getRemoteSocketAddress());
        String playerId = connections.remove(conn);
        if (playerId != null) {
            players.remove(playerId);
            broadcastPlayersUpdate();
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("Received message: " + message);
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.get("type").getAsString();

            switch (type) {
                case "join":
                    handleJoin(conn, json.get("name").getAsString());
                    break;
                case "start":
                    startGame();
                    break;
                case "guess":
                    handleGuess(conn, json.get("guess").getAsDouble());
                    break;
            }
        } catch (Exception e) {
            System.err.println("Error processing message: " + message);
            e.printStackTrace();
        }
    }

    private void handleJoin(WebSocket conn, String name) {
        String playerId = UUID.randomUUID().toString();
        Player player = new Player(playerId, name, conn);
        players.put(playerId, player);
        connections.put(conn, playerId);
        conn.send(String.format("{\"type\":\"welcome\",\"playerId\":\"%s\"}", playerId));
        broadcastPlayersUpdate();
        System.out.println("Player joined: " + name);
    }

    private void broadcastPlayersUpdate() {
        JsonArray playersArray = new JsonArray();
        players.values().forEach(p -> {
            JsonObject playerJson = new JsonObject();
            playerJson.addProperty("id", p.id);
            playerJson.addProperty("name", p.name);
            playerJson.addProperty("score", p.score);
            playersArray.add(playerJson);
        });

        JsonObject message = new JsonObject();
        message.addProperty("type", "players");
        message.add("players", playersArray);
        broadcast(message.toString());
    }

    private void startGame() {
        if (gameState.isRunning) return;

        gameState = new GameState();
        gameState.isRunning = true;
        gameState.players = new ArrayList<>(players.values());

        nextRound();
    }

    private void nextRound() {
        cancelCurrentTask();

        if (items.isEmpty()) {
            endGame();
            return;
        }

        Item item = items.remove(new Random().nextInt(items.size()));
        gameState.currentItem = item;
        gameState.phase = "showing";
        gameState.timeRemaining = 5;
        broadcastGameState();

        currentTask = scheduler.scheduleAtFixedRate(() -> {
            gameState.timeRemaining--;
            broadcastGameState();

            if (gameState.timeRemaining <= 0) {
                cancelCurrentTask();
                startGuessingPhase();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void startGuessingPhase() {
        gameState.phase = "guessing";
        gameState.timeRemaining = 30;
        broadcastGameState();

        currentTask = scheduler.scheduleAtFixedRate(() -> {
            gameState.timeRemaining--;
            broadcastGameState();

            if (gameState.timeRemaining <= 0) {
                cancelCurrentTask();
                showResults();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void showResults() {
        gameState.phase = "results";
        gameState.timeRemaining = 10;
        broadcastGameState();

        currentTask = scheduler.schedule(() -> {
            nextRound();
        }, 10, TimeUnit.SECONDS);
    }

    private void cancelCurrentTask() {
        if (currentTask != null) {
            currentTask.cancel(false);
            currentTask = null;
        }
    }

    private void handleGuess(WebSocket conn, double guess) {
        String playerId = connections.get(conn);
        if (playerId != null) {
            Player player = players.get(playerId);
            if (player != null) {
                player.currentGuess = guess;
                player.lastDiff = Math.abs(guess - gameState.currentItem.price);
                player.score += Math.max(0, 100 - (int)(player.lastDiff / gameState.currentItem.price * 100));
                broadcastPlayersUpdate();
            }
        }
    }

    private void endGame() {
        cancelCurrentTask();
        gameState.isRunning = false;
        gameState.phase = "waiting";
        broadcastGameState();
        initializeItems();
    }

    private void broadcastGameState() {
        JsonObject stateJson = new JsonObject();
        stateJson.addProperty("type", "update");

        JsonObject gameStateJson = new JsonObject();
        gameStateJson.addProperty("phase", gameState.phase);
        gameStateJson.addProperty("timeRemaining", gameState.timeRemaining);

        if (gameState.currentItem != null) {
            JsonObject itemJson = new JsonObject();
            itemJson.addProperty("id", gameState.currentItem.id);
            itemJson.addProperty("desc", gameState.currentItem.description);
            itemJson.addProperty("img", gameState.currentItem.image);
            itemJson.addProperty("price", gameState.currentItem.price);
            gameStateJson.add("currentItem", itemJson);
        }

        JsonArray playersArray = new JsonArray();
        gameState.players.forEach(p -> {
            JsonObject playerJson = new JsonObject();
            playerJson.addProperty("id", p.id);
            playerJson.addProperty("name", p.name);
            playerJson.addProperty("score", p.score);
            playerJson.addProperty("currentGuess", p.currentGuess);
            playerJson.addProperty("lastDiff", p.lastDiff != null ? p.lastDiff : 0);
            playersArray.add(playerJson);
        });
        gameStateJson.add("players", playersArray);

        stateJson.add("gameState", gameStateJson);
        broadcast(stateJson.toString());
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("WebSocket server started successfully! :)");
    }

    @Override
    public void stop() throws InterruptedException {
        cancelCurrentTask();
        scheduler.shutdown();
        super.stop();
    }

    static class Player {
        String id;
        String name;
        WebSocket connection;
        int score = 0;
        double currentGuess = 0;
        Double lastDiff = null;

        Player(String id, String name, WebSocket connection) {
            this.id = id;
            this.name = name;
            this.connection = connection;
        }
    }

    static class Item {
        String id;
        String name;
        String description;
        double price;
        String image;

        Item(String id, String name, String description, double price, String image) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.price = price;
            this.image = image;
        }
    }

    static class GameState {
        boolean isRunning = false;
        String phase = "waiting";
        int timeRemaining = 0;
        Item currentItem = null;
        List<Player> players = new ArrayList<>();
    }
}