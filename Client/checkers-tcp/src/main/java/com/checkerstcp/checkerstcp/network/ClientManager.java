package com.checkerstcp.checkerstcp.network;


import com.checkerstcp.checkerstcp.GameRoom;
import com.checkerstcp.checkerstcp.Player;
import com.checkerstcp.checkerstcp.Position;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Singleton клас для управління підключенням та станом клієнта
 */
public class ClientManager {
    private static ClientManager instance;

    private ClientConnection connection;
    private BooleanProperty connected = new SimpleBooleanProperty(false);
    private StringProperty statusMessage = new SimpleStringProperty("Disconnected");
    private String currentClientId;
    private String currentRoom;

    // Кеш кімнат (оновлюється при отриманні списку)
    private List<GameRoom> availableRooms = new ArrayList<>();

    // Обробники подій
    private Map<OpCode, Consumer<Message>> messageHandlers = new HashMap<>();
    private List<Consumer<List<GameRoom>>> roomListUpdateHandlers = new ArrayList<>();

    private ConnectionStatusDialog reconnectDialog;

    private Runnable onConnectionLost;
    private Runnable onReconnectSuccess;
    private Runnable onReconnectFailed;

    private ClientManager() {
        connection = new ClientConnection();
        reconnectDialog = new ConnectionStatusDialog();
        setupConnectionHandlers();
        setupReconnectHandlers();
    }

    public static synchronized ClientManager getInstance() {
        if (instance == null) {
            instance = new ClientManager();
        }
        return instance;
    }

    /**
     * Налаштування обробників з'єднання
     */
    private void setupConnectionHandlers() {
        // Обробник зміни стану з'єднання
        connection.setConnectionStateHandler(state -> {
            Platform.runLater(() -> {
                connected.set(state);
                statusMessage.set(state ? "Connected to " + connection.getServerInfo() : "Disconnected");
            });
        });

        // Обробник вхідних повідомлень
        connection.setMessageHandler(message -> {
            System.out.println("Received: " + message);
            handleMessage(message);
        });
    }

    private void setupReconnectHandlers() {
        ReconnectManager reconnectManager = connection.getReconnectManager();

        // Оновлення статусу реконекту
        reconnectManager.setOnStatusUpdate(status -> {
            int attempts = reconnectManager.getReconnectAttempts();
            long duration = reconnectManager.getDisconnectDuration() / 1000;

            if (!reconnectDialog.isShowing()) {
                reconnectDialog.show();
            }

            Platform.runLater(() -> {
                reconnectDialog.updateConnectionStatus(status, attempts, duration);

                switch (status) {
                    case SHORT_DISCONNECT:
                        statusMessage.set("Přepojování... (pokus " + attempts + ")");
                        break;
                    case LONG_DISCONNECT:
                        statusMessage.set("Dlouhodobé odpojení (" + duration + " sekund)");
                        break;
                    case RECONNECTED:
                        statusMessage.set("Spojení obnoveno!");
                        break;
                    case CRITICAL_TIMEOUT:
                        statusMessage.set("Připojení ztraceno (časový limit)");
                        if (onReconnectFailed != null) {
                            onReconnectFailed.run();
                        }
                        break;
                }
            });
        });

        reconnectManager.setOnReconnectSuccess(() -> {
            Platform.runLater(() -> {
                System.out.println("Reconnect successful");
                if (onReconnectSuccess != null) {
                    onReconnectSuccess.run();
                }
            });
        });

        reconnectManager.setOnReconnectFailed(() -> {
            Platform.runLater(() -> {
                System.err.println("Reconnect failed");
                if (onReconnectFailed != null) {
                    onReconnectFailed.run();
                }
            });
        });

        reconnectDialog.setOnCancel(() -> {
            reconnectManager.stopReconnect();
            if (onReconnectFailed != null) {
                onReconnectFailed.run();
            }
        });

        reconnectDialog.setOnManualReconnect(() -> {
            System.out.println("Manual reconnect triggered from UI");
            boolean success = reconnectManager.manualReconnect();

            if (!success) {
                System.err.println("Manual reconnect failed");
            }
        });

    }

    /**
     * Підключення до сервера
     */
    public boolean connect(String host, int port, String clientId) {
        if (connection.isConnected()) {
            System.out.println("Already connected");
            return true;
        }

        this.currentClientId = clientId;
        return connection.connect(host, port, clientId);
    }

    /**
     * Відключення від сервера
     */
    public void disconnect() {
        connection.disconnect();
        currentRoom = null;
        availableRooms.clear();
        reconnectDialog.hide();
    }


    /**
     * Обробка вхідних повідомлень
     */
    private void handleMessage(Message message) {
        // Викликаємо зареєстрований обробник для цього OpCode
        Consumer<Message> handler = messageHandlers.get(message.getOpCode());
        if (handler != null) {
            Platform.runLater(() -> handler.accept(message));
        }

        // Глобальна обробка деяких повідомлень
        switch (message.getOpCode()) {
            case LOGIN_OK:
                Platform.runLater(() -> {
                    statusMessage.set("Logged in as " + currentClientId);
                    requestRoomsList();
                });
                connection.transitionToLobby();
                break;

            case LOGIN_FAIL:
                Platform.runLater(() -> {
                    statusMessage.set("Login failed: " + message.getData());
                    disconnect();
                });
                break;

            case ROOM_JOINED:
                parseRoomJoined(message.getData());
                connection.transitionToRoomWaiting(currentRoom);
                break;

            case ROOM_FAIL:
                Platform.runLater(() -> {
                    statusMessage.set("Room operation failed: " + message.getData());
                });
                break;

            case ROOM_LEFT:
                connection.transitionToLobby();
                break;

            case ERROR:
                Platform.runLater(() -> {
                    statusMessage.set("Error: " + message.getData());
                });
                break;

            case PONG:
                connection.getHeartbeatManager().onPongReceived();
                break;

            case PING:
                System.out.println("Ping received from server");
                connection.sendPong();
                break;

            case ROOM_CREATED:
                handleRoomCreated(message);
                break;

            case ROOMS_LIST:
                handleRoomsList(message);
                break;

            case PLAYER_DISCONNECTED:
                handlePlayerDisconnected(message);
                break;

            case PLAYER_RECONNECTED:
                handlePlayerReconnected(message);
                break;

            case GAME_START:
                connection.transitionToGame(currentRoom);
                break;

            case GAME_END:
                connection.transitionToLobby();
                break;

            case GAME_PAUSED:
                handleGamePaused(message);
                break;

            case GAME_RESUMED:
                handleGameResumed(message);
                break;

            case RECONNECT_OK:
                handleReconnectOk(message);
                break;

            case RECONNECT_FAIL:
                handleReconnectFail(message);
                break;
        }
    }

    private void handleConnectionLost() {
        System.err.println("Connection lost detected in ClientManager");

        reconnectDialog.show();

        if (onConnectionLost != null) {
            onConnectionLost.run();
        }
    }

    private void handlePlayerDisconnected(Message message) {
        String[] parts = message.getData().split(",");
        if (parts.length >= 2) {
            String roomName = parts[0];
            String playerName = parts[1];

            Platform.runLater(() -> {
                System.out.println("Player " + playerName + " disconnected from room " + roomName);
                reconnectDialog.showOpponentDisconnected(playerName);
                statusMessage.set("Soupeř se odpojil. Čekáme na jeho návrat...");
            });
        }
    }

    private void handlePlayerReconnected(Message message) {
        String[] parts = message.getData().split(",");
        if (parts.length >= 2) {
            String roomName = parts[0];
            String playerName = parts[1];

            Platform.runLater(() -> {
                System.out.println("Player " + playerName + " reconnected to room " + roomName);
                reconnectDialog.showOpponentReconnected(playerName);
                statusMessage.set("Soupeř se vrátil. Hra pokračuje!");
            });
        }
    }


    private void handleGamePaused(Message message) {
        Platform.runLater(() -> {
            System.out.println("Game paused in room: " + message.getData());
            statusMessage.set("Hra byla pozastavena");
        });
    }

    private void handleGameResumed(Message message) {
        Platform.runLater(() -> {
            System.out.println("Game resumed in room: " + message.getData());
            statusMessage.set("Hra obnovena!");
        });
    }

    private void handleReconnectOk(Message message) {
        Platform.runLater(() -> {
            String roomName = message.getData();
            System.out.println("Successfully reconnected to room: " + roomName);
            currentRoom = roomName;
            statusMessage.set("Připojeno ke hře: " + roomName);
            reconnectDialog.hide();

            connection.getReconnectManager().confirmReconnectSuccess();
        });
    }

    private void handleReconnectFail(Message message) {
        Platform.runLater(() -> {
            System.err.println("Failed to reconnect to game: " + message.getData());
            statusMessage.set("Nepodařilo se obnovit hru");

            connection.getReconnectManager().confirmReconnectFailure();

            if (onReconnectFailed != null) {
                onReconnectFailed.run();
            }
        });
    }

    private void handleRoomCreated(Message message) {
        Platform.runLater(() -> {
            statusMessage.set("Room created successfully");
            requestRoomsList();
        });
    }

    private void handleRoomsList(Message message) {
        Platform.runLater(() -> {
            try {
                // Формат: [{"id":1,"name":"Room1","players":1,"status":"waiting"},...]
                List<GameRoom> rooms = parseRoomsList(message.getData());
                availableRooms = rooms;
                notifyRoomListUpdate(rooms);
            } catch (Exception e) {
                System.err.println("Error parsing rooms list: " + e.getMessage());
            }
        });
    }

    private List<GameRoom> parseRoomsList(String jsonData) {
        List<GameRoom> rooms = new ArrayList<>();

        try {
            // Format: [{"id":1,"name":"Room1","players":1},...]

            if (jsonData == null || jsonData.trim().isEmpty() || jsonData.equals("[]")) {
                return rooms;
            }

            String content = jsonData.trim();
            if (content.startsWith("[")) {
                content = content.substring(1);
            }
            if (content.endsWith("]")) {
                content = content.substring(0, content.length() - 1);
            }

            String[] roomObjects = content.split("\\},\\{");

            for (String roomStr : roomObjects) {
                roomStr = roomStr.replace("{", "").replace("}", "").trim();
                if (roomStr.isEmpty()) continue;

                int id = 0;
                String name = "";
                int playersCount = 0;

                String[] fields = roomStr.split(",");
                for (String field : fields) {
                    String[] keyValue = field.split(":");
                    if (keyValue.length != 2) continue;

                    String key = keyValue[0].replace("\"", "").trim();
                    String value = keyValue[1].replace("\"", "").trim();

                    switch (key) {
                        case "id":
                            id = Integer.parseInt(value);
                            break;
                        case "name":
                            name = value;
                            break;
                        case "players":
                            playersCount = Integer.parseInt(value);
                            break;
                    }
                }

                if (!name.isEmpty()) {
                    List<Player> players = new ArrayList<>();
                    for (int i = 0; i < playersCount; i++) {
                        players.add(new Player("Player" + (i + 1)));
                    }
                    rooms.add(new GameRoom(id, name, players));
                }
            }

        } catch (Exception e) {
            System.err.println("Error parsing rooms list: " + e.getMessage());
            e.printStackTrace();
        }

        return rooms;
    }


    private void parseRoomJoined(String data) {
        // Формат: room_name,players_count
        String[] parts = data.split(",");
        if (parts.length >= 1) {
            currentRoom = parts[0];
            Platform.runLater(() -> {
                statusMessage.set("Joined room: " + currentRoom);
            });
        }
    }


    public void requestRoomsList() {
        if (connection.isConnected()) {
            connection.sendListRooms();
            System.out.println("Requested rooms list from server");
        }
    }

    /**
     * Оновлення списку кімнат (викликається при отриманні LIST_ROOMS від сервера)
     */
    public void updateRoomsList(List<GameRoom> rooms) {
        this.availableRooms = new ArrayList<>(rooms);
        notifyRoomListUpdate(rooms);
    }

    private void notifyRoomListUpdate(List<GameRoom> rooms) {
        for (Consumer<List<GameRoom>> handler : roomListUpdateHandlers) {
            handler.accept(new ArrayList<>(rooms));
        }
    }

    // ========== API methods ==========

    public void createRoom(String roomName) {
        if (!connection.isConnected()) {
            System.err.println("Not connected");
            return;
        }
        connection.sendCreateRoom(currentClientId, roomName);
    }

    public void joinRoom(String roomName) {
        if (!connection.isConnected()) {
            System.err.println("Not connected");
            return;
        }
        connection.sendJoinRoom(currentClientId, roomName);
    }

    public void leaveRoom() {
        if (!connection.isConnected() || currentRoom == null) {
            System.err.println("Not in a room");
            return;
        }
        connection.sendLeaveRoom(currentRoom, currentClientId);
        currentRoom = null;
    }

    public void sendMove(int fromRow, int fromCol, int toRow, int toCol) {
        if (!connection.isConnected() || currentRoom == null) {
            System.err.println("Not in a game");
            return;
        }
        connection.sendMove(currentRoom, currentClientId, fromRow, fromCol, toRow, toCol);
    }

    public void sendMultiMove(List<Position> path){
        if (!connection.isConnected() || currentRoom == null) {
            System.err.println("Not in a game");
            return;
        }

        if (path == null || path.size() < 2) {
            System.err.println("Invalid path: need at least 2 positions");
            return;
        }

        connection.sendMultiMove(currentRoom, currentClientId, path);
    }

    public void ping() {
        if (connection.isConnected()) {
            connection.sendPing();
        }
    }

    // ========== Реєстрація обробників ==========

    public void registerMessageHandler(OpCode opCode, Consumer<Message> handler) {
        messageHandlers.put(opCode, handler);
    }

    public void unregisterMessageHandler(OpCode opCode) {
        messageHandlers.remove(opCode);
    }

    public void addRoomListUpdateHandler(Consumer<List<GameRoom>> handler) {
        roomListUpdateHandlers.add(handler);
    }

    public void removeRoomListUpdateHandler(Consumer<List<GameRoom>> handler) {
        roomListUpdateHandlers.remove(handler);

    }

    public void setOnConnectionLost(Runnable callback) {
        this.onConnectionLost = callback;
    }

    public void setOnReconnectSuccess(Runnable callback) {
        this.onReconnectSuccess = callback;
    }

    public void setOnReconnectFailed(Runnable callback) {
        this.onReconnectFailed = callback;
    }

    // ========== Геттери та Properties ==========

    public boolean isConnected() {
        return connection.isConnected();
    }

    public BooleanProperty connectedProperty() {
        return connected;
    }

    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    public String getCurrentClientId() {
        return currentClientId;
    }

    public String getCurrentRoom() {
        return currentRoom;
    }

    public List<GameRoom> getAvailableRooms() {
        return new ArrayList<>(availableRooms);
    }

    public ConnectionStatusDialog getReconnectDialog() {
        return reconnectDialog;
    }
}