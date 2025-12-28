package com.checkerstcp.checkerstcp;

public enum PieceColor {
    WHITE,
    BLACK;

    public PieceColor opposite() {
        return this == WHITE ? BLACK : WHITE;
    }

    public static PieceColor fromPieceType(PieceType type) {
        if (type.isWhite()) {
            return WHITE;
        } else if (type.isBlack()) {
            return BLACK;
        }
        return null;
    }
}
