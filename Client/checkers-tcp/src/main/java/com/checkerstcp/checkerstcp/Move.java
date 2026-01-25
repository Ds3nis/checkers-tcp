package com.checkerstcp.checkerstcp;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a move in the checkers game.
 * Supports normal moves, single captures, and multi-jump sequences.
 *
 * <p>Move types:
 * <ul>
 *   <li>NORMAL: Simple diagonal move</li>
 *   <li>CAPTURE: Single jump over opponent piece</li>
 *   <li>MULTI_CAPTURE: Multiple consecutive jumps</li>
 * </ul>
 */
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
     * Adds captured piece position to this move.
     * Used to track which pieces were captured during the move.
     *
     * @param row Row of captured piece
     * @param col Column of captured piece
     */
    public void addCapturedPosition(int row, int col) {
        capturedPositions.add(new Position(row, col));
    }

    /**
     * Adds position to move path.
     * Used for multi-jump moves to track complete path.
     *
     * @param row Row position in path
     * @param col Column position in path
     */
    public void addPathPosition(int row, int col) {
        path.add(new Position(row, col));
    }

    /**
     * Checks if this is a capture move.
     *
     * @return true if move captures opponent pieces
     */
    public boolean isCapture() {
        return type == MoveType.CAPTURE || type == MoveType.MULTI_CAPTURE;
    }

    /**
     * Checks if this is a multi-capture move.
     *
     * @return true if move captures multiple pieces
     */
    public boolean isMultiCapture() {
        return type == MoveType.MULTI_CAPTURE;
    }

    /**
     * Gets position of captured piece for simple capture.
     *
     * @return First captured position, or null if no captures
     */
    public Position getCapturedPosition() {
        return capturedPositions.isEmpty() ? null : capturedPositions.get(0);
    }


    public List<Position> getPath() {
        return path;
    }

    /**
     * Calculates Manhattan distance of move.
     *
     * @return Maximum of row and column distance
     */
    public int getDistance() {
        return Math.max(Math.abs(toRow - fromRow), Math.abs(toCol - fromCol));
    }
    
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
