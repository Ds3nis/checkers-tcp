package com.checkerstcp.checkerstcp;

import java.time.LocalDateTime;
import java.time.LocalTime;

public class PlayerMoveItem {

    private String fromCellMove;
    private String toCellMove;
    private LocalTime moveTime;

    public PlayerMoveItem(String fromCellMove, String toCellMove, LocalTime moveTime) {
        this.fromCellMove = fromCellMove;
        this.toCellMove = toCellMove;
        this.moveTime = moveTime;
    }


    public String getFromCellMove() {
        return fromCellMove;
    }

    public LocalTime getMoveTime() {
        return moveTime;
    }

    public String getToCellMove() {
        return toCellMove;
    }

}
