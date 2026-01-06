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
import java.util.stream.Collectors;

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
    private PieceColor myColor;


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
        return piece != null && (piece.getColor() == currentTurn && piece.getColor() == myColor);
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
            // Видалити всі з'їдені шашки
            if (move.isCapture()) {
                for (Position captured : move.getCapturedPositions()) {
                    removePiece(captured.getRow(), captured.getCol());
                }
            }

            // Промоція в дамку
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

    private List<Move> getValidMovesForPiece(Piece piece) {
        List<Move> moves = new ArrayList<>();
        int row = piece.getRow();
        int col = piece.getCol();

        if (piece.isKing()) {
            addKingMoves(piece, row, col, moves);
        } else {
            addRegularPieceMoves(piece, row, col, moves);
        }
        // Якщо є можливість биття - залишити тільки биття (обов'язкове биття)
        List<Move> captureMoves = moves.stream()
                .filter(Move::isCapture)
                .collect(Collectors.toList());

        if (!captureMoves.isEmpty()) {
            return captureMoves;
        }

        return moves;
    }


    private void addRegularPieceMoves(Piece piece, int row, int col, List<Move> moves) {
        int direction = -1; // Завжди вгору після ротації

        for (int dCol : new int[]{-1, 1}) {
            // Звичайний хід
            int newRow = row + direction;
            int newCol = col + dCol;

            if (isValidPosition(newRow, newCol) && pieces[newRow][newCol] == null) {
                boolean promotesToKing = checkPromotion(piece, newRow);
                moves.add(new Move(row, col, newRow, newCol, MoveType.NORMAL, promotesToKing));
            }

            // Перевірка множинного биття
        }

        List<Position> emptyPath = new ArrayList<>();
        emptyPath.add(new Position(row, col));
        exploreCaptures(piece, row, col, new boolean[SIZE][SIZE], new ArrayList<>(),emptyPath, moves);
    }

    /**
     * Рекурсивний пошук множинного биття
     */


    /**
     * Перевірка чи позиція вже з'їдена в поточному ланцюжку
     */
    private boolean isCaptured(int row, int col, List<Position> captured) {
        for (Position pos : captured) {
            if (pos.getRow() == row && pos.getCol() == col) {
                return true;
            }
        }
        return false;
    }

    private void addKingMoves(Piece piece, int row, int col, List<Move> moves) {
        // Дамка може ходити в 4 напрямки
        int[][] directions = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

        for (int[] dir : directions) {
            int dRow = dir[0];
            int dCol = dir[1];

            // Звичайні ходи (до першої перешкоди)
            for (int distance = 1; distance < SIZE; distance++) {
                int newRow = row + dRow * distance;
                int newCol = col + dCol * distance;

                if (!isValidPosition(newRow, newCol)) break;

                if (pieces[newRow][newCol] == null) {
                    // Порожня клітинка - можна піти
                    moves.add(new Move(row, col, newRow, newCol, MoveType.NORMAL, false));
                } else {
                    // Зустріли шашку - перевірити чи можна з'їсти
                    if (pieces[newRow][newCol].getColor() != piece.getColor()) {
                        // Це супротивник - перевірити чи є місце за ним
                        addKingCaptureMove(piece, row, col, newRow, newCol, dRow, dCol, moves);
                    }
                    break; // Зупинитися на першій шашці
                }
            }
        }

        // ✅ ВИПРАВЛЕНО: Додати початковий шлях для дамки
        List<Position> initialPath = new ArrayList<>();
        initialPath.add(new Position(row, col));

        exploreKingCaptures(piece, row, col, new boolean[SIZE][SIZE], new ArrayList<>(), initialPath, moves);
    }


    private void exploreKingCaptures(Piece piece, int row, int col,
                                     boolean[][] visited, List<Position> capturedSoFar,
                                     List<Position> pathSoFar, List<Move> allMoves) {
        boolean foundCapture = false;
        int[][] directions = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

        for (int[] dir : directions) {
            int dRow = dir[0];
            int dCol = dir[1];

            for (int distance = 1; distance < SIZE; distance++) {
                int enemyRow = row + dRow * distance;
                int enemyCol = col + dCol * distance;

                if (!isValidPosition(enemyRow, enemyCol)) break;

                if (pieces[enemyRow][enemyCol] != null) {
                    if (pieces[enemyRow][enemyCol].getColor() != piece.getColor() &&
                            !isCaptured(enemyRow, enemyCol, capturedSoFar)) {

                        for (int landDistance = 1; landDistance < SIZE; landDistance++) {
                            int landRow = enemyRow + dRow * landDistance;
                            int landCol = enemyCol + dCol * landDistance;

                            if (!isValidPosition(landRow, landCol)) break;

                            if (pieces[landRow][landCol] == null && !visited[landRow][landCol]) {
                                foundCapture = true;
                                visited[landRow][landCol] = true;

                                List<Position> newCaptured = new ArrayList<>(capturedSoFar);
                                newCaptured.add(new Position(enemyRow, enemyCol));

                                List<Position> newPath = new ArrayList<>(pathSoFar);
                                newPath.add(new Position(landRow, landCol));

                                exploreKingCaptures(piece, landRow, landCol, visited, newCaptured, newPath, allMoves);

                                visited[landRow][landCol] = false;
                            } else if (pieces[landRow][landCol] != null) {
                                break;
                            }
                        }
                    }
                    break;
                }
            }
        }

        if (!foundCapture && !capturedSoFar.isEmpty()) {
            MoveType type = capturedSoFar.size() > 1 ? MoveType.MULTI_CAPTURE : MoveType.CAPTURE;
            Move move = new Move(piece.getRow(), piece.getCol(), row, col, type, false);

            for (Position pos : capturedSoFar) {
                move.addCapturedPosition(pos.getRow(), pos.getCol());
            }

            // ✅ КОПІЮВАТИ ШЛЯХ З РЕКУРСІЇ
            for (Position pathPos : pathSoFar) {
                move.addPathPosition(pathPos.getRow(), pathPos.getCol());
            }

            allMoves.add(move);

            System.out.println("✅ Created king capture move:");
            System.out.println("   Path: " + pathSoFar.size() + " positions");
        }
    }

    /**
     * Додати хід з битням для дамки
     */
    private void addKingCaptureMove(Piece piece, int fromRow, int fromCol,
                                    int enemyRow, int enemyCol, int dRow, int dCol,
                                    List<Move> moves) {
        // Знайти всі можливі позиції за супротивником
        for (int distance = 1; distance < SIZE; distance++) {
            int landRow = enemyRow + dRow * distance;
            int landCol = enemyCol + dCol * distance;

            if (!isValidPosition(landRow, landCol)) break;

            if (pieces[landRow][landCol] == null) {
                Move move = new Move(fromRow, fromCol, landRow, landCol, MoveType.CAPTURE, false);
                move.addCapturedPosition(enemyRow, enemyCol);
                moves.add(move);
            } else {
                break; // Зустріли іншу шашку
            }
        }
    }

    private void exploreCaptures(Piece piece, int row, int col, boolean[][] visited,
                                 List<Position> capturedSoFar, List<Position> pathSoFar,
                                 List<Move> allMoves) {
        boolean foundFurtherCapture = false;
        int[] rowDirs = piece.isKing() ? new int[]{-1, 1} : new int[]{-1,1};

        for (int dRow : rowDirs) {
            for (int dCol : new int[]{-1, 1}) {
                int jumpRow = row + dRow * 2;
                int jumpCol = col + dCol * 2;
                int midRow = row + dRow;
                int midCol = col + dCol;

                if (isValidPosition(jumpRow, jumpCol) &&
                        pieces[jumpRow][jumpCol] == null &&
                        !visited[jumpRow][jumpCol] &&
                        pieces[midRow][midCol] != null &&
                        pieces[midRow][midCol].getColor() != piece.getColor() &&
                        !isCaptured(midRow, midCol, capturedSoFar)) {

                    foundFurtherCapture = true;
                    visited[jumpRow][jumpCol] = true;

                    // ✅ ВИДАЛЕНО дублювання single capture
                    // Тепер ходи додаються ТІЛЬКИ в кінці ланцюжка

                    // Рекурсія з оновленим шляхом
                    List<Position> newCaptured = new ArrayList<>(capturedSoFar);
                    newCaptured.add(new Position(midRow, midCol));

                    List<Position> newPath = new ArrayList<>(pathSoFar);
                    newPath.add(new Position(jumpRow, jumpCol));

                    exploreCaptures(piece, jumpRow, jumpCol, visited, newCaptured, newPath, allMoves);

                    visited[jumpRow][jumpCol] = false;
                }
            }
        }

        // ✅ Створити хід ТІЛЬКИ якщо це кінець ланцюжка
        if (!foundFurtherCapture && !capturedSoFar.isEmpty()) {
            MoveType type = capturedSoFar.size() > 1 ? MoveType.MULTI_CAPTURE : MoveType.CAPTURE;
            Move move = new Move(piece.getRow(), piece.getCol(), row, col, type,
                    checkPromotion(piece, row));

            // Додати всі захоплені позиції
            for (Position pos : capturedSoFar) {
                move.addCapturedPosition(pos.getRow(), pos.getCol());
            }

            // ✅ КОПІЮВАТИ ТОЧНИЙ ШЛЯХ З РЕКУРСІЇ (вже містить початок!)
            for (Position pathPos : pathSoFar) {
                move.addPathPosition(pathPos.getRow(), pathPos.getCol());
            }

            allMoves.add(move);

            System.out.println("✅ Created capture move:");
            System.out.println("   From: (" + piece.getRow() + "," + piece.getCol() + ")");
            System.out.println("   To: (" + row + "," + col + ")");
            System.out.println("   Captured: " + capturedSoFar.size() + " pieces");
            System.out.println("   Path: " + pathSoFar.size() + " positions");
            for (int i = 0; i < pathSoFar.size(); i++) {
                Position p = pathSoFar.get(i);
                System.out.println("      [" + i + "] (" + p.getRow() + "," + p.getCol() + ")");
            }
        }
    }


    private boolean checkPromotion(Piece piece, int newRow) {
        if (piece.isKing()) return false;
        return newRow == 0;
    }


    private boolean isValidPosition(int row, int col) {
        return row >= 0 && row < SIZE && col >= 0 && col < SIZE;
    }


    private void resizeBoard() {
        double size = Math.min(getWidth(), getHeight());
        cellSize = size / SIZE;

        setPrefSize(size, size);

        for (var node : getChildren()) {
            if (node instanceof Region region) {
                region.setPrefSize(cellSize, cellSize);
            }
        }

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

    public void setMyColor(PieceColor myColor) {
        this.myColor = myColor;
    }

    public Piece getSelectedPiece() {
        return selectedPiece;
    }

    public boolean isAnimating() {
        return animating;
    }

    @FunctionalInterface
    public interface MoveCallback {
        void onMove(Move move);
    }

    @FunctionalInterface
    public interface SelectionCallback {
        void onPieceSelected(Piece piece);
    }
}