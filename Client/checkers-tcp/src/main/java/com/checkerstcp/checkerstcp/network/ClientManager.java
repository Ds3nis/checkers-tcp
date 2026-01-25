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
 * Singleton class for managing client connection and application state.
 * Provides high-level API for game operations and centralizes message handling.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Connection lifecycle management (connect, disconnect, reconnect)</li>
 *   <li>Message routing and handling</li>
 *   <li>Game room management (create, join, leave)</li>
 *   <li>Move transmission (single and multi-move)</li>
 *   <li>UI state synchronization via JavaFX properties</li>
 *   <li>Reconnection dialog coordination</li>
 * </ul>
 *
 * <p>Features:
 * <ul>
 *   <li>Thread-safe singleton pattern</li>
 *   <li>Observable connection state and status messages</li>
 *   <li>Extensible message handler registration system</li>
 *   <li>Automatic room list caching and updates</li>
 *   <li>Integrated reconnection UI management</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * ClientManager manager = ClientManager.getInstance();
 * manager.connect("localhost", 12345, "PlayerName");
 * manager.registerMessageHandler(OpCode.GAME_STATE, this::handleGameState);
 * }</pre>
 */
public class ClientManager {
    private static ClientManager instance;

    private ClientConnection connection;
    private BooleanProperty connected = new SimpleBooleanProperty(false);
    private StringProperty statusMessage = new SimpleStringProperty("Disconnected");
    private String currentClientId;
    private String currentRoom;

    // Room cache (updated when receiving list from server)
    private List<GameRoom> availableRooms = new ArrayList<>();

    // Event handlers
    private Map<OpCode, Consumer<Message>> messageHandlers = new HashMap<>();
    private List<Consumer<List<GameRoom>>> roomListUpdateHandlers = new ArrayList<>();

    private ConnectionStatusDialog reconnectDialog;

    private Runnable onConnectionLost;
    private Runnable onReconnectSuccess;
    private Runnable onReconnectFailed;

    /**
     * Private constructor for singleton pattern.
     * Initializes connection, dialog, and sets up handlers.
     */
    private ClientManager() {
        connection = new ClientConnection();
        reconnectDialog = new ConnectionStatusDialog();
        setupConnectionHandlers();
        setupReconnectHandlers();
    }

    /**
     * Gets the singleton instance of ClientManager.
     * Thread-safe lazy initialization.
     *
     * @return ClientManager instance
     */
    public static synchronized ClientManager getInstance() {
        if (instance == null) {
            instance = new ClientManager();
        }
        return instance;
    }

    /**
     * Sets up connection state and message handlers.
     * Configures callbacks for connection events and incoming messages.
     */
    private void setupConnectionHandlers() {
        // Connection state change handler
        connection.setConnectionStateHandler(state -> {
            Platform.runLater(() -> {
                connected.set(state);
                statusMessage.set(state ? "Connected to " + connection.getServerInfo() : "Disconnected");
            });
        });

        // Incoming message handler
        connection.setMessageHandler(message -> {
            System.out.println("Received: " + message);
            handleMessage(message);
        });
    }

    /**
     * Sets up reconnection handlers and dialog callbacks.
     * Configures ReconnectManager callbacks for status updates and outcomes.
     */
    private void setupReconnectHandlers() {
        ReconnectManager reconnectManager = connection.getReconnectManager();

        // Reconnection status update handler
        reconnectManager.setOnStatusUpdate(status -> {
            int attempts = reconnectManager.getReconnectAttempts();
            long duration = reconnectManager.getDisconnectDuration() / 1000;

            if (!reconnectDialog.isShowing()) {
                reconnectDialog.show();
            }

            Platform.runLater(() -> {
                reconnectDialog.updateConnectionStatus(status, attempts, duration);
                // Update status message based on reconnection phase
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

        // Reconnection success handler
        reconnectManager.setOnReconnectSuccess(() -> {
            Platform.runLater(() -> {
                System.out.println("Reconnect successful");
                if (onReconnectSuccess != null) {
                    onReconnectSuccess.run();
                }
            });
        });

        // Reconnection failure handler
        reconnectManager.setOnReconnectFailed(() -> {
            Platform.runLater(() -> {
                System.err.println("Reconnect failed");
                if (onReconnectFailed != null) {
                    onReconnectFailed.run();
                }
            });
        });

        // Dialog cancel button handler
        reconnectDialog.setOnCancel(() -> {
            reconnectManager.stopReconnect();
            if (onReconnectFailed != null) {
                onReconnectFailed.run();
            }
        });

        // Dialog manual reconnect button handler
        reconnectDialog.setOnManualReconnect(() -> {
            System.out.println("Manual reconnect triggered from UI");
            boolean success = reconnectManager.manualReconnect();

            if (!success) {
                System.err.println("Manual reconnect failed");
            }
        });

    }

    /**
     * Connects to the game server.
     * Initiates connection, sends login, and transitions to lobby state.
     *
     * @param host Server hostname or IP address
     * @param port Server port number
     * @param clientId Unique client identifier
     * @return true if connection successful, false otherwise
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
     * Disconnects from the server.
     * Clears room state, closes connection, and hides reconnection dialog.
     */
    public void disconnect() {
        connection.disconnect();
        currentRoom = null;
        availableRooms.clear();
        reconnectDialog.hide();
    }


    /**
     * Handles incoming messages from server.
     * Routes messages to registered handlers and performs global message processing.
     *
     * @param message Message to handle
     */
    private void handleMessage(Message message) {
        // Invoke registered handler for this OpCode
        Consumer<Message> handler = messageHandlers.get(message.getOpCode());
        if (handler != null) {
            Platform.runLater(() -> handler.accept(message));
        }

        // Global message handling
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


    /**
     * Handles connection loss event.
     * Shows reconnection dialog and triggers callback.
     */
    private void handleConnectionLost() {
        System.err.println("Connection lost detected in ClientManager");

        reconnectDialog.show();

        if (onConnectionLost != null) {
            onConnectionLost.run();
        }
    }


    /**
     * Handles opponent disconnection notification.
     * Displays opponent disconnect dialog with waiting message.
     *
     * @param message PLAYER_DISCONNECTED message with room and player info
     */
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

    /**
     * Handles opponent reconnection notification.
     * Displays opponent reconnect success dialog.
     *
     * @param message PLAYER_RECONNECTED message with room and player info
     */
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


    /**
     * Handles game paused notification.
     * Updates status to indicate game pause.
     *
     * @param message GAME_PAUSED message with room name
     */
    private void handleGamePaused(Message message) {
        Platform.runLater(() -> {
            System.out.println("Game paused in room: " + message.getData());
            statusMessage.set("Hra byla pozastavena");
        });
    }

    /**
     * Handles game resumed notification.
     * Updates status to indicate game continuation.
     *
     * @param message GAME_RESUMED message with room name
     */
    private void handleGameResumed(Message message) {
        Platform.runLater(() -> {
            System.out.println("Game resumed in room: " + message.getData());
            statusMessage.set("Hra obnovena!");
        });
    }

    /**
     * Handles successful reconnection confirmation.
     * Hides dialog, updates room state, and confirms to ReconnectManager.
     *
     * @param message RECONNECT_OK message with room name
     */
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

    /**
     * Handles failed reconnection notification.
     * Confirms failure to ReconnectManager and triggers callback.
     *
     * @param message RECONNECT_FAIL message with failure reason
     */
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

    /**
     * Handles room creation confirmation.
     * Requests updated room list from server.
     *
     * @param message ROOM_CREATED message
     */
    private void handleRoomCreated(Message message) {
        Platform.runLater(() -> {
            statusMessage.set("Room created successfully");
            requestRoomsList();
        });
    }

    /**
     * Handles room list update from server.
     * Parses JSON room list and notifies registered handlers.
     *
     * @param message ROOMS_LIST message with JSON array
     */
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

    /**
     * Parses JSON room list from server.
     * Manually parses simple JSON format without external library.
     *
     * Format: [{"id":1,"name":"Room1","players":1},...]
     *
     * @param jsonData JSON string to parse
     * @return List of GameRoom objects
     */
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
     * Parses room joined response data.
     * Format: room_name,players_count
     *
     * @param data Response data to parse
     */
    private void parseRoomJoined(String data) {
        // Format: room_name,players_count
        String[] parts = data.split(",");
        if (parts.length >= 1) {
            currentRoom = parts[0];
            Platform.runLater(() -> {
                statusMessage.set("Joined room: " + currentRoom);
            });
        }
    }


    /**
     * Requests current room list from server.
     */
    public void requestRoomsList() {
        if (connection.isConnected()) {
            connection.sendListRooms();
            System.out.println("Requested rooms list from server");
        }
    }

    /**
     * Updates room list cache and notifies handlers.
     * Called when receiving room list from server.
     *
     * @param rooms New list of available rooms
     */
    public void updateRoomsList(List<GameRoom> rooms) {
        this.availableRooms = new ArrayList<>(rooms);
        notifyRoomListUpdate(rooms);
    }

    /**
     * Notifies all registered room list update handlers.
     *
     * @param rooms Current room list
     */
    private void notifyRoomListUpdate(List<GameRoom> rooms) {
        for (Consumer<List<GameRoom>> handler : roomListUpdateHandlers) {
            handler.accept(new ArrayList<>(rooms));
        }
    }

    // ========== API methods ==========

    /**
     * Creates a new game room.
     *
     * @param roomName Name for the new room
     */
    public void createRoom(String roomName) {
        if (!connection.isConnected()) {
            System.err.println("Not connected");
            return;
        }
        connection.sendCreateRoom(currentClientId, roomName);
    }

    /**
     * Joins an existing game room.
     *
     * @param roomName Name of room to join
     */
    public void joinRoom(String roomName) {
        if (!connection.isConnected()) {
            System.err.println("Not connected");
            return;
        }
        connection.sendJoinRoom(currentClientId, roomName);
    }

    /**
     * Leaves current game room.
     */
    public void leaveRoom() {
        if (!connection.isConnected() || currentRoom == null) {
            System.err.println("Not in a room");
            return;
        }
        connection.sendLeaveRoom(currentRoom, currentClientId);
        currentRoom = null;
    }

    /**
     * Sends a single move to the server.
     *
     * @param fromRow Source row coordinate
     * @param fromCol Source column coordinate
     * @param toRow Destination row coordinate
     * @param toCol Destination column coordinate
     */
    public void sendMove(int fromRow, int fromCol, int toRow, int toCol) {
        if (!connection.isConnected() || currentRoom == null) {
            System.err.println("Not in a game");
            return;
        }
        connection.sendMove(currentRoom, currentClientId, fromRow, fromCol, toRow, toCol);
    }

    /**
     * Sends a multi-jump move sequence to the server.
     *
     * @param path List of positions in the jump sequence (minimum 2)
     */
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

    /**
     * Sends a PING to test connection.
     */
    public void ping() {
        if (connection.isConnected()) {
            connection.sendPing();
        }
    }

    // ========== Handler Registration ==========

    /**
     * Registers a message handler for specific OpCode.
     * Handler will be invoked on JavaFX Application Thread.
     *
     * @param opCode Operation code to handle
     * @param handler Consumer that processes the message
     */
    public void registerMessageHandler(OpCode opCode, Consumer<Message> handler) {
        messageHandlers.put(opCode, handler);
    }

    /**
     * Unregisters a message handler for specific OpCode.
     *
     * @param opCode Operation code to unregister
     */
    public void unregisterMessageHandler(OpCode opCode) {
        messageHandlers.remove(opCode);
    }

    /**
     * Adds a room list update handler.
     * Handler is invoked whenever room list is updated from server.
     *
     * @param handler Consumer that processes room list updates
     */
    public void addRoomListUpdateHandler(Consumer<List<GameRoom>> handler) {
        roomListUpdateHandlers.add(handler);
    }

    /**
     * Adds a room list update handler.
     * Handler is invoked whenever room list is updated from server.
     *
     * @param handler Consumer that processes room list updates
     */
    public void removeRoomListUpdateHandler(Consumer<List<GameRoom>> handler) {
        roomListUpdateHandlers.remove(handler);

    }

    /**
     * Sets callback for connection loss event.
     *
     * @param callback Runnable to execute when connection is lost
     */
    public void setOnConnectionLost(Runnable callback) {
        this.onConnectionLost = callback;
    }

    /**
     * Sets callback for successful reconnection.
     *
     * @param callback Runnable to execute when reconnection succeeds
     */
    public void setOnReconnectSuccess(Runnable callback) {
        this.onReconnectSuccess = callback;
    }


    /**
     * Sets callback for failed reconnection.
     *
     * @param callback Runnable to execute when reconnection fails
     */
    public void setOnReconnectFailed(Runnable callback) {
        this.onReconnectFailed = callback;
    }

    // ========== Getters and Properties ==========

    /**
     * Checks if currently connected to server.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return connection.isConnected();
    }

    /**
     * Gets observable connection state property.
     * Can be bound to UI components for automatic updates.
     *
     * @return BooleanProperty for connection state
     */
    public BooleanProperty connectedProperty() {
        return connected;
    }

    /**
     * Gets observable status message property.
     * Can be bound to UI labels for status display.
     *
     * @return StringProperty for status messages
     */
    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    /**
     * Gets current client identifier.
     *
     * @return Client ID string
     */
    public String getCurrentClientId() {
        return currentClientId;
    }

    /**
     * Gets current room name.
     *
     * @return Room name or null if not in a room
     */
    public String getCurrentRoom() {
        return currentRoom;
    }

    /**
     * Gets copy of available rooms list.
     *
     * @return List of available game rooms
     */
    public List<GameRoom> getAvailableRooms() {
        return new ArrayList<>(availableRooms);
    }

    /**
     * Gets the reconnection status dialog.
     *
     * @return ConnectionStatusDialog instance
     */
    public ConnectionStatusDialog getReconnectDialog() {
        return reconnectDialog;
    }
}