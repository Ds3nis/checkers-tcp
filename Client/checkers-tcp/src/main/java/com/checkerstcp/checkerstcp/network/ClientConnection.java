package com.checkerstcp.checkerstcp.network;

import com.checkerstcp.checkerstcp.Position;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Main client connection manager for the checkers game.
 * Handles TCP connection, message sending/receiving, heartbeat monitoring,
 * and automatic reconnection on connection loss.
 *
 * <p>Features:
 * <ul>
 *   <li>Asynchronous message handling with separate listener and sender threads</li>
 *   <li>Automatic heartbeat monitoring with connection loss detection</li>
 *   <li>Seamless reconnection with game state preservation</li>
 *   <li>Protocol violation detection and spam protection</li>
 *   <li>Thread-safe message queuing</li>
 * </ul>
 */
public class ClientConnection {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread listenerThread;
    private Thread senderThread;
    private boolean connected = false;

    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
    private Consumer<Message> messageHandler;
    private Consumer<Boolean> connectionStateHandler;

    private String serverHost;
    private int serverPort;
    private String clientId;

    // Spam protection: track duplicate message counts
    private final ConcurrentHashMap<Integer, AtomicInteger> messageCounters = new ConcurrentHashMap<>();
    private static final int MAX_DUPLICATE_MESSAGES = 10;  // Maximum identical messages allowed
    private static final long COUNTER_RESET_INTERVAL = 5000; // Reset counters every 5 seconds
    private long lastCounterReset = System.currentTimeMillis();

    private HeartbeatManager heartbeatManager;
    private ReconnectManager reconnectManager;
    private boolean inReconnectMode = false;

    // Protocol violation tracking
    private final AtomicInteger invalidMessageCount = new AtomicInteger(0);
    private static final int MAX_INVALID_MESSAGES = 1;
    private long lastInvalidMessageTime = 0;
    private static final long INVALID_MESSAGE_RESET_TIME = 60000;

    private ReconnectManager.ClientGameState currentGameState;

    // Track active listener thread to prevent old threads from triggering disconnects
    private volatile long activeListenerThreadId = -1;


    /**
     * Constructs a new ClientConnection with heartbeat and reconnect managers.
     * Initializes connection state handlers and sets up automatic reconnection.
     */
    public ClientConnection() {
        heartbeatManager = new HeartbeatManager(this);
        reconnectManager = new ReconnectManager(this);

        currentGameState = ReconnectManager.ClientGameState.NOT_LOGGED_IN;
        // Set up heartbeat callbacks
        heartbeatManager.setOnConnectionLost(() -> {
            System.err.println("Heartbeat detected connection loss");
            handleConnectionLoss();
        });

        heartbeatManager.setOnConnectionRestored(() -> {
            System.out.println("Heartbeat detected connection restored");
        });
    }

    /**
     * Establishes connection to the game server.
     * Creates socket, starts listener/sender threads, and initiates login sequence.
     *
     * @param host Server hostname or IP address
     * @param port Server port number
     * @param clientId Unique client identifier for this session
     * @return true if connection successful, false otherwise
     */
    public synchronized boolean connect(String host, int port, String clientId) {
        if (connected) {
            System.err.println("Already connected");
            return false;
        }

        try {
            this.serverHost = host;
            this.serverPort = port;
            this.clientId = clientId;

            socket = new Socket();

            SocketAddress endpoint = new InetSocketAddress(host, port);
            socket.connect(endpoint, 5000);

            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            connected = true;
            invalidMessageCount.set(0);
            lastInvalidMessageTime = 0;

            startListenerThread();
            startSenderThread();


            sendLogin(clientId);

            heartbeatManager.start();

            reconnectManager.saveConnectionData(host, port, clientId, null, ReconnectManager.ClientGameState.IN_LOBBY);

            notifyConnectionState(true);
            System.out.println("Connected to " + host + ":" + port);

            return true;

        } catch (SocketTimeoutException e) {
            System.err.println("Connection timeout: Server not responding");
            disconnect();
            return false;

        } catch (IOException e) {
            System.err.println("Connection failed: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    /**
     * Gracefully disconnects from the server.
     * Stops heartbeat monitoring, cleans up resources, and notifies handlers.
     */
    public synchronized void disconnect() {
        if (!connected) {
            return;
        }

        heartbeatManager.stop();
        reconnectManager.stopReconnect();
        connected = false;
        cleanupConnection();

        notifyConnectionState(false);
        System.out.println("Disconnected from server");
    }

    /**
     * Attempts to reconnect to the server at TCP level.
     * Called by ReconnectManager during automatic reconnection attempts.
     * Establishes new socket connection but waits for protocol-level verification.
     *
     * @param host Server hostname
     * @param port Server port
     * @param clientId Client identifier
     * @return true if TCP connection established, false otherwise
     */
    public synchronized boolean reconnect(String host, int port, String clientId) {
        System.out.println("Attempting TCP reconnect to " + host + ":" + port);

        cleanupConnection();
        inReconnectMode = true;

        try {
            socket = new Socket();
            SocketAddress endpoint = new InetSocketAddress(host, port);
            socket.connect(endpoint, 5000);

            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            connected = true;
            invalidMessageCount.set(0);
            lastInvalidMessageTime = 0;

            startListenerThread();
            startSenderThread();

            System.out.println("TCP connection established (protocol verification pending)");

            return true;

        } catch (SocketTimeoutException e) {
            System.err.println("Connection timeout: Server not responding");
            forceCloseSocket();
            connected = false;
            inReconnectMode = false;
            return false;
        } catch (IOException e) {
            System.err.println("Reconnect failed: " + e.getMessage());
            connected = false;
            inReconnectMode = false;
            return false;
        }
    }

    /**
     * Forcefully closes the socket connection.
     * Used during reconnection cleanup to ensure old socket is properly closed.
     */
    public synchronized void forceCloseSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                System.out.println("Socket forcefully closed (reconnect cleanup)");
            }
        } catch (IOException e) {
            System.err.println("Error closing socket: " + e.getMessage());
        }
    }

    /**
     * Handles connection loss detected by heartbeat monitor.
     * Stops heartbeat, saves connection state, and initiates reconnection process.
     */
    private void handleConnectionLoss() {
        if (!connected) {
            return;
        }

        System.err.println("Connection lost!");

        inReconnectMode = true;
        connected = false;

        heartbeatManager.stop();

        cleanupConnection();

        notifyConnectionState(false);

        reconnectManager.saveConnectionData(
                serverHost,
                serverPort,
                clientId,
                getCurrentRoom(),
                currentGameState
        );

        reconnectManager.startReconnect();
    }

    /**
     * Cleans up connection resources.
     * Interrupts threads, closes streams and socket.
     */
    private void cleanupConnection() {
        try {
            if (listenerThread != null) {
                listenerThread.interrupt();
            }
            if (senderThread != null) {
                senderThread.interrupt();
            }
            if (out != null) {
                out.close();
            }
            if (in != null) {
                in.close();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }

    /**
     * Transitions client to lobby state.
     * Updates game state and clears room association.
     */
    public void transitionToLobby() {
        currentGameState = ReconnectManager.ClientGameState.IN_LOBBY;
        reconnectManager.setGameState(currentGameState);
        reconnectManager.setCurrentRoom(null);
        System.out.println("State: IN_LOBBY");
    }

    /**
     * Transitions client to room waiting state.
     * Called when player joins a room but game hasn't started yet.
     *
     * @param roomName Name of the room joined
     */
    public void transitionToRoomWaiting(String roomName) {
        currentGameState = ReconnectManager.ClientGameState.IN_ROOM_WAITING;
        reconnectManager.setGameState(currentGameState);
        reconnectManager.setCurrentRoom(roomName);
        System.out.println("State: IN_ROOM_WAITING (room: " + roomName + ")");
    }

    /**
     * Transitions client to active game state.
     * Called when game starts with both players present.
     *
     * @param roomName Name of the game room
     */
    public void transitionToGame(String roomName) {
        currentGameState = ReconnectManager.ClientGameState.IN_GAME;
        reconnectManager.setGameState(currentGameState);
        reconnectManager.setCurrentRoom(roomName);
        System.out.println("State: IN_GAME (room: " + roomName + ")");
    }


    /**
     * Starts the message listener thread.
     * Reads incoming messages from server, buffers partial messages,
     * and processes complete messages when newline delimiter is received.
     *
     * <p>Thread safety: Only the active listener thread (tracked by ID)
     * will trigger disconnection to prevent old threads from interfering
     * with reconnection process.
     */
    private void startListenerThread() {
        // Stop old listener thread if still running
        if (listenerThread != null && listenerThread.isAlive()) {
            System.out.println("Interrupting old listener thread...");
            listenerThread.interrupt();
            try {
                listenerThread.join(1000); // Wait max 1 second
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        listenerThread = new Thread(() -> {
            long myThreadId = Thread.currentThread().getId();
            activeListenerThreadId = myThreadId;

            StringBuilder messageBuffer = new StringBuilder();
            char[] buffer = new char[1024];

            System.out.println("Listener thread started (ID: " + myThreadId + ")");

            try {
                while (connected) {
                    // Store this thread's ID as the active listener
                    if (activeListenerThreadId != myThreadId) {
                        System.out.println("Listener thread " + myThreadId +
                                " detected newer thread, exiting gracefully");
                        return; // Exit without triggering disconnect
                    }

                    int charsRead = in.read(buffer, 0, buffer.length);

                    if (charsRead == -1) {
                        System.out.println("Server closed connection");
                        break;
                    }
                    // Process received characters
                    for (int i = 0; i < charsRead; i++) {
                        char currentChar = buffer[i];

                        // Prevent buffer overflow
                        if (messageBuffer.length() >= 8192) {
                            System.err.println("Message buffer overflow, resetting");
                            messageBuffer.setLength(0);
                            continue;
                        }

                        messageBuffer.append(currentChar);

                        // Complete message received (newline delimiter)
                        if (currentChar == '\n') {
                            String messageStr = messageBuffer.toString().trim();
                            messageBuffer.setLength(0);

                            if (!messageStr.isEmpty()) {
                                processMessage(messageStr);
                            }
                        }
                    }
                }
            } catch (SocketException e) {
                // Only active thread should trigger disconnect
                if (activeListenerThreadId == myThreadId) {
                    if (connected && !inReconnectMode) {
                        System.err.println("Socket closed unexpectedly (thread " + myThreadId + ")");
                    } else {
                        System.out.println("Socket closed (reconnect in progress, thread " + myThreadId + ")");
                    }
                } else {
                    System.out.println("Old socket closed (expected, thread " + myThreadId + ")");
                    return; // Don't trigger disconnect
                }
            } catch (IOException e) {
                if (activeListenerThreadId == myThreadId) {
                    if (connected && !inReconnectMode) {
                        System.err.println("Connection lost: " + e.getMessage() + " (thread " + myThreadId + ")");
                    }
                } else {
                    System.out.println("Old connection IOException (expected, thread " + myThreadId + ")");
                    return; // Don't trigger disconnect
                }
            } finally {
                // Only active thread triggers disconnect
                if (activeListenerThreadId == myThreadId) {
                    if (connected && !inReconnectMode) {
                        System.out.println("Listener thread " + myThreadId + " ending, disconnecting...");
                        disconnect();
                    } else {
                        System.out.println("Listener thread " + myThreadId + " ended (reconnect mode or already disconnected)");
                    }
                } else {
                    System.out.println("Old listener thread " + myThreadId + " exiting gracefully");
                }
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.setName("MessageListener-" + System.currentTimeMillis());
        listenerThread.start();
    }

    /**
     * Processes a received message string.
     * Parses the message, checks for spam, and forwards to message handler.
     *
     * @param messageStr Raw message string to process
     */
    private void processMessage(String messageStr) {
        try {
            Message message = Message.parse(messageStr);

            if (message == null) {
                System.err.println("Failed to parse message: " + messageStr);
                handleInvalidMessage("Invalid message format");
                return;
            }

            if (!checkMessageSpam(message)) {
                System.err.println("Too many duplicate messages, ignoring: " + message.getOpCode());
                return;
            }

            if (messageHandler != null) {
                messageHandler.accept(message);
            }

        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handles invalid message received from server.
     * Tracks violation count and forcefully disconnects after threshold exceeded.
     *
     * @param reason Reason why message was invalid
     */
    private void handleInvalidMessage(String reason) {
        long now = System.currentTimeMillis();

        if (lastInvalidMessageTime > 0 &&
                (now - lastInvalidMessageTime) > INVALID_MESSAGE_RESET_TIME) {
            invalidMessageCount.set(0);
        }

        lastInvalidMessageTime = now;
        int count = invalidMessageCount.incrementAndGet();

        System.err.println("Invalid message count: " + count + "/" + MAX_INVALID_MESSAGES);

        if (count >= MAX_INVALID_MESSAGES) {
            forceDisconnect("Protocol violation: " + reason);
        }
    }

    /**
     * Forces immediate disconnection due to protocol violation.
     * Sends error message to server and disconnects.
     *
     * @param reason Reason for forced disconnect
     */
    private synchronized void forceDisconnect(String reason) {
        System.err.println("FORCE DISCONNECT: " + reason);

        try {
            if (connected && out != null) {
                sendError("Client detected protocol violation: " + reason);
            }
        } catch (Exception e) {
        }

        disconnect();
        notifyConnectionState(false);
    }

    /**
     * Checks for message spam (duplicate messages in short time period).
     * Implements sliding window spam detection with automatic counter reset.
     *
     * @param message Message to check
     * @return true if message is not spam, false if spam detected
     */
    private boolean checkMessageSpam(Message message) {
        long now = System.currentTimeMillis();

        // Reset counters periodically
        if (now - lastCounterReset > COUNTER_RESET_INTERVAL) {
            messageCounters.clear();
            lastCounterReset = now;
        }

        int messageKey = message.getOpCode().hashCode();

        AtomicInteger counter = messageCounters.computeIfAbsent(
                messageKey,
                k -> new AtomicInteger(0)
        );

        int count = counter.incrementAndGet();


        if (count > MAX_DUPLICATE_MESSAGES) {
            if (count == MAX_DUPLICATE_MESSAGES + 1) {
                System.err.println("Spam detected: " + message.getOpCode() +
                        " received " + count + " times in " +
                        COUNTER_RESET_INTERVAL + "ms");
            }
            return false;
        }

        return true;
    }


    /**
     * Starts the message sender thread.
     * Processes messages from queue and sends them to server.
     * Uses blocking queue for thread-safe message queuing.
     */
    private void startSenderThread() {
        if (senderThread != null && senderThread.isAlive()) {
            System.out.println("Interrupting old sender thread...");
            senderThread.interrupt();
            try {
                senderThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        senderThread = new Thread(() -> {
            long myThreadId = Thread.currentThread().getId();
            System.out.println("Sender thread started (ID: " + myThreadId + ")");

            try {
                while (connected) {
                    String message = messageQueue.take();
                    if (out != null && !out.checkError()) {
                        out.println(message);
                        System.out.println("SENT: " + message);
                    }
                }
            } catch (InterruptedException e) {
                System.out.println("Sender thread " + myThreadId + " interrupted");
            }
        });
        senderThread.setDaemon(true);
        senderThread.setName("MessageSender-" + System.currentTimeMillis());
        senderThread.start();
    }

    /**
     * Queues a message for sending to the server.
     * Messages are sent asynchronously by the sender thread.
     *
     * @param message Message to send
     */
    private void sendMessage(Message message) {
        if (!connected) {
            System.err.println("Not connected to server");
            return;
        }

        try {
            String encoded = message.encode();
            messageQueue.put(encoded);
        } catch (InterruptedException e) {
            System.err.println("Failed to queue message: " + e.getMessage());
        }
    }

    // ========== Protocol message methods ==========

    /**
     * Sends login request with client identifier.
     *
     * @param clientId Unique client identifier
     */
    public void sendLogin(String clientId) {
        Message msg = new Message(OpCode.LOGIN, clientId);
        sendMessage(msg);
    }

    /**
     * Sends request to create a new game room.
     *
     * @param playerName Name of the player creating the room
     * @param roomName Name for the new room
     */
    public void sendCreateRoom(String playerName, String roomName) {
        String data = playerName + "," + roomName;
        Message msg = new Message(OpCode.CREATE_ROOM, data);
        sendMessage(msg);
    }

    /**
     * Sends request to join an existing room.
     *
     * @param playerName Name of the player joining
     * @param roomName Name of the room to join
     */
    public void sendJoinRoom(String playerName, String roomName) {
        String data = playerName + "," + roomName;
        Message msg = new Message(OpCode.JOIN_ROOM, data);
        sendMessage(msg);
    }

    /**
     * Sends a single move in the checkers game.
     *
     * @param roomName Current game room
     * @param playerName Player making the move
     * @param fromRow Source row coordinate
     * @param fromCol Source column coordinate
     * @param toRow Destination row coordinate
     * @param toCol Destination column coordinate
     */
    public void sendMove(String roomName, String playerName, int fromRow, int fromCol, int toRow, int toCol) {
        String data = String.format("%s,%s,%d,%d,%d,%d",
                roomName, playerName, fromRow, fromCol, toRow, toCol);
        Message msg = new Message(OpCode.MOVE, data);
        sendMessage(msg);
    }

    /**
     * Sends request to leave current room.
     *
     * @param roomName Room to leave
     * @param playerName Player leaving
     */
    public void sendLeaveRoom(String roomName, String playerName) {
        String data = roomName + "," + playerName;
        Message msg = new Message(OpCode.LEAVE_ROOM, data);
        sendMessage(msg);
    }

    /**
     * Sends PING to server for connection monitoring.
     */
    public void sendPing() {
        Message msg = new Message(OpCode.PING, "");
        sendMessage(msg);
    }

    /**
     * Sends PONG response to server's PING.
     */
    public void sendPong(){
        Message msg = new Message(OpCode.PONG, "");
        sendMessage(msg);
    }

    /**
     * Requests list of available rooms from server.
     */
    public void sendListRooms() {
        Message msg = new Message(OpCode.LIST_ROOMS, "");
        sendMessage(msg);
    }

    /**
     * Sends error message to server.
     *
     * @param error Error description
     */
    public void sendError(String error){
        Message msg = new Message(OpCode.ERROR, error);
        sendMessage(msg);
    }

    /**
     * Sends multi-jump move sequence.
     * Used when player captures multiple pieces in one turn.
     *
     * @param roomName Current game room
     * @param playerName Player making the moves
     * @param path List of positions in the jump sequence
     */
    public void sendMultiMove(String roomName, String playerName, List<Position> path) {
        StringBuilder data = new StringBuilder();
        data.append(roomName).append(",");
        data.append(playerName).append(",");
        data.append(path.size());

        for (Position pos : path) {
            data.append(",").append(pos.getRow()).append(",").append(pos.getCol());
        }

        Message msg = new Message(OpCode.MULTI_MOVE, data.toString());
        sendMessage(msg);
    }

    /**
     * Sends reconnection request to server.
     * Used to restore session after connection loss.
     *
     * @param roomName Room to reconnect to (null for lobby)
     * @param playerName Player identifier
     */
    public void sendReconnectRequest(String roomName, String playerName) {
        String data;

        if (roomName == null || roomName.isEmpty()) {
            data = playerName;
            System.out.println("Sending RECONNECT_REQUEST (lobby): " + playerName);
        } else {
            data = roomName + "," + playerName;
            System.out.println("Sending RECONNECT_REQUEST (room): " + data);
        }

        Message msg = new Message(OpCode.RECONNECT_REQUEST, data);
        sendMessage(msg);
    }


    // ========== Public API and getters ==========

    /**
     * Notifies connection state handler (public wrapper for external access).
     *
     * @param state Connection state (true = connected, false = disconnected)
     */
    public void notifyConnectionStatePublic(boolean state) {
        notifyConnectionState(state);
    }

    /**
     * Checks if client is in reconnection mode.
     *
     * @return true if reconnecting
     */
    public boolean isInReconnectMode() {
        return inReconnectMode;
    }

    /**
     * Gets the heartbeat manager instance.
     *
     * @return HeartbeatManager
     */
    public HeartbeatManager getHeartbeatManager() {
        return heartbeatManager;
    }

    /**
     * Gets the current room name.
     *
     * @return Room name or null if not in a room
     */
    private String getCurrentRoom() {
        return reconnectManager.getCurrentRoom();
    }

    /**
     * Gets the reconnect manager instance.
     *
     * @return ReconnectManager
     */
    public ReconnectManager getReconnectManager() {
        return reconnectManager;
    }

    /**
     * Gets the current game state.
     *
     * @return Current ClientGameState
     */
    public ReconnectManager.ClientGameState getCurrentGameState() {
        return currentGameState;
    }


    /**
     * Checks if connection is active.
     *
     * @return true if connected and socket is open
     */
    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    /**
     * Gets the client identifier.
     *
     * @return Client ID string
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * Sets the message handler callback.
     * Called when a complete message is received and parsed.
     *
     * @param handler Consumer that processes incoming messages
     */
    public void setMessageHandler(Consumer<Message> handler) {
        this.messageHandler = handler;
    }

    /**
     * Sets the connection state handler callback.
     * Called when connection state changes (connected/disconnected).
     *
     * @param handler Consumer that processes connection state changes
     */
    public void setConnectionStateHandler(Consumer<Boolean> handler) {
        this.connectionStateHandler = handler;
    }

    /**
     * Sets the reconnection mode flag.
     *
     * @param mode true to enable reconnect mode
     */
    public void setInReconnectMode(boolean mode) {
        this.inReconnectMode = mode;
    }

    /**
     * Notifies connection state handler of state change.
     *
     * @param state Connection state (true = connected, false = disconnected)
     */
    private void notifyConnectionState(boolean state) {
        if (connectionStateHandler != null) {
            connectionStateHandler.accept(state);
        }
    }

    /**
     * Gets server connection information as string.
     *
     * @return "host:port" if connected, "Not connected" otherwise
     */
    public String getServerInfo() {
        if (connected) {
            return serverHost + ":" + serverPort;
        }
        return "Not connected";
    }

    /**
     * Gets message statistics for debugging.
     *
     * @return Formatted string with message counts per operation
     */
    public String getMessageStats() {
        StringBuilder stats = new StringBuilder("Message statistics:\n");
        messageCounters.forEach((key, counter) -> {
            stats.append(String.format("  Key %d: %d messages\n", key, counter.get()));
        });
        return stats.toString();
    }
}