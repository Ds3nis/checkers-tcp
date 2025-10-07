package com.checkerstcp.checkerstcp;

import com.checkerstcp.checkerstcp.controller.RoomItemController;

import java.util.List;

public class GameRoom {
    private final int id;
    private final String name;
    private RoomStatus roomStatus;
    private List<Player> players;

    public GameRoom(String name, List<Player> players, int id) throws Exception {
        this.name = name;
        this.id = id;
        this.roomStatus = RoomStatus.WAITING_FOR_PLAYERS;
        setPlayers(players);
    }

    public void connect(){

    }

    private void setPlayers(List<Player> players) throws Exception {
        if (players.isEmpty() ||  players.size() != 2) {
            throw new Exception("Invalid number of players");
        }

        this.players = players;
    }

    public void setRoomStatus(RoomStatus roomStatus) throws Exception {
        if (roomStatus == null) {
            throw new Exception("Invalid room status");
        }
        this.roomStatus = roomStatus;
    }


    public RoomStatus getRoomStatus() {return roomStatus;}
    public String getName() { return name; }
    public List<Player> getPlayers() { return players; }
    public int getNumPlayers() { return players.size(); }
    public int getId() {return id;}
}
