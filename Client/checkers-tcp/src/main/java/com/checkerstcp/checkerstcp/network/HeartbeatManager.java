package com.checkerstcp.checkerstcp.network;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages heartbeat (PING/PONG) monitoring for connection health detection.
 * Implements automatic connection loss detection through periodic health checks.
 *
 * <p>Features:
 * <ul>
 *   <li>Periodic PING transmission to server</li>
 *   <li>PONG response monitoring with timeout detection</li>
 *   <li>Missed response counting with threshold-based disconnect</li>
 *   <li>Latency measurement for connection quality</li>
 *   <li>Automatic connection restoration detection</li>
 * </ul>
 *
 * <p>Timing configuration:
 * <ul>
 *   <li>PING interval: 5 seconds</li>
 *   <li>PONG timeout: 3 seconds</li>
 *   <li>Connection timeout: 100 seconds (configurable)</li>
 *   <li>Max missed PONGs: 3 before declaring connection lost</li>
 * </ul>
 */
public class HeartbeatManager {
    private final ClientConnection connection;
    private ScheduledExecutorService scheduler;

    // Timing configuration
    private static final long PING_INTERVAL_MS = 5000; // Send PING every 5 seconds
    private static final long PONG_TIMEOUT_MS = 3000;  // Wait 3 seconds for PONG
    private static final long CONNECTION_TIMEOUT_MS = 100000; // Consider connection lost after 100 seconds

    // State tracking
    private final AtomicLong lastPongTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean waitingForPong = new AtomicBoolean(false);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Callbacks
    private Runnable onConnectionLost;
    private Runnable onConnectionRestored;

    private int missedPongs = 0;
    private static final int MAX_MISSED_PONGS = 3; // After 3 missed PONGs - connection lost

    /**
     * Constructs a HeartbeatManager for the given connection.
     *
     * @param connection ClientConnection to monitor
     */
    public HeartbeatManager(ClientConnection connection) {
        this.connection = connection;
    }

    /**
     * Starts heartbeat monitoring.
     * Schedules two periodic tasks:
     * <ul>
     *   <li>PING sender: Sends PING every 5 seconds</li>
     *   <li>Timeout checker: Verifies PONG responses every second</li>
     * </ul>
     *
     * Thread-safe: Can be called multiple times, but only first call takes effect.
     */
    public synchronized void start() {
        if (isRunning.get()) {
            System.out.println("Heartbeat already running");
            return;
        }

        isRunning.set(true);
        lastPongTime.set(System.currentTimeMillis());
        missedPongs = 0;

        // Create daemon thread pool for heartbeat tasks
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "HeartbeatThread");
            t.setDaemon(true);
            return t;
        });

        // Task 1: Send PINGs periodically
        scheduler.scheduleAtFixedRate(
                this::sendPing,
                0,
                PING_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        // Task 2: Check for PONG timeouts
        scheduler.scheduleAtFixedRate(
                this::checkTimeout,
                PONG_TIMEOUT_MS,
                1000,
                TimeUnit.MILLISECONDS
        );

        System.out.println("💓 Heartbeat started (interval: " + PING_INTERVAL_MS + "ms)");
    }

    /**
     * Stops heartbeat monitoring.
     * Cancels scheduled tasks and shuts down executor.
     *
     * Thread-safe: Safe to call multiple times.
     */
    public synchronized void stop() {
        if (!isRunning.get()) {
            return;
        }

        isRunning.set(false);

        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }

        System.out.println("💓 Heartbeat stopped");
    }

    /**
     * Sends PING message to server.
     * Called periodically by scheduler. Sets waiting flag to track PONG response.
     */
    private void sendPing() {
        if (!connection.isConnected() || !isRunning.get()) {
            return;
        }

        try {
            waitingForPong.set(true);
            connection.sendPing();
            System.out.println("PING sent");
        } catch (Exception e) {
            System.err.println("Failed to send PING: " + e.getMessage());
        }
    }

    /**
     * Handles received PONG response from server.
     * Updates last PONG timestamp, resets missed counter, and calculates latency.
     * If connection was previously in reconnect mode, triggers restoration callback.
     */
    public void onPongReceived() {
        long now = System.currentTimeMillis();
        long latency = now - lastPongTime.get();

        lastPongTime.set(now);
        waitingForPong.set(false);
        missedPongs = 0;

        System.out.println("PONG received (latency: " + latency + "ms)");

        // Detect connection restoration
        if (connection.isInReconnectMode()) {
            System.out.println("Connection restored via heartbeat");
            if (onConnectionRestored != null) {
                onConnectionRestored.run();
            }
        }
    }

    /**
     * Checks for PONG timeout and connection loss.
     * Called periodically by scheduler. Implements two timeout mechanisms:
     * <ul>
     *   <li>PONG timeout: Individual PONG response not received in time</li>
     *   <li>Connection timeout: No PONG received for extended period</li>
     * </ul>
     *
     * After MAX_MISSED_PONGS consecutive misses, declares connection lost.
     */
    private void checkTimeout() {
        if (!connection.isConnected() || !isRunning.get()) {
            return;
        }

        long timeSinceLastPong = System.currentTimeMillis() - lastPongTime.get();

        // Check for individual PONG timeout
        if (waitingForPong.get() && timeSinceLastPong > PONG_TIMEOUT_MS) {
            missedPongs++;
            System.err.println("PONG timeout! Missed pongs: " + missedPongs + "/" + MAX_MISSED_PONGS);

            waitingForPong.set(false);

            if (missedPongs >= MAX_MISSED_PONGS) {
                handleConnectionLost();
            }
        }

        // Check for total connection timeout
        if (timeSinceLastPong > CONNECTION_TIMEOUT_MS) {
            System.err.println("Connection timeout! Last pong: " + timeSinceLastPong + "ms ago");
            handleConnectionLost();
        }
    }

    /**
     * Handles detected connection loss.
     * Stops heartbeat monitoring and triggers connection lost callback.
     */
    private void handleConnectionLost() {
        if (!isRunning.get()) {
            return;
        }

        System.err.println("Connection lost detected by heartbeat");

        stop();

        if (onConnectionLost != null) {
            onConnectionLost.run();
        }
    }

    /**
     * Resets heartbeat state after reconnection.
     * Clears missed PONG counter and updates last PONG timestamp.
     */
    public void reset() {
        lastPongTime.set(System.currentTimeMillis());
        waitingForPong.set(false);
        missedPongs = 0;
    }

    // ========== Getters and Setters ==========

    /**
     * Sets callback for connection loss event.
     *
     * @param callback Runnable to execute when connection loss is detected
     */
    public void setOnConnectionLost(Runnable callback) {
        this.onConnectionLost = callback;
    }

    /**
     * Sets callback for connection restoration event.
     *
     * @param callback Runnable to execute when connection is restored
     */
    public void setOnConnectionRestored(Runnable callback) {
        this.onConnectionRestored = callback;
    }

    /**
     * Gets timestamp of last received PONG.
     *
     * @return Last PONG time in milliseconds
     */
    public long getLastPongTime() {
        return lastPongTime.get();
    }

    /**
     * Checks if currently waiting for PONG response.
     *
     * @return true if waiting for PONG
     */
    public boolean isWaitingForPong() {
        return waitingForPong.get();
    }

    /**
     * Gets count of consecutive missed PONGs.
     *
     * @return Number of missed PONGs
     */
    public int getMissedPongs() {
        return missedPongs;
    }
}
