package com.checkerstcp.checkerstcp;

/**
 * Types of pieces on the checkers board.
 * Maps to server protocol integer values.
 */
public enum PieceType {
    EMPTY(0),
    WHITE_PIECE(1),
    WHITE_KING(2),
    BLACK_PIECE(3),
    BLACK_KING(4);

    private final int value;

    PieceType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * Converts protocol integer to PieceType.
     *
     * @param value Protocol value
     * @return Corresponding PieceType
     */
    public static PieceType fromValue(int value) {
        for (PieceType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return EMPTY;
    }

    public boolean isWhite() {
        return this == WHITE_PIECE || this == WHITE_KING;
    }

    public boolean isBlack() {
        return this == BLACK_PIECE || this == BLACK_KING;
    }

    public boolean isKing() {
        return this == WHITE_KING || this == BLACK_KING;
    }
}
