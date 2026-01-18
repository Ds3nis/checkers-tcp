package com.checkerstcp.checkerstcp.network;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Керує heartbeat (ping/pong) для моніторингу з'єднання
 */
public class HeartbeatManager {
    private final ClientConnection connection;
    private ScheduledExecutorService scheduler;

    // Налаштування таймінгів
    private static final long PING_INTERVAL_MS = 5000; // Пінг кожні 5 секунд
    private static final long PONG_TIMEOUT_MS = 3000;  // Очікування понгу 3 секунди
    private static final long CONNECTION_TIMEOUT_MS = 100000; // Вважати з'єднання втраченим через 15 сек

    // Стан
    private final AtomicLong lastPongTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean waitingForPong = new AtomicBoolean(false);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Callbacks
    private Runnable onConnectionLost;
    private Runnable onConnectionRestored;

    private int missedPongs = 0;
    private static final int MAX_MISSED_PONGS = 3; // Після 3 пропущених понгів - з'єднання втрачено

    public HeartbeatManager(ClientConnection connection) {
        this.connection = connection;
    }

    public synchronized void start() {
        if (isRunning.get()) {
            System.out.println("Heartbeat already running");
            return;
        }

        isRunning.set(true);
        lastPongTime.set(System.currentTimeMillis());
        missedPongs = 0;

        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "HeartbeatThread");
            t.setDaemon(true);
            return t;
        });

        //Task 1: Відправка пінгів
        scheduler.scheduleAtFixedRate(
                this::sendPing,
                0,
                PING_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );

        // Task 2: Перевірка таймаутів
        scheduler.scheduleAtFixedRate(
                this::checkTimeout,
                PONG_TIMEOUT_MS,
                1000,
                TimeUnit.MILLISECONDS
        );

        System.out.println("💓 Heartbeat started (interval: " + PING_INTERVAL_MS + "ms)");
    }

    /**
     * Зупинити heartbeat моніторинг
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
     * Відправити PING на сервер
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
     * Обробити отриманий PONG
     */
    public void onPongReceived() {
        long now = System.currentTimeMillis();
        long latency = now - lastPongTime.get();

        lastPongTime.set(now);
        waitingForPong.set(false);
        missedPongs = 0;

        System.out.println("PONG received (latency: " + latency + "ms)");

        // Якщо з'єднання було втрачене і тепер відновлене
        if (connection.isInReconnectMode()) {
            System.out.println("Connection restored via heartbeat");
            if (onConnectionRestored != null) {
                onConnectionRestored.run();
            }
        }
    }

    /**
     * Перевірити чи не виник таймаут
     */
    private void checkTimeout() {
        if (!connection.isConnected() || !isRunning.get()) {
            return;
        }

        long timeSinceLastPong = System.currentTimeMillis() - lastPongTime.get();


        if (waitingForPong.get() && timeSinceLastPong > PONG_TIMEOUT_MS) {
            missedPongs++;
            System.err.println("PONG timeout! Missed pongs: " + missedPongs + "/" + MAX_MISSED_PONGS);

            waitingForPong.set(false);

            if (missedPongs >= MAX_MISSED_PONGS) {
                handleConnectionLost();
            }
        }

        if (timeSinceLastPong > CONNECTION_TIMEOUT_MS) {
            System.err.println("Connection timeout! Last pong: " + timeSinceLastPong + "ms ago");
            handleConnectionLost();
        }
    }

    /**
     * Обробити втрату з'єднання
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
     * Скинути heartbeat після реконекту
     */
    public void reset() {
        lastPongTime.set(System.currentTimeMillis());
        waitingForPong.set(false);
        missedPongs = 0;
    }

    // Геттери та сеттери

    public void setOnConnectionLost(Runnable callback) {
        this.onConnectionLost = callback;
    }

    public void setOnConnectionRestored(Runnable callback) {
        this.onConnectionRestored = callback;
    }

    public long getLastPongTime() {
        return lastPongTime.get();
    }

    public boolean isWaitingForPong() {
        return waitingForPong.get();
    }

    public int getMissedPongs() {
        return missedPongs;
    }
}
