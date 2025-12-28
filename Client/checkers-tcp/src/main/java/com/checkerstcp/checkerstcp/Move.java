package com.checkerstcp.checkerstcp;

import java.util.ArrayList;
import java.util.List;

public class Move {
    private final int fromRow;
    private final int fromCol;
    private final int toRow;
    private final int toCol;
    private final MoveType type;
    private final List<Position> capturedPositions;
    private final boolean promotesToKing;

    public Move(int fromRow, int fromCol, int toRow, int toCol) {
        this(fromRow, fromCol, toRow, toCol, MoveType.NORMAL, false);
    }

    public Move(int fromRow, int fromCol, int toRow, int toCol, MoveType type, boolean promotesToKing) {
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.toRow = toRow;
        this.toCol = toCol;
        this.type = type;
        this.capturedPositions = new ArrayList<>();
        this.promotesToKing = promotesToKing;
    }

    /**
     * Додати позицію захопленої шашки
     */
    public void addCapturedPosition(int row, int col) {
        capturedPositions.add(new Position(row, col));
    }

    /**
     * Чи є хід захопленням (стрибок)
     */
    public boolean isCapture() {
        return type == MoveType.CAPTURE || !capturedPositions.isEmpty();
    }

    /**
     * Отримати позицію захопленої шашки (для простого стрибка)
     */
    public Position getCapturedPosition() {
        if (isCapture() && !capturedPositions.isEmpty()) {
            return capturedPositions.get(0);
        }
        // Для простого стрибка - середина між from і to
        if (Math.abs(toRow - fromRow) == 2) {
            int midRow = (fromRow + toRow) / 2;
            int midCol = (fromCol + toCol) / 2;
            return new Position(midRow, midCol);
        }
        return null;
    }

    /**
     * Відстань ходу
     */
    public int getDistance() {
        return Math.max(Math.abs(toRow - fromRow), Math.abs(toCol - fromCol));
    }

    // Геттери
    public int getFromRow() {
        return fromRow;
    }

    public int getFromCol() {
        return fromCol;
    }

    public int getToRow() {
        return toRow;
    }

    public int getToCol() {
        return toCol;
    }

    public MoveType getType() {
        return type;
    }

    public List<Position> getCapturedPositions() {
        return new ArrayList<>(capturedPositions);
    }

    public boolean promotesToKing() {
        return promotesToKing;
    }

    @Override
    public String toString() {
        return String.format("Move[(%d,%d)->(%d,%d) %s%s]",
                fromRow, fromCol, toRow, toCol, type,
                promotesToKing ? " +KING" : "");
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Move move = (Move) obj;
        return fromRow == move.fromRow && fromCol == move.fromCol &&
                toRow == move.toRow && toCol == move.toCol;
    }

    @Override
    public int hashCode() {
        return (fromRow << 24) | (fromCol << 16) | (toRow << 8) | toCol;
    }
}
