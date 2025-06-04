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
    // Spielerdaten: ID -> Player-Objekt
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    // Aktive Verbindungen: WebSocket -> Spieler-ID
    private final Map<WebSocket, String> connections = new ConcurrentHashMap<>();
    // Liste aller verfügbaren Rate-Artikel
    private final List<Item> items = new ArrayList<>();
    // Aktueller Spielzustand
    private GameState gameState = new GameState();
    // Timer-Service für unsere Countdowns
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    // Referenz auf den aktuellen Timer-Task
    private ScheduledFuture<?> currentTask;

    public MyWebSocketServer(int port) {
        super(new InetSocketAddress(port));
        initializeItems();
    }

    // Initialisiert die Artikel zum Raten
    private void initializeItems() {
        items.add(new Item("1", "Smartphone", "Neuwertiges Smartphone, 128GB Speicher", 1099.99, "phone.jpg"));
        items.add(new Item("2", "Kaffeemaschine", "Premium Kaffeemaschine mit Milchaufschäumer", 449.50, "coffee.jpg"));
        items.add(new Item("3", "Bürostuhl", "Ergonomischer Bürostuhl, schwarz", 389.00, "chair.jpg"));
        items.add(new Item("4", "Kopfhörer", "Noise-Cancelling Kopfhörer", 179.99, "headphones.jpg"));
        items.add(new Item("5", "Fahrrad", "Mountainbike", 1349.00, "bike.jpg"));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("Neue Verbindung: " + conn.getRemoteSocketAddress());
        connections.put(conn, null); // Spieler-ID wird später bei "join" gesetzt
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Verbindung getrennt: " + conn.getRemoteSocketAddress());
        String playerId = connections.remove(conn);
        if (playerId != null) {
            players.remove(playerId);
            broadcastPlayersUpdate(); // Alle über Spieleraustritt informieren
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("Nachricht erhalten: " + message);
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
            System.err.println("Fehler bei Nachrichtenverarbeitung: " + message);
            e.printStackTrace();
        }
    }

    // Behandelt neue Spieler
    private void handleJoin(WebSocket conn, String name) {
        String playerId = UUID.randomUUID().toString();
        Player player = new Player(playerId, name, conn);
        players.put(playerId, player);
        connections.put(conn, playerId);
        conn.send(String.format("{\"type\":\"welcome\",\"playerId\":\"%s\"}", playerId));
        broadcastPlayersUpdate();
    }

    // Aktualisiert die Spielerliste für alle Clients
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

    // Startet ein neues Spiel
    private void startGame() {
        if (gameState.isRunning) return;

        gameState = new GameState();
        gameState.isRunning = true;
        gameState.players = new ArrayList<>(players.values());
        players.values().forEach(player -> {
                    player.score = 0; // Punkte werden bei Spielstart auf 0 gesetzt
                }
        );

        nextRound();
    }

    // Beginnt eine neue Runde
    private void nextRound() {
        cancelCurrentTask(); // Sicherstellen, dass kein Timer läuft

        if (items.isEmpty()) {
            endGame();
            return;
        }

        // Zufälligen Artikel auswählen
        Item item = items.remove(new Random().nextInt(items.size()));
        gameState.currentItem = item;
        gameState.phase = "guessing"; // Direkt zur Rate-Phase wechseln
        gameState.timeRemaining = 20; // 20 Sekunden zum Raten
        broadcastGameState();

        // Countdown für Rate-Phase
        currentTask = scheduler.scheduleAtFixedRate(() -> {
            gameState.timeRemaining--;
            broadcastGameState();

            if (gameState.timeRemaining <= 0) {
                cancelCurrentTask();
                showResults();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    // Zeigt die Ergebnisse an
    private void showResults() {
        // Punkte berechnen für alle Spieler
        players.values().forEach(player -> {
            if (player.currentGuess > 0) {
                double diff = Math.abs(player.currentGuess - gameState.currentItem.price);
                player.lastDiff = diff;
                int points = (int)(1000 * Math.exp(-diff / gameState.currentItem.price));
                player.score += points; // Punkte werden zu den bestehenden addiert
            }
        });

        gameState.phase = "results";
        gameState.timeRemaining = 10; // Set initial timeRemaining for results phase
        broadcastGameState(); // Broadcast initial state

        // Countdown for results phase
        currentTask = scheduler.scheduleAtFixedRate(() -> {
            gameState.timeRemaining--;
            broadcastGameState(); // Broadcast updated timeRemaining

            if (gameState.timeRemaining <= 0) {
                cancelCurrentTask();
                players.values().forEach(player -> {
                    player.currentGuess = 0;
                    player.lastDiff = null; // Reset for next round
                });
                nextRound(); // Start next round
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    // Beendet den aktuellen Timer-Task
    private void cancelCurrentTask() {
        if (currentTask != null) {
            currentTask.cancel(false);
            currentTask = null;
        }
    }

    // Verarbeitet eine Preis-Schätzung
    private void handleGuess(WebSocket conn, double guess) {
        if (!gameState.phase.equals("guessing")) {
            return; // Nur während der Guessing-Phase erlauben
        }

        String playerId = connections.get(conn);
        if (playerId != null) {
            Player player = players.get(playerId);
            if (player != null) {
                // Spieler kann seine Schätzung beliebig oft ändern
                player.currentGuess = guess;
                broadcastPlayersUpdate();
            }
        }
    }

    // Beendet das Spiel
    private void endGame() {
        cancelCurrentTask();
        gameState.isRunning = false;
        gameState.phase = "waiting";
        broadcastGameState();
        initializeItems(); // Artikel zurücksetzen für neues Spiel
    }

    // Sendet den aktuellen Spielzustand an alle
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
        System.err.println("WebSocket-Fehler:");
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("Server gestartet auf Port " + getPort());
    }

    @Override
    public void stop() throws InterruptedException {
        cancelCurrentTask();
        scheduler.shutdown(); // Threadpool sauber beenden
        super.stop(); // WebSocket-Server stoppen
    }

    // --- Datenklassen ---

    // Speichert Spielerdaten
    static class Player {
        String id;
        String name;
        WebSocket connection;
        int score = 0;
        double currentGuess = 0;
        Double lastDiff = null; // wie weit der letzte guess daneben lag

        Player(String id, String name, WebSocket connection) {
            this.id = id;
            this.name = name;
            this.connection = connection;
        }
    }

    // Beschreibt einen Artikel zum Raten
    static class Item {
        String id;
        String name;
        String description;
        double price; // Der zu erratende Preis
        String image; // Bild-URL

        Item(String id, String name, String description, double price, String image) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.price = price;
            this.image = image;
        }
    }

    // Hält den aktuellen Spielzustand
    static class GameState {
        boolean isRunning = false;
        String phase = "waiting"; // waiting|guessing|results
        int timeRemaining = 0;
        Item currentItem = null; // Aktuell angezeigter Artikel
        List<Player> players = new ArrayList<>(); // Aktive Spieler
    }
}