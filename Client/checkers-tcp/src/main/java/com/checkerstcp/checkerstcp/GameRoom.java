package com.checkerstcp.checkerstcp;

import com.checkerstcp.checkerstcp.controller.RoomItemController;

import java.util.ArrayList;
import java.util.List;

public class GameRoom {
    private final int id;
    private final String name;
    private RoomStatus roomStatus;
    private List<Player> players;
    private final int maxPlayers = 2;

    public GameRoom(int id, String name, List<Player> players) throws Exception {
        this.name = name;
        this.id = id;
        this.players = new ArrayList<>(players);
        updateRoomStatus();
    }

    public GameRoom(String name) throws Exception {
        this(0, name, new ArrayList<>());
    }

    private GameRoom(int id, String name, List<Player> players, boolean skipValidation) {
        this.id = id;
        this.name = name;
        this.players = new ArrayList<>(players);
        updateRoomStatus();
    }

    /**
     * Оновлення статусу кімнати в залежності від кількості гравців
     */
    private void updateRoomStatus() {
        int playerCount = players.size();

        if (playerCount == 0) {
            this.roomStatus = RoomStatus.WAITING_FOR_PLAYERS;
        } else if (playerCount == 1) {
            this.roomStatus = RoomStatus.WAITING_FOR_PLAYERS;
        } else if (playerCount == maxPlayers) {
            this.roomStatus = RoomStatus.FULL;
        } else if (playerCount > maxPlayers) {
            this.roomStatus = RoomStatus.FULL;
        }
    }

    /**
     * Додати гравця до кімнати
     */
    public boolean addPlayer(Player player) {
        if (players.size() >= maxPlayers) {
            return false;
        }

        players.add(player);
        updateRoomStatus();
        return true;
    }

    /**
     * Видалити гравця з кімнати
     */
    public boolean removePlayer(Player player) {
        boolean removed = players.remove(player);
        if (removed) {
            updateRoomStatus();
        }
        return removed;
    }

    /**
     * Встановити статус гри
     */
    public void setPlaying(boolean playing) {
        if (playing && players.size() == maxPlayers) {
            this.roomStatus = RoomStatus.PLAYING;
        } else {
            updateRoomStatus();
        }
    }

    public void setPlayers(List<Player> players) throws Exception {
        if (players == null) {
            throw new Exception("Players list cannot be null");
        }

        if (players.size() > maxPlayers) {
            throw new Exception("Too many players (max: " + maxPlayers + ")");
        }

        this.players = new ArrayList<>(players);
        updateRoomStatus();
    }

    // Геттери
    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public RoomStatus getRoomStatus() {
        return roomStatus;
    }

    public List<Player> getPlayers() {
        return new ArrayList<>(players); // Повертаємо копію
    }

    public int getNumPlayers() {
        return players.size();
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public boolean isFull() {
        return players.size() >= maxPlayers;
    }

    public boolean isEmpty() {
        return players.isEmpty();
    }

    public boolean isPlaying() {
        return roomStatus == RoomStatus.PLAYING;
    }

    @Override
    public String toString() {
        return String.format("GameRoom[id=%d, name='%s', status=%s, players=%d/%d]",
                id, name, roomStatus, players.size(), maxPlayers);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GameRoom gameRoom = (GameRoom) obj;
        return id == gameRoom.id || name.equals(gameRoom.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
