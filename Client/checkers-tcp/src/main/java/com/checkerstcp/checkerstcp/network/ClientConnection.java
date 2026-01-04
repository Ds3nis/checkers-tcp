package com.checkerstcp.checkerstcp.network;

import java.io.*;
import java.net.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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

    public ClientConnection() {
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

            startListenerThread();
            startSenderThread();

            sendLogin(clientId);

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

        connected = false;

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
            System.err.println("Error during disconnect: " + e.getMessage());
        }

        notifyConnectionState(false);
        System.out.println("Disconnected from server");
    }

    private void startListenerThread() {
        listenerThread = new Thread(() -> {
            StringBuilder messageBuffer = new StringBuilder();
            char[] buffer = new char[1024];

            try {
                while (connected) {
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
                if (connected) {
                    System.err.println("Socket closed unexpectedly");
                }
            } catch (IOException e) {
                if (connected) {
                    System.err.println("Connection lost: " + e.getMessage());
                }
            } finally {
                disconnect();
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.setName("MessageListener");
        listenerThread.start();
    }


    private void processMessage(String messageStr) {
        try {
            Message message = Message.parse(messageStr);

            if (message == null) {
                System.err.println("Failed to parse message: " + messageStr);
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
        senderThread = new Thread(() -> {
            try {
                while (connected) {
                    String message = messageQueue.take();
                    if (out != null && !out.checkError()) {
                        out.println(message);
                        System.out.println("SENT: " + message);
                    }
                }
            } catch (InterruptedException e) {
                System.out.println("Sender thread interrupted");
            }
        });
        senderThread.setDaemon(true);
        senderThread.setName("MessageSender");
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

    public void sendListRooms() {
        Message msg = new Message(OpCode.LIST_ROOMS, "");
        sendMessage(msg);
    }

    // ========== Геттери та сеттери ==========

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