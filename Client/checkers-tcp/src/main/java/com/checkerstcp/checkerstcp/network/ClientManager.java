package com.checkerstcp.checkerstcp.network;


import com.checkerstcp.checkerstcp.GameRoom;
import com.checkerstcp.checkerstcp.Player;
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

    private ClientManager() {
        connection = new ClientConnection();
        setupConnectionHandlers();
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
                    // Автоматично запитуємо список кімнат (якщо сервер підтримує)
                    requestRoomsList();
                });
                break;

            case LOGIN_FAIL:
                Platform.runLater(() -> {
                    statusMessage.set("Login failed: " + message.getData());
                    disconnect();
                });
                break;

            case ROOM_JOINED:
                parseRoomJoined(message.getData());
                break;

            case ROOM_FAIL:
                Platform.runLater(() -> {
                    statusMessage.set("Room operation failed: " + message.getData());
                });
                break;

            case ERROR:
                Platform.runLater(() -> {
                    statusMessage.set("Error: " + message.getData());
                });
                break;

            case PONG:
                System.out.println("Pong received - connection alive");
                break;

            case ROOM_CREATED:
                handleRoomCreated(message);
                break;

            case ROOMS_LIST:
                handleRoomsList(message);
                break;
        }
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
                // Парсинг списку кімнат з JSON
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


    /**
     * Парсинг інформації про приєднання до кімнати
     */
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

    /**
     * Запит списку кімнат (поки що емуляція, потрібна підтримка на сервері)
     * TODO: Додати на сервер операцію LIST_ROOMS
     */

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

    // ========== API методи ==========

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
}