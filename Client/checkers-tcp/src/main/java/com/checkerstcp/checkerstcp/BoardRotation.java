package com.checkerstcp.checkerstcp;

public class BoardRotation {

    /**
     * Поворот дошки на 180° БЕЗ зміни кольорів шашок
     */
    public static int[][] rotateBoard(int[][] board) {
        int size = board.length;
        int[][] rotated = new int[size][size];

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int newRow = size - 1 - i;
                int newCol = size - 1 - j;
                rotated[newRow][newCol] = board[i][j];  // ✅ Без swapColor!
            }
        }

        return rotated;
    }

    /**
     * Поворот координат ходу на 180°
     */
    public static class RotatedMove {
        public final int fromRow;
        public final int fromCol;
        public final int toRow;
        public final int toCol;

        public RotatedMove(int fromRow, int fromCol, int toRow, int toCol) {
            this.fromRow = fromRow;
            this.fromCol = fromCol;
            this.toRow = toRow;
            this.toCol = toCol;
        }
    }

    public static RotatedMove rotateCoordinates(int fromRow, int fromCol, int toRow, int toCol, int boardSize) {
        return new RotatedMove(
                boardSize - 1 - fromRow,
                boardSize - 1 - fromCol,
                boardSize - 1 - toRow,
                boardSize - 1 - toCol
        );
    }
}
