package com.checkerstcp.checkerstcp.network;

import com.checkerstcp.checkerstcp.Position;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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

    // Захист від спаму: лічильник однакових повідомлень
    private final ConcurrentHashMap<Integer, AtomicInteger> messageCounters = new ConcurrentHashMap<>();
    private static final int MAX_DUPLICATE_MESSAGES = 10; // Максимум однакових повідомлень
    private static final long COUNTER_RESET_INTERVAL = 5000; // Скидати лічильники кожні 5 секунд
    private long lastCounterReset = System.currentTimeMillis();

    private HeartbeatManager heartbeatManager;
    private ReconnectManager reconnectManager;
    private boolean inReconnectMode = false;

    private final AtomicInteger invalidMessageCount = new AtomicInteger(0);
    private static final int MAX_INVALID_MESSAGES = 1;
    private long lastInvalidMessageTime = 0;
    private static final long INVALID_MESSAGE_RESET_TIME = 60000;

    private ReconnectManager.ClientGameState currentGameState;

    private volatile long activeListenerThreadId = -1;


    public ClientConnection() {
        heartbeatManager = new HeartbeatManager(this);
        reconnectManager = new ReconnectManager(this);

        currentGameState = ReconnectManager.ClientGameState.NOT_LOGGED_IN;

        heartbeatManager.setOnConnectionLost(() -> {
            System.err.println("Heartbeat detected connection loss");
            handleConnectionLoss();
        });

        heartbeatManager.setOnConnectionRestored(() -> {
            System.out.println("Heartbeat detected connection restored");
        });
    }

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

    public synchronized boolean reconnect(String host, int port, String clientId) {
        System.out.println("🔌 Attempting TCP reconnect to " + host + ":" + port);

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

    public void transitionToLobby() {
        currentGameState = ReconnectManager.ClientGameState.IN_LOBBY;
        reconnectManager.setGameState(currentGameState);
        reconnectManager.setCurrentRoom(null);
        System.out.println("State: IN_LOBBY");
    }

    public void transitionToRoomWaiting(String roomName) {
        currentGameState = ReconnectManager.ClientGameState.IN_ROOM_WAITING;
        reconnectManager.setGameState(currentGameState);
        reconnectManager.setCurrentRoom(roomName);
        System.out.println("State: IN_ROOM_WAITING (room: " + roomName + ")");
    }

    public void transitionToGame(String roomName) {
        currentGameState = ReconnectManager.ClientGameState.IN_GAME;
        reconnectManager.setGameState(currentGameState);
        reconnectManager.setCurrentRoom(roomName);
        System.out.println("State: IN_GAME (room: " + roomName + ")");
    }



    private void startListenerThread() {
        // ========== ЗУПИНИТИ СТАРИЙ THREAD ==========
        if (listenerThread != null && listenerThread.isAlive()) {
            System.out.println("Interrupting old listener thread...");
            listenerThread.interrupt();
            try {
                listenerThread.join(1000); // Почекати максимум 1 секунду
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        listenerThread = new Thread(() -> {
            // ========== ЗБЕРЕГТИ ID ЦЬОГО ПОТОКУ ==========
            long myThreadId = Thread.currentThread().getId();
            activeListenerThreadId = myThreadId;

            StringBuilder messageBuffer = new StringBuilder();
            char[] buffer = new char[1024];

            System.out.println("Listener thread started (ID: " + myThreadId + ")");

            try {
                while (connected) {
                    // ========== ПЕРЕВІРИТИ ЧИ ЦЕЙ THREAD ЩЕ АКТИВНИЙ ==========
                    if (activeListenerThreadId != myThreadId) {
                        System.out.println("Listener thread " + myThreadId +
                                " detected newer thread, exiting gracefully");
                        return; // Вийти без disconnect
                    }

                    int charsRead = in.read(buffer, 0, buffer.length);

                    if (charsRead == -1) {
                        System.out.println("Server closed connection");
                        break;
                    }

                    for (int i = 0; i < charsRead; i++) {
                        char currentChar = buffer[i];

                        if (messageBuffer.length() >= 8192) {
                            System.err.println("Message buffer overflow, resetting");
                            messageBuffer.setLength(0);
                            continue;
                        }

                        messageBuffer.append(currentChar);

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
                // ========== ПЕРЕВІРИТИ ЧИ ПОТРІБНО ВИКЛИКАТИ DISCONNECT ==========
                if (activeListenerThreadId == myThreadId) {
                    if (connected && !inReconnectMode) {
                        System.err.println("Socket closed unexpectedly (thread " + myThreadId + ")");
                    } else {
                        System.out.println("Socket closed (reconnect in progress, thread " + myThreadId + ")");
                    }
                } else {
                    System.out.println("Old socket closed (expected, thread " + myThreadId + ")");
                    return; // Не викликати disconnect
                }
            } catch (IOException e) {
                if (activeListenerThreadId == myThreadId) {
                    if (connected && !inReconnectMode) {
                        System.err.println("Connection lost: " + e.getMessage() + " (thread " + myThreadId + ")");
                    }
                } else {
                    System.out.println("Old connection IOException (expected, thread " + myThreadId + ")");
                    return; // Не викликати disconnect
                }
            } finally {
                // ========== ТІЛЬКИ АКТИВНИЙ THREAD ВИКЛИКАЄ DISCONNECT ==========
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

    private boolean checkMessageSpam(Message message) {
        long now = System.currentTimeMillis();
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
                System.err.println("⚠️ Spam detected: " + message.getOpCode() +
                        " received " + count + " times in " +
                        COUNTER_RESET_INTERVAL + "ms");
            }
            return false;
        }

        return true;
    }

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

    // ========== API methods for interaction with server ==========

    public void sendLogin(String clientId) {
        Message msg = new Message(OpCode.LOGIN, clientId);
        sendMessage(msg);
    }

    public void sendCreateRoom(String playerName, String roomName) {
        String data = playerName + "," + roomName;
        Message msg = new Message(OpCode.CREATE_ROOM, data);
        sendMessage(msg);
    }

    public void sendJoinRoom(String playerName, String roomName) {
        String data = playerName + "," + roomName;
        Message msg = new Message(OpCode.JOIN_ROOM, data);
        sendMessage(msg);
    }

    public void sendMove(String roomName, String playerName, int fromRow, int fromCol, int toRow, int toCol) {
        String data = String.format("%s,%s,%d,%d,%d,%d",
                roomName, playerName, fromRow, fromCol, toRow, toCol);
        Message msg = new Message(OpCode.MOVE, data);
        sendMessage(msg);
    }

    public void sendLeaveRoom(String roomName, String playerName) {
        String data = roomName + "," + playerName;
        Message msg = new Message(OpCode.LEAVE_ROOM, data);
        sendMessage(msg);
    }

    public void sendPing() {
        Message msg = new Message(OpCode.PING, "");
        sendMessage(msg);
    }

    public void sendPong(){
        Message msg = new Message(OpCode.PONG, "");
        sendMessage(msg);
    }

    public void sendListRooms() {
        Message msg = new Message(OpCode.LIST_ROOMS, "");
        sendMessage(msg);
    }

    public void sendError(String error){
        Message msg = new Message(OpCode.ERROR, error);
        sendMessage(msg);
    }

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

    public void notifyConnectionStatePublic(boolean state) {
        notifyConnectionState(state);
    }

    public boolean isInReconnectMode() {
        return inReconnectMode;
    }

    public HeartbeatManager getHeartbeatManager() {
        return heartbeatManager;
    }

    private String getCurrentRoom() {
        return reconnectManager.getCurrentRoom();
    }

    public ReconnectManager getReconnectManager() {
        return reconnectManager;
    }

    public ReconnectManager.ClientGameState getCurrentGameState() {
        return currentGameState;
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    public String getClientId() {
        return clientId;
    }

    public void setMessageHandler(Consumer<Message> handler) {
        this.messageHandler = handler;
    }

    public void setConnectionStateHandler(Consumer<Boolean> handler) {
        this.connectionStateHandler = handler;
    }

    public void setInReconnectMode(boolean mode) {
        this.inReconnectMode = mode;
    }

    private void notifyConnectionState(boolean state) {
        if (connectionStateHandler != null) {
            connectionStateHandler.accept(state);
        }
    }

    public String getServerInfo() {
        if (connected) {
            return serverHost + ":" + serverPort;
        }
        return "Not connected";
    }

    public String getMessageStats() {
        StringBuilder stats = new StringBuilder("Message statistics:\n");
        messageCounters.forEach((key, counter) -> {
            stats.append(String.format("  Key %d: %d messages\n", key, counter.get()));
        });
        return stats.toString();
    }
}