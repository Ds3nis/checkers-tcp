package com.checkerstcp.checkerstcp.network;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class ReconnectManager {
    private final ClientConnection connection;
    private ScheduledExecutorService reconnectExecutor;

    private static final int INITIAL_RECONNECT_DELAY_MS = 1000;      // 1 секунда
    private static final int MAX_RECONNECT_DELAY_MS = 5000;          // 5 секунд
    private static final int SHORT_DISCONNECT_THRESHOLD_SEC = 40;    // Короткочасне (0-40 сек)
    private static final int LONG_DISCONNECT_THRESHOLD_SEC = 80;     // Критичне (80+ сек)
    private static final int MAX_AUTO_RECONNECT_ATTEMPTS = 8;        // ~40 секунд спроб
    private static final long RECONNECT_TIMEOUT_MS = 10000;

    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private long disconnectStartTime = 0;

    // Дані для реконекту
    private String serverHost;
    private int serverPort;
    private String clientId;
    private String currentRoom;
    private ClientGameState gameState;

    // Callbacks
    private Runnable onReconnectSuccess;
    private Runnable onReconnectFailed;
    private Consumer<ReconnectStatus> onStatusUpdate;
    private CompletableFuture<Boolean> pendingReconnectVerification = null;

    public ReconnectManager(ClientConnection connection) {
        this.connection = connection;
    }

    public void saveConnectionData(String host, int port, String clientId,
                                   String room, ClientGameState state) {
        this.serverHost = host;
        this.serverPort = port;
        this.clientId = clientId;
        this.currentRoom = room;
        this.gameState = state;

        System.out.println("Saved reconnect data: " + clientId +
                " in state " + state +
                (room != null ? " (room: " + room + ")" : " (lobby)"));
    }

    public synchronized void startReconnect() {
        if (isReconnecting.get()) {
            System.out.println("Reconnect already in progress");
            return;
        }

        if (serverHost == null || clientId == null) {
            System.err.println("Cannot reconnect: no connection data");
            return;
        }

        isReconnecting.set(true);
        reconnectAttempts.set(0);
        disconnectStartTime = System.currentTimeMillis();

        reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ReconnectThread");
            t.setDaemon(true);
            return t;
        });

        System.out.println("Starting automatic reconnection (max " +
                MAX_AUTO_RECONNECT_ATTEMPTS + " attempts)...");
        System.out.println("📍 Will reconnect to state: " + gameState);

        notifyStatus(ReconnectStatus.SHORT_DISCONNECT, 0, 0);
        scheduleNextAttempt(INITIAL_RECONNECT_DELAY_MS);
    }

    public synchronized void stopReconnect() {
        if (!isReconnecting.get()) {
            return;
        }

        isReconnecting.set(false);

        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
            try {
                reconnectExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reconnectExecutor = null;
        }

        System.out.println("Reconnection stopped");
    }

    private void scheduleNextAttempt(int delayMs) {
        if (!isReconnecting.get() || reconnectExecutor == null) {
            return;
        }

        reconnectExecutor.schedule(
                this::attemptReconnect,
                delayMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void attemptReconnect() {
        if (!isReconnecting.get()) {
            return;
        }

        int attempt = reconnectAttempts.incrementAndGet();
        long disconnectDuration = System.currentTimeMillis() - disconnectStartTime;
        long disconnectSeconds = disconnectDuration / 1000;

        System.out.println("Reconnect attempt #" + attempt + "/" +
                MAX_AUTO_RECONNECT_ATTEMPTS + " (disconnected for " +
                disconnectSeconds + "s)");

        // ========== ПЕРЕХІД ДО СТАНУ "LONG_DISCONNECT" ==========
        if (disconnectSeconds >= SHORT_DISCONNECT_THRESHOLD_SEC) {
            System.out.println("Transition to LONG_DISCONNECT (40+ seconds)");
            notifyStatus(ReconnectStatus.LONG_DISCONNECT, attempt, disconnectSeconds);

            stopReconnect();
            return;
        }

        // ========== АВТОМАТИЧНІ СПРОБИ (0-40 СЕКУНД) ==========
        if (attempt > MAX_AUTO_RECONNECT_ATTEMPTS) {
            System.out.println("Max auto-reconnect attempts reached");
            notifyStatus(ReconnectStatus.LONG_DISCONNECT, attempt, disconnectSeconds);
            stopReconnect();
            return;
        }

        notifyStatus(ReconnectStatus.SHORT_DISCONNECT, attempt, disconnectSeconds);

        ReconnectResult result = attemptReconnectWithTimeout();

        if (result.success) {
            handleReconnectSuccess();
        } else {
            // Продовжити спроби (якщо ще не досягли 40 секунд)
            if (attempt < MAX_AUTO_RECONNECT_ATTEMPTS) {
                int nextDelay = calculateNextDelay(attempt);
                System.out.println("Next attempt in " + (nextDelay / 1000) + "s");
                scheduleNextAttempt(nextDelay);
            } else {
                notifyStatus(ReconnectStatus.LONG_DISCONNECT, attempt, disconnectSeconds);
                stopReconnect();
            }
        }
    }

    private ReconnectResult attemptReconnectWithTimeout() {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<ReconnectResult> future = executor.submit(() -> {
            try {

                boolean tcpConnected = connection.reconnect(serverHost, serverPort, clientId);

                if (!tcpConnected) {
                    System.err.println("TCP reconnect failed");
                    return new ReconnectResult(false, "TCP connection failed");
                }

                System.out.println("TCP connected, verifying protocol...");

                if (gameState == null) {
                    gameState = ClientGameState.IN_LOBBY;
                }

                pendingReconnectVerification = new CompletableFuture<>();

                switch (gameState) {
                    case IN_LOBBY:
                        connection.sendReconnectRequest("", clientId);
                        break;
                    case IN_ROOM_WAITING:
                    case IN_GAME:
                        connection.sendReconnectRequest(
                                currentRoom != null ? currentRoom : "",
                                clientId
                        );
                        break;
                    default:
                        pendingReconnectVerification = null;
                        return new ReconnectResult(false, "Invalid state");
                }

                try {
                    boolean verified = pendingReconnectVerification.get(8, TimeUnit.SECONDS);

                    if (verified) {
                        System.out.println("Protocol reconnect VERIFIED");
                        return new ReconnectResult(true, "Reconnected successfully");
                    } else {
                        System.err.println("Server rejected reconnect (RECONNECT_FAIL)");
                        connection.forceCloseSocket(); // Новий метод
                        return new ReconnectResult(false, "Server rejected reconnect");
                    }

                } catch (TimeoutException e) {
                    System.err.println("No response from server (timeout after 8s)");
                    connection.forceCloseSocket();
                    return new ReconnectResult(false, "Server did not respond");
                } finally {
                    pendingReconnectVerification = null;
                }

            } catch (InterruptedException e) {
                System.err.println("Reconnect interrupted");
                Thread.currentThread().interrupt();
                return new ReconnectResult(false, "Interrupted");
            } catch (Exception e) {
                System.err.println("Reconnect exception: " + e.getMessage());
                e.printStackTrace();
                return new ReconnectResult(false, e.getMessage());
            }
        });

        try {
            return future.get(15000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.err.println("Reconnect timeout after 15 seconds");
            future.cancel(true);
            connection.forceCloseSocket();
            return new ReconnectResult(false, "Timeout");
        } catch (Exception e) {
            System.err.println("Reconnect error: " + e.getMessage());
            return new ReconnectResult(false, e.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    public void confirmReconnectSuccess() {
        if (pendingReconnectVerification != null) {
            pendingReconnectVerification.complete(true);
        }
    }

    public void confirmReconnectFailure() {
        if (pendingReconnectVerification != null) {
            pendingReconnectVerification.complete(false);
        }
    }

    private int calculateNextDelay(int attempt) {
        int delay = INITIAL_RECONNECT_DELAY_MS * (int) Math.pow(2, attempt - 1);
        return Math.min(delay, MAX_RECONNECT_DELAY_MS);
    }

    private void handleReconnectSuccess() {
        System.out.println("========== RECONNECTION SUCCESSFUL ==========");

        stopReconnect();
        reconnectAttempts.set(0);
        connection.getHeartbeatManager().reset();
        connection.getHeartbeatManager().start();

        connection.notifyConnectionStatePublic(true);
        connection.setInReconnectMode(false);


        notifyStatus(ReconnectStatus.RECONNECTED, 0,
                (System.currentTimeMillis() - disconnectStartTime) / 1000);

        if (onReconnectSuccess != null) {
            onReconnectSuccess.run();
        }
    }

    /**
     * Ручна спроба реконекту (викликається кнопкою в UI)
     */
    public synchronized boolean manualReconnect() {
        System.out.println("Manual reconnect attempt...");

        if (serverHost == null || clientId == null) {
            System.err.println("No connection data");
            return false;
        }

        long disconnectDuration = System.currentTimeMillis() - disconnectStartTime;
        long disconnectSeconds = disconnectDuration / 1000;

        // Перевірити чи не минуло критичного часу (80 секунд)
        if (disconnectSeconds >= LONG_DISCONNECT_THRESHOLD_SEC) {
            System.err.println("Disconnect duration exceeded critical threshold");
            notifyStatus(ReconnectStatus.CRITICAL_TIMEOUT, 0, disconnectSeconds);
            return false;
        }

        ReconnectResult result = attemptReconnectWithTimeout();

        if (result.success) {
            handleReconnectSuccess();
            return true;
        } else {
            System.err.println("Manual reconnect failed");
            return false;
        }
    }

    private void notifyStatus(ReconnectStatus status, int attempt, long disconnectDuration) {
        if (onStatusUpdate != null) {
            onStatusUpdate.accept(status);
        }
    }

    // Допоміжний клас
    private static class ReconnectResult {
        final boolean success;
        final String message;

        ReconnectResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    // ========== Enum для стану гри ==========
    public enum ClientGameState {
        NOT_LOGGED_IN,
        IN_LOBBY,
        IN_ROOM_WAITING,
        IN_GAME
    }

    // ========== Enum для статусу реконекту ==========
    public enum ReconnectStatus {
        SHORT_DISCONNECT,      // 0-40 сек: автоматичні спроби
        LONG_DISCONNECT,       // 40-80 сек: ручна кнопка
        CRITICAL_TIMEOUT,      // 80+ сек: сервер відключив
        RECONNECTED            // Успішно
    }

    // Геттери та сеттери

    public boolean isReconnecting() {
        return isReconnecting.get();
    }

    public int getReconnectAttempts() {
        return reconnectAttempts.get();
    }

    public int getMaxReconnectAttempts() {
        return MAX_AUTO_RECONNECT_ATTEMPTS;
    }

    public long getDisconnectDuration() {
        if (disconnectStartTime == 0) return 0;
        return System.currentTimeMillis() - disconnectStartTime;
    }

    public void setOnReconnectSuccess(Runnable callback) {
        this.onReconnectSuccess = callback;
    }

    public void setOnReconnectFailed(Runnable callback) {
        this.onReconnectFailed = callback;
    }

    public void setOnStatusUpdate(Consumer<ReconnectStatus> callback) {
        this.onStatusUpdate = callback;
    }

    public void setCurrentRoom(String room) {
        this.currentRoom = room;
    }

    public String getCurrentRoom() {
        return currentRoom;
    }

    public ClientGameState getGameState() {
        return gameState;
    }

    public void setGameState(ClientGameState state) {
        this.gameState = state;
        System.out.println("Game state updated to: " + state);
    }
}