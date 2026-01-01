package com.checkerstcp.checkerstcp;

import javafx.animation.TranslateTransition;
import javafx.animation.FadeTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Pos;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

public class CheckersBoard extends GridPane {
    private static final int SIZE = 8;

    private double cellSize;
    private Piece[][] pieces = new Piece[SIZE][SIZE];
    private StackPane[][] cells = new StackPane[SIZE][SIZE];


    private Piece selectedPiece = null;
    private List<Move> validMoves = new ArrayList<>();
    private PieceColor currentTurn = PieceColor.WHITE;

    private MoveCallback onMoveAttempt;
    private SelectionCallback onPieceSelected;
    private boolean animating = false;
    private Runnable onAnimationFinished;


    public CheckersBoard() {
        setHgap(0);
        setVgap(0);
        setAlignment(Pos.CENTER);
        buildBoard();
        initializePieces();

        widthProperty().addListener((obs, oldVal, newVal) -> resizeBoard());
        heightProperty().addListener((obs, oldVal, newVal) -> resizeBoard());
    }

    /**
     * Створення дошки
     */
    private void buildBoard() {
        getChildren().clear();

        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                StackPane cell = createCell(row, col);
                cells[row][col] = cell;
                add(cell, col, row);
            }
        }
    }

    public void setOnAnimationFinished(Runnable r) {
        this.onAnimationFinished = r;
    }

    /**
     * Створення окремої клітинки
     */
    private StackPane createCell(int row, int col) {
        StackPane cell = new StackPane();

        // Стилі для світлих/темних клітинок
        boolean isDark = (row + col) % 2 != 0;
        cell.getStyleClass().add(isDark ? "cell-dark" : "cell-light");

        // Обробка кліків
        final int finalRow = row;
        final int finalCol = col;

        cell.setOnMouseClicked(e -> handleCellClick(finalRow, finalCol));

        // Hover ефект тільки для темних клітинок
        if (isDark) {
            cell.setOnMouseEntered(e -> {
                if (selectedPiece != null && isValidMoveTarget(finalRow, finalCol)) {
                    highlightCell(cell, true);
                }
            });

            cell.setOnMouseExited(e -> {
                highlightCell(cell, false);
            });
        }

        return cell;
    }

    /**
     * Підсвітка клітинки
     */
    private void highlightCell(StackPane cell, boolean highlight) {
        if (highlight) {
            cell.setStyle(cell.getStyle() + "-fx-background-color: rgba(255, 255, 0, 0.3);");
        } else {
            cell.setStyle("");
        }
    }

    /**
     * Ініціалізація шашок на початкових позиціях
     */
    private void initializePieces() {
        // Чорні шашки (верх дошки)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < SIZE; col++) {
                if ((row + col) % 2 != 0) {
                    addPiece(PieceType.BLACK_PIECE, row, col);
                }
            }
        }

        // Білі шашки (низ дошки)
        for (int row = SIZE - 3; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if ((row + col) % 2 != 0) {
                    addPiece(PieceType.WHITE_PIECE, row, col);
                }
            }
        }

        // Оновлюємо розміри після створення
        resizeBoard();
    }

    /**
     * Додати шашку на дошку
     */
    private void addPiece(PieceType type, int row, int col) {
        if (type == PieceType.EMPTY) return;

        PieceColor color = PieceColor.fromPieceType(type);
        Piece piece = new Piece(type, color, row, col);
        pieces[row][col] = piece;

        StackPane cell = cells[row][col];
        cell.getChildren().add(piece);

        // Обробка кліків на шашку
        piece.setOnMouseClicked(e -> {
            e.consume();
            handlePieceClick(piece);
        });

        // Hover ефект
        piece.setOnMouseEntered(e -> {
            if (canSelectPiece(piece)) {
                piece.setHovered(true);
            }
        });

        piece.setOnMouseExited(e -> piece.setHovered(false));
    }

    /**
     * Обробка кліку на клітинку
     */
    private void handleCellClick(int row, int col) {
        // Якщо є вибрана шашка - спроба ходу
        if (selectedPiece != null) {
            Move move = findMove(selectedPiece.getRow(), selectedPiece.getCol(), row, col);
            if (move != null) {
                attemptMove(move);
            } else {
                deselectPiece();
            }
        }
    }

    /**
     * Обробка кліку на шашку
     */
    private void handlePieceClick(Piece piece) {
        // Якщо ця шашка вже вибрана - зняти виділення
        if (piece == selectedPiece) {
            deselectPiece();
            return;
        }

        // Якщо можна вибрати цю шашку
        if (canSelectPiece(piece)) {
            selectPiece(piece);
        }
        // Якщо є вибрана шашка і клікнули на іншу - спроба з'їсти
        else if (selectedPiece != null) {
            deselectPiece();
        }
    }

    /**
     * Чи можна вибрати цю шашку
     */
    private boolean canSelectPiece(Piece piece) {
        return piece != null && piece.getColor() == currentTurn;
    }

    /**
     * Вибрати шашку
     */
    private void selectPiece(Piece piece) {
        // Зняти попереднє виділення
        if (selectedPiece != null) {
            selectedPiece.setSelected(false);
            clearHighlights();
        }

        selectedPiece = piece;
        piece.setSelected(true);

        // Знайти валідні ходи для цієї шашки
        validMoves = getValidMovesForPiece(piece);

        // Підсвітити можливі ходи
        highlightValidMoves();

        // Callback
        if (onPieceSelected != null) {
            onPieceSelected.onPieceSelected(piece);
        }
    }

    /**
     * Зняти виділення з шашки
     */
    private void deselectPiece() {
        if (selectedPiece != null) {
            selectedPiece.setSelected(false);
            selectedPiece = null;
        }
        validMoves.clear();
        clearHighlights();
    }

    /**
     * Підсвітити валідні ходи
     */
    private void highlightValidMoves() {
        for (Move move : validMoves) {
            StackPane targetCell = cells[move.getToRow()][move.getToCol()];

            // Додаємо візуальний індикатор можливого ходу
            Rectangle highlight = new Rectangle();
            highlight.setFill(move.isCapture() ?
                    Color.rgb(255, 0, 0, 0.3) :  // Червоний для захоплення
                    Color.rgb(0, 255, 0, 0.3));   // Зелений для звичайного ходу
            highlight.widthProperty().bind(targetCell.widthProperty());
            highlight.heightProperty().bind(targetCell.heightProperty());
            highlight.getStyleClass().add("move-highlight");

            targetCell.getChildren().add(0, highlight); // Додати під шашку
        }
    }

    /**
     * Очистити підсвітку
     */
    private void clearHighlights() {
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                StackPane cell = cells[row][col];
                cell.getChildren().removeIf(node ->
                        node.getStyleClass().contains("move-highlight"));
            }
        }
    }

    /**
     * Знайти хід в списку валідних ходів
     */
    private Move findMove(int fromRow, int fromCol, int toRow, int toCol) {
        for (Move move : validMoves) {
            if (move.getFromRow() == fromRow && move.getFromCol() == fromCol &&
                    move.getToRow() == toRow && move.getToCol() == toCol) {
                return move;
            }
        }
        return null;
    }

    /**
     * Чи є клітинка валідною ціллю для ходу
     */
    private boolean isValidMoveTarget(int row, int col) {
        for (Move move : validMoves) {
            if (move.getToRow() == row && move.getToCol() == col) {
                return true;
            }
        }
        return false;
    }

    /**
     * Спроба виконати хід
     */
    private void attemptMove(Move move) {
        if (onMoveAttempt != null) {
            // Callback для обробки ходу (відправка на сервер)
            onMoveAttempt.onMove(move);
        } else {
            // Локальне виконання ходу (для тестування)
            executeMove(move);
        }
    }

    /**
     * Виконати хід на дошці (з анімацією)
     */
    public void executeMove(Move move) {
        if (animating) return;

        animating = true;

        Piece piece = pieces[move.getFromRow()][move.getFromCol()];
        if (piece == null) {
            animating = false;
            return;
        }

        animateMove(move, () -> {

            if (move.isCapture()) {
                Position captured = move.getCapturedPosition();
                if (captured != null) {
                    removePiece(captured.getRow(), captured.getCol());
                }
            }

            if (move.promotesToKing()) {
                piece.promoteToKing();
            }

            currentTurn = currentTurn.opposite();
            deselectPiece();

            animating = false;

            if (onAnimationFinished != null) {
                onAnimationFinished.run();
            }
        });

    }


    /**
     * Анімація ходу
     */
    private void animateMove(Move move, Runnable onComplete) {
        Piece piece = pieces[move.getFromRow()][move.getFromCol()];
        if (piece == null) return;

        StackPane fromCell = cells[move.getFromRow()][move.getFromCol()];
        StackPane toCell = cells[move.getToRow()][move.getToCol()];

        double dx = (move.getToCol() - move.getFromCol()) * cellSize;
        double dy = (move.getToRow() - move.getFromRow()) * cellSize;

        TranslateTransition translate = new TranslateTransition(Duration.millis(400), piece);
        translate.setByX(dx);
        translate.setByY(dy);

        translate.setOnFinished(e -> {
            // Переміщення в моделі
            pieces[move.getFromRow()][move.getFromCol()] = null;
            pieces[move.getToRow()][move.getToCol()] = piece;
            piece.setPosition(move.getToRow(), move.getToCol());

            // Переміщення в UI
            fromCell.getChildren().remove(piece);
            piece.setTranslateX(0);
            piece.setTranslateY(0);
            toCell.getChildren().add(piece);

            if (onComplete != null) {
                onComplete.run();
            }
        });

        translate.play();
    }

    /**
     * Видалити шашку з дошки
     */
    private void removePiece(int row, int col) {
        Piece piece = pieces[row][col];
        if (piece != null) {
            StackPane cell = cells[row][col];

            // Анімація зникнення
            FadeTransition fade = new FadeTransition(Duration.millis(300), piece);
            fade.setToValue(0);
            fade.setOnFinished(e -> cell.getChildren().remove(piece));
            fade.play();

            pieces[row][col] = null;
        }
    }

    /**
     * Отримати валідні ходи для шашки
     */
    private List<Move> getValidMovesForPiece(Piece piece) {
        List<Move> moves = new ArrayList<>();
        int row = piece.getRow();
        int col = piece.getCol();

        // ✅ Визначити напрямок руху на основі позиції, а не кольору
        int[] directions;
        if (piece.isKing()) {
            directions = new int[]{-1, 1};  // Дамка йде в обидва боки
        } else {
            // Звичайна шашка завжди йде "вперед" (до рядка 0)
            // Незалежно від кольору!
            directions = new int[]{-1};  // ✅ Завжди вгору після ротації
        }

        for (int dRow : directions) {
            for (int dCol : new int[]{-1, 1}) {
                // Звичайний хід
                int newRow = row + dRow;
                int newCol = col + dCol;

                if (isValidPosition(newRow, newCol) && pieces[newRow][newCol] == null) {
                    boolean promotesToKing = checkPromotion(piece, newRow);
                    moves.add(new Move(row, col, newRow, newCol, MoveType.NORMAL, promotesToKing));
                }

                // Захоплення (бите)
                int jumpRow = row + dRow * 2;
                int jumpCol = col + dCol * 2;
                int midRow = row + dRow;
                int midCol = col + dCol;

                if (isValidPosition(jumpRow, jumpCol) &&
                        pieces[jumpRow][jumpCol] == null &&
                        pieces[midRow][midCol] != null &&
                        pieces[midRow][midCol].getColor() != piece.getColor()) {

                    boolean promotesToKing = checkPromotion(piece, jumpRow);
                    Move captureMove = new Move(row, col, jumpRow, jumpCol, MoveType.CAPTURE, promotesToKing);
                    captureMove.addCapturedPosition(midRow, midCol);
                    moves.add(captureMove);
                }
            }
        }

        return moves;
    }


    /**
     * Перевірка чи шашка стане дамкою після ходу
     */
    private boolean checkPromotion(Piece piece, int newRow) {
        if (piece.isKing()) return false;


        return newRow == 0;  // Завжди рядок 0 після ротації
    }

    /**
     * Перевірка чи позиція в межах дошки
     */
    private boolean isValidPosition(int row, int col) {
        return row >= 0 && row < SIZE && col >= 0 && col < SIZE;
    }

    /**
     * Оновлення розмірів дошки
     */
    private void resizeBoard() {
        double size = Math.min(getWidth(), getHeight());
        cellSize = size / SIZE;

        setPrefSize(size, size);

        for (var node : getChildren()) {
            if (node instanceof Region region) {
                region.setPrefSize(cellSize, cellSize);
            }
        }

        // Оновити розміри шашок
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if (pieces[row][col] != null) {
                    pieces[row][col].updateSize(cellSize);
                }
            }
        }
    }

    /**
     * Встановити стан дошки з масиву
     */
    public void setBoardState(int[][] boardState) {
        // Очистити поточну дошку
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if (pieces[row][col] != null) {
                    cells[row][col].getChildren().remove(pieces[row][col]);
                    pieces[row][col] = null;
                }
            }
        }

        // Встановити новий стан
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                PieceType type = PieceType.fromValue(boardState[row][col]);
                if (type != PieceType.EMPTY) {
                    addPiece(type, row, col);
                }
            }
        }

        resizeBoard();
    }

    /**
     * Отримати поточний стан дошки як масив
     */
    public int[][] getBoardState() {
        int[][] state = new int[SIZE][SIZE];
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                state[row][col] = pieces[row][col] != null ?
                        pieces[row][col].getType().getValue() : 0;
            }
        }
        return state;
    }

    // Геттери та сеттери
    public PieceColor getCurrentTurn() {
        return currentTurn;
    }

    public void setCurrentTurn(PieceColor turn) {
        this.currentTurn = turn;
        deselectPiece();
    }

    public void setOnMoveAttempt(MoveCallback callback) {
        this.onMoveAttempt = callback;
    }

    public void setOnPieceSelected(SelectionCallback callback) {
        this.onPieceSelected = callback;
    }

    public Piece getSelectedPiece() {
        return selectedPiece;
    }


    public boolean isAnimating() {
        return animating;
    }

    // Callback інтерфейси
    @FunctionalInterface
    public interface MoveCallback {
        void onMove(Move move);
    }

    @FunctionalInterface
    public interface SelectionCallback {
        void onPieceSelected(Piece piece);
    }
}