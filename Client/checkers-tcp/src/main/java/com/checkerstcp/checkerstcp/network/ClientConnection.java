package com.checkerstcp.checkerstcp.network;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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

            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            connected = true;

            startListenerThread();
            startSenderThread();

            sendLogin(clientId);

            notifyConnectionState(true);
            System.out.println("Connected to " + host + ":" + port);

            return true;
        } catch (IOException e) {
            System.err.println("Connection failed: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    /**
     * Відключення від сервера
     */
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
            try {
                String line;
                while (connected && (line = in.readLine()) != null) {
                    try {
                        Message message = Message.parse(line);
                        if (message != null && messageHandler != null) {
                            messageHandler.accept(message);
                        }
                    } catch (Exception e) {
                        System.err.println("Error parsing message: " + e.getMessage());
                    }
                }
            } catch (SocketException e) {
                System.out.println("Socket closed");
            } catch (IOException e) {
                System.err.println("Connection lost: " + e.getMessage());
            } finally {
                disconnect();
            }
        });
        listenerThread.setDaemon(true);
        listenerThread.setName("MessageListener");
        listenerThread.start();
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
}
