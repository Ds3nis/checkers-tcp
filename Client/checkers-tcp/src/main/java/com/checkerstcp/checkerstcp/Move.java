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
    private final List<Position> path;

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
        this.path = new ArrayList<>();
    }

    /**
     * Додати позицію захопленої шашки
     */
    public void addCapturedPosition(int row, int col) {
        capturedPositions.add(new Position(row, col));
    }

    public void addPathPosition(int row, int col) {
        path.add(new Position(row, col));
    }


    public boolean isCapture() {
        return type == MoveType.CAPTURE || type == MoveType.MULTI_CAPTURE;
    }

    public boolean isMultiCapture() {
        return type == MoveType.MULTI_CAPTURE;
    }

    /**
     * Отримати позицію захопленої шашки (для простого стрибка)
     */
    public Position getCapturedPosition() {
        return capturedPositions.isEmpty() ? null : capturedPositions.get(0);
    }

    public List<Position> getPath() {
        return path;
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
        return String.format("Move(%d,%d → %d,%d, %s, captures=%d)",
                fromRow, fromCol, toRow, toCol, type, capturedPositions.size());
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
