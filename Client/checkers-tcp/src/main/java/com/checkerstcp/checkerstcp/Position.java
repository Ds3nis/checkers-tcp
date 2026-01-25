package com.checkerstcp.checkerstcp;

/**
 * Represents a position on the checkers board.
 * Uses 0-indexed coordinates (0-7 for both row and column).
 *
 * <p>Display format: Column letter (A-H) + row number (1-8)
 * Example: A8 = row 0, col 0
 */
public class Position {
    private final int row;
    private final int col;

    public Position(int row, int col) {
        this.row = row;
        this.col = col;
    }

    public int getRow() {
        return row;
    }

    public int getCol() {
        return col;
    }

    /**
     * Checks if position is within board bounds.
     *
     * @return true if position is valid (0-7 for row and col)
     */
    public boolean isValid() {
        return row >= 0 && row < 8 && col >= 0 && col < 8;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Position position = (Position) obj;
        return row == position.row && col == position.col;
    }

    @Override
    public int hashCode() {
        return (row << 8) | col;
    }


    /**
     * Converts position to chess notation.
     * Format: Column letter (A-H) + row number (8-1)
     *
     * @return Position in chess notation (e.g., "A8", "H1")
     */
    @Override
    public String toString() {
        char colChar = (char) ('A' + col);
        return String.format("%c%d", colChar, 8 - row);
    }
}