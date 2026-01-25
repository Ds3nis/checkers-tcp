package com.checkerstcp.checkerstcp;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Represents a single move in the move history.
 * Used for displaying move list in player info panel.
 */
public class PlayerMoveItem {

    private String fromCellMove;
    private String toCellMove;
    private LocalTime moveTime;

    /**
     * Creates a move history item.
     *
     * @param fromCellMove Starting position (e.g., "A3")
     * @param toCellMove Ending position (e.g., "B4")
     * @param moveTime Timestamp of move
     */
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
