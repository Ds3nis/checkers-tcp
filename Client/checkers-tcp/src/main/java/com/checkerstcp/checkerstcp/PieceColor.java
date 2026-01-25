package com.checkerstcp.checkerstcp;
/**
 * Piece colors in checkers.
 */
public enum PieceColor {
    WHITE,
    BLACK;

    /**
     * Gets opposite color.
     *
     * @return Opposite piece color
     */
    public PieceColor opposite() {
        return this == WHITE ? BLACK : WHITE;
    }

    /**
     * Extracts color from piece type.
     *
     * @param type Piece type
     * @return Corresponding color, or null if empty
     */
    public static PieceColor fromPieceType(PieceType type) {
        if (type.isWhite()) {
            return WHITE;
        } else if (type.isBlack()) {
            return BLACK;
        }
        return null;
    }
}
