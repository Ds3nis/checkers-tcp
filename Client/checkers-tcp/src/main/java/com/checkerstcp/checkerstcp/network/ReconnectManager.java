package com.checkerstcp.checkerstcp.network;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Manages automatic and manual reconnection attempts after connection loss.
 * Implements a three-phase reconnection strategy based on disconnect duration.
 *
 * <p>Reconnection phases:
 * <ul>
 *   <li>SHORT_DISCONNECT (0-40s): Automatic reconnection with exponential backoff</li>
 *   <li>LONG_DISCONNECT (40-80s): Manual reconnection required (automatic attempts stopped)</li>
 *   <li>CRITICAL_TIMEOUT (80+s): Server has removed client, return to lobby</li>
 * </ul>
 *
 * <p>Features:
 * <ul>
 *   <li>Exponential backoff for automatic attempts (1s → 5s max delay)</li>
 *   <li>Protocol-level reconnection verification with timeout</li>
 *   <li>Game state preservation and restoration</li>
 *   <li>Thread-safe reconnection management</li>
 *   <li>Status callbacks for UI updates</li>
 * </ul>
 *
 * <p>Timing configuration:
 * <ul>
 *   <li>Initial delay: 1 second</li>
 *   <li>Max delay: 5 seconds</li>
 *   <li>Max auto attempts: 8 (~40 seconds)</li>
 *   <li>Reconnect timeout: 10 seconds per attempt</li>
 * </ul>
 */
public class ReconnectManager {
    private final ClientConnection connection;
    private ScheduledExecutorService reconnectExecutor;

    // Timing configuration
    private static final int INITIAL_RECONNECT_DELAY_MS = 1000;      // 1 second initial delay
    private static final int MAX_RECONNECT_DELAY_MS = 5000;          // 5 seconds maximum delay
    private static final int SHORT_DISCONNECT_THRESHOLD_SEC = 40;    // Short disconnect (0-40s)
    private static final int LONG_DISCONNECT_THRESHOLD_SEC = 80;     // Critical timeout (80+s)
    private static final int MAX_AUTO_RECONNECT_ATTEMPTS = 8;        // ~40 seconds of attempts
    private static final long RECONNECT_TIMEOUT_MS = 10000;          // 10 seconds per attempt

    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private long disconnectStartTime = 0;

    // Connection data for reconnection
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


    /**
     * Constructs a ReconnectManager for the given connection.
     *
     * @param connection ClientConnection to manage reconnection for
     */
    public ReconnectManager(ClientConnection connection) {
        this.connection = connection;
    }

    /**
     * Saves connection data for future reconnection attempts.
     * Called before disconnect or periodically to maintain reconnection state.
     *
     * @param host Server hostname
     * @param port Server port
     * @param clientId Client identifier
     * @param room Current room name (null if in lobby)
     * @param state Current game state
     */
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

    /**
     * Starts automatic reconnection process.
     * Initiates scheduled reconnection attempts with exponential backoff.
     * Continues until max attempts reached or SHORT_DISCONNECT threshold exceeded.
     *
     * Thread-safe: Can be called multiple times, but only first call takes effect.
     */
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
        System.out.println("Will reconnect to state: " + gameState);

        notifyStatus(ReconnectStatus.SHORT_DISCONNECT, 0, 0);
        scheduleNextAttempt(INITIAL_RECONNECT_DELAY_MS);
    }

    /**
     * Stops automatic reconnection process.
     * Cancels scheduled attempts and shuts down executor.
     *
     * Thread-safe: Safe to call multiple times.
     */
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

    /**
     * Schedules next reconnection attempt with specified delay.
     *
     * @param delayMs Delay in milliseconds before next attempt
     */
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


    /**
     * Attempts reconnection to server.
     * Checks disconnect duration and transitions to LONG_DISCONNECT phase if needed.
     * Uses exponential backoff for retry delays.
     */
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

        // ========== TRANSITION TO LONG_DISCONNECT PHASE ==========
        if (disconnectSeconds >= SHORT_DISCONNECT_THRESHOLD_SEC) {
            System.out.println("Transition to LONG_DISCONNECT (40+ seconds)");
            notifyStatus(ReconnectStatus.LONG_DISCONNECT, attempt, disconnectSeconds);

            stopReconnect();
            return;
        }

        // ========== AUTOMATIC ATTEMPTS (0-40 SECONDS) ==========
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
            // Continue attempts if not yet reached 40 seconds
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

    /**
     * Attempts reconnection with timeout protection.
     * Performs both TCP reconnection and protocol-level verification.
     *
     * <p>Steps:
     * <ol>
     *   <li>Establish TCP connection</li>
     *   <li>Send RECONNECT_REQUEST with saved game state</li>
     *   <li>Wait for RECONNECT_OK/FAIL response (8 second timeout)</li>
     *   <li>Clean up on failure</li>
     * </ol>
     *
     * @return ReconnectResult indicating success or failure with reason
     */
    private ReconnectResult attemptReconnectWithTimeout() {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<ReconnectResult> future = executor.submit(() -> {
            try {

                // Step 1: TCP reconnection
                boolean tcpConnected = connection.reconnect(serverHost, serverPort, clientId);

                if (!tcpConnected) {
                    System.err.println("TCP reconnect failed");
                    return new ReconnectResult(false, "TCP connection failed");
                }

                System.out.println("TCP connected, verifying protocol...");

                // Step 2: Protocol verification
                if (gameState == null) {
                    gameState = ClientGameState.IN_LOBBY;
                }

                // Send appropriate reconnect request based on game state
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

                // Step 3: Wait for server response
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

    /**
     * Confirms successful reconnection from protocol level.
     * Called when RECONNECT_OK message is received from server.
     */
    public void confirmReconnectSuccess() {
        if (pendingReconnectVerification != null) {
            pendingReconnectVerification.complete(true);
        }
    }

    /**
     * Confirms failed reconnection from protocol level.
     * Called when RECONNECT_FAIL message is received from server.
     */
    public void confirmReconnectFailure() {
        if (pendingReconnectVerification != null) {
            pendingReconnectVerification.complete(false);
        }
    }


    /**
     * Calculates next reconnection delay using exponential backoff.
     * Delay doubles with each attempt, capped at maximum.
     *
     * @param attempt Current attempt number
     * @return Delay in milliseconds
     */
    private int calculateNextDelay(int attempt) {
        int delay = INITIAL_RECONNECT_DELAY_MS * (int) Math.pow(2, attempt - 1);
        return Math.min(delay, MAX_RECONNECT_DELAY_MS);
    }

    /**
     * Handles successful reconnection.
     * Stops reconnection process, resets state, restarts heartbeat,
     * and notifies callbacks.
     */
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
     * Attempts manual reconnection (triggered by UI button).
     * Checks if critical timeout has been exceeded before attempting.
     *
     * @return true if reconnection successful, false otherwise
     */
    public synchronized boolean manualReconnect() {
        System.out.println("Manual reconnect attempt...");

        if (serverHost == null || clientId == null) {
            System.err.println("No connection data");
            return false;
        }

        long disconnectDuration = System.currentTimeMillis() - disconnectStartTime;
        long disconnectSeconds = disconnectDuration / 1000;

        // Check if critical timeout has been exceeded (80 seconds)
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

    /**
     * Notifies status callback of reconnection state change.
     *
     * @param status Current reconnection status
     * @param attempt Number of attempts made
     * @param disconnectDuration Duration of disconnect in seconds
     */
    private void notifyStatus(ReconnectStatus status, int attempt, long disconnectDuration) {
        if (onStatusUpdate != null) {
            onStatusUpdate.accept(status);
        }
    }

    /**
     * Helper class for reconnection attempt results.
     */
    private static class ReconnectResult {
        final boolean success;
        final String message;

        /**
         * Constructs a reconnection result.
         *
         * @param success Whether reconnection succeeded
         * @param message Result message or error description
         */
        ReconnectResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /**
     * Client game states for reconnection targeting.
     * Determines which room/state to reconnect to.
     */
    public enum ClientGameState {
        NOT_LOGGED_IN,
        IN_LOBBY,
        IN_ROOM_WAITING,
        IN_GAME
    }

    /**
     * Reconnection status phases.
     */
    public enum ReconnectStatus {
        SHORT_DISCONNECT,      // 0-40s: Automatic attempts
        LONG_DISCONNECT,       // 40-80s: Manual button available
        CRITICAL_TIMEOUT,      // 80+s: Server disconnected client
        RECONNECTED            // Successfully reconnected
    }



    // ========== Getters and Setters ==========

    /**
     * Checks if reconnection is in progress.
     *
     * @return true if reconnecting
     */
    public boolean isReconnecting() {
        return isReconnecting.get();
    }

    /**
     * Gets number of reconnection attempts made.
     *
     * @return Attempt count
     */
    public int getReconnectAttempts() {
        return reconnectAttempts.get();
    }

    /**
     * Gets maximum number of automatic reconnection attempts.
     *
     * @return Max attempts
     */
    public int getMaxReconnectAttempts() {
        return MAX_AUTO_RECONNECT_ATTEMPTS;
    }

    /**
     * Gets duration of current disconnection.
     *
     * @return Duration in milliseconds, or 0 if not disconnected
     */
    public long getDisconnectDuration() {
        if (disconnectStartTime == 0) return 0;
        return System.currentTimeMillis() - disconnectStartTime;
    }

    /**
     * Sets callback for successful reconnection.
     *
     * @param callback Runnable to execute on success
     */
    public void setOnReconnectSuccess(Runnable callback) {
        this.onReconnectSuccess = callback;
    }

    /**
     * Sets callback for failed reconnection.
     *
     * @param callback Runnable to execute on failure
     */
    public void setOnReconnectFailed(Runnable callback) {
        this.onReconnectFailed = callback;
    }

    /**
     * Sets callback for status updates.
     *
     * @param callback Consumer that receives ReconnectStatus updates
     */
    public void setOnStatusUpdate(Consumer<ReconnectStatus> callback) {
        this.onStatusUpdate = callback;
    }

    /**
     * Sets current room name.
     *
     * @param room Room name to save
     */
    public void setCurrentRoom(String room) {
        this.currentRoom = room;
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
     * Gets current game state.
     *
     * @return ClientGameState
     */
    public ClientGameState getGameState() {
        return gameState;
    }

    /**
     * Sets current game state.
     *
     * @param state New game state
     */
    public void setGameState(ClientGameState state) {
        this.gameState = state;
        System.out.println("Game state updated to: " + state);
    }
}