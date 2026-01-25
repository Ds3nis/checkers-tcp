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

/**
 * JavaFX component representing the visual and logical checkers board.
 *
 * This class is responsible for:
 * - Rendering the board grid and pieces
 * - Handling user interaction (clicks, selection, moves)
 * - Calculating and highlighting valid moves
 * - Animating piece movement and captures
 * - Managing turn state and board resizing
 *
 * It acts as the main bridge between UI, game logic, and (optionally) server callbacks.
 */
public class CheckersBoard extends GridPane {
    // Board size (standard 8x8 checkers board)
    private static final int SIZE = 8;

    // Current size of each cell in pixels, calculated dynamically based on window size
    private double cellSize;

    // 2D array storing piece objects at their board positions
    private Piece[][] pieces = new Piece[SIZE][SIZE];

    // 2D array storing the StackPane cells that make up the visual board
    private StackPane[][] cells = new StackPane[SIZE][SIZE];

    // Currently selected piece that the user has clicked on
    private Piece selectedPiece = null;

    // List of valid moves available for the currently selected piece
    private List<Move> validMoves = new ArrayList<>();

    // Current player's turn (WHITE or BLACK)
    private PieceColor currentTurn = PieceColor.WHITE;

    // Callback function invoked when a move is attempted (typically sends move to server)
    private MoveCallback onMoveAttempt;

    // Callback function invoked when a piece is selected
    private SelectionCallback onPieceSelected;

    // Flag indicating whether an animation is currently playing
    private boolean animating = false;

    // Callback to run after move animation completes
    private Runnable onAnimationFinished;

    // The color assigned to this client (WHITE or BLACK)
    private PieceColor myColor;

    /**
     * Constructor initializes the board structure and sets up responsive resizing.
     */
    public CheckersBoard() {
        setHgap(0);
        setVgap(0);
        setAlignment(Pos.CENTER);
        buildBoard();
        initializePieces();

        // Listen for window size changes and resize board accordingly
        widthProperty().addListener((obs, oldVal, newVal) -> resizeBoard());
        heightProperty().addListener((obs, oldVal, newVal) -> resizeBoard());
    }

    /**
     * Creates the visual board grid of 8x8 cells.
     * Each cell is a StackPane that can contain pieces and highlights.
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

    /**
     * Sets the callback to be invoked when move animation finishes.
     *
     * @param r Runnable to execute after animation completes
     */
    public void setOnAnimationFinished(Runnable r) {
        this.onAnimationFinished = r;
    }

    /**
     * Creates a single cell on the checkers board with appropriate styling and event handlers.
     *
     * @param row Row position of the cell
     * @param col Column position of the cell
     * @return StackPane representing the cell
     */
    private StackPane createCell(int row, int col) {
        StackPane cell = new StackPane();

        // Apply different styles for light and dark squares
        boolean isDark = (row + col) % 2 != 0;
        cell.getStyleClass().add(isDark ? "cell-dark" : "cell-light");

        // Handle cell clicks for move execution
        final int finalRow = row;
        final int finalCol = col;

        cell.setOnMouseClicked(e -> handleCellClick(finalRow, finalCol));

        // Add hover effect only for dark cells (playable squares)
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
     * Highlights or removes highlight from a cell.
     *
     * @param cell The cell to highlight
     * @param highlight True to add highlight, false to remove it
     */
    private void highlightCell(StackPane cell, boolean highlight) {
        if (highlight) {
            cell.setStyle(cell.getStyle() + "-fx-background-color: rgba(255, 255, 0, 0.3);");
        } else {
            cell.setStyle("");
        }
    }

    /**
     * Initializes all pieces in their starting positions.
     * Black pieces occupy the top 3 rows, white pieces occupy the bottom 3 rows.
     * Pieces are only placed on dark squares.
     */
    private void initializePieces() {
        // Black pieces (top of the board)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < SIZE; col++) {
                if ((row + col) % 2 != 0) {
                    addPiece(PieceType.BLACK_PIECE, row, col);
                }
            }
        }

        // White pieces (bottom of the board)
        for (int row = SIZE - 3; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if ((row + col) % 2 != 0) {
                    addPiece(PieceType.WHITE_PIECE, row, col);
                }
            }
        }

        // Update sizes after creation
        resizeBoard();
    }

    /**
     * Adds a piece to the board at the specified position.
     * Sets up event handlers for clicking and hovering over the piece.
     *
     * @param type Type of piece to add (BLACK_PIECE, WHITE_PIECE, etc.)
     * @param row Row position
     * @param col Column position
     */
    private void addPiece(PieceType type, int row, int col) {
        if (type == PieceType.EMPTY) return;

        PieceColor color = PieceColor.fromPieceType(type);
        Piece piece = new Piece(type, color, row, col);
        pieces[row][col] = piece;

        StackPane cell = cells[row][col];
        cell.getChildren().add(piece);

        // Handle clicks on the piece
        piece.setOnMouseClicked(e -> {
            e.consume();
            handlePieceClick(piece);
        });

        // Hover effect to indicate selectable pieces
        piece.setOnMouseEntered(e -> {
            if (canSelectPiece(piece)) {
                piece.setHovered(true);
            }
        });

        piece.setOnMouseExited(e -> piece.setHovered(false));
    }

    /**
     * Handles clicks on empty cells.
     * If a piece is selected, attempts to move it to the clicked cell.
     *
     * @param row Row of the clicked cell
     * @param col Column of the clicked cell
     */
    private void handleCellClick(int row, int col) {
        // If there's a selected piece, try to move it
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
     * Handles clicks on pieces.
     * Selects the piece if it's the player's turn and color, or deselects if already selected.
     *
     * @param piece The piece that was clicked
     */
    private void handlePieceClick(Piece piece) {
        // If this piece is already selected, deselect it
        if (piece == selectedPiece) {
            deselectPiece();
            return;
        }

        // If this piece can be selected, select it
        if (canSelectPiece(piece)) {
            selectPiece(piece);
        }
        // If another piece is selected, deselect it
        else if (selectedPiece != null) {
            deselectPiece();
        }
    }

    /**
     * Checks if a piece can be selected by the current player.
     * A piece can be selected if it matches both the current turn and the player's color.
     *
     * @param piece The piece to check
     * @return True if the piece can be selected
     */
    private boolean canSelectPiece(Piece piece) {
        return piece != null && (piece.getColor() == currentTurn && piece.getColor() == myColor);
    }

    /**
     * Selects a piece and highlights all its valid moves.
     *
     * @param piece The piece to select
     */
    private void selectPiece(Piece piece) {
        // Deselect previous piece if any
        if (selectedPiece != null) {
            selectedPiece.setSelected(false);
            clearHighlights();
        }

        selectedPiece = piece;
        piece.setSelected(true);

        // Find all valid moves for this piece
        validMoves = getValidMovesForPiece(piece);

        // Highlight possible moves on the board
        highlightValidMoves();

        // Invoke callback if set
        if (onPieceSelected != null) {
            onPieceSelected.onPieceSelected(piece);
        }
    }

    /**
     * Deselects the currently selected piece and clears move highlights.
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
     * Highlights all valid move destinations for the currently selected piece.
     * Capture moves are shown in red, regular moves in green.
     */
    private void highlightValidMoves() {
        for (Move move : validMoves) {
            StackPane targetCell = cells[move.getToRow()][move.getToCol()];

            // Add visual indicator for possible moves
            Rectangle highlight = new Rectangle();
            highlight.setFill(move.isCapture() ?
                    Color.rgb(255, 0, 0, 0.3) :  // Red for captures
                    Color.rgb(0, 255, 0, 0.3));   // Green for regular moves
            highlight.widthProperty().bind(targetCell.widthProperty());
            highlight.heightProperty().bind(targetCell.heightProperty());
            highlight.getStyleClass().add("move-highlight");

            targetCell.getChildren().add(0, highlight); // Add behind pieces
        }
    }

    /**
     * Removes all move highlight rectangles from the board.
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
     * Finds a move in the list of valid moves matching the given coordinates.
     *
     * @param fromRow Starting row
     * @param fromCol Starting column
     * @param toRow Destination row
     * @param toCol Destination column
     * @return The matching Move object, or null if not found
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
     * Checks if a cell is a valid move destination for the selected piece.
     *
     * @param row Row to check
     * @param col Column to check
     * @return True if the cell is a valid move target
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
     * Attempts to execute a move by invoking the move callback.
     * If no callback is set, executes the move locally (for testing).
     *
     * @param move The move to attempt
     */
    private void attemptMove(Move move) {
        if (onMoveAttempt != null) {
            // Callback for handling the move (typically sends to server)
            onMoveAttempt.onMove(move);
        } else {
            // Local execution for testing
            executeMove(move);
        }
    }

    /**
     * Executes a move on the board with animation.
     * Removes captured pieces, promotes to king if applicable, and switches turns.
     *
     * @param move The move to execute
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
            // Remove all captured pieces
            if (move.isCapture()) {
                for (Position captured : move.getCapturedPositions()) {
                    removePiece(captured.getRow(), captured.getCol());
                }
            }

            // Promote to king if move reaches the opposite end
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
     * Animates a piece moving from one position to another.
     *
     * @param move The move to animate
     * @param onComplete Callback to run when animation finishes
     */
    private void animateMove(Move move, Runnable onComplete) {
        Piece piece = pieces[move.getFromRow()][move.getFromCol()];
        if (piece == null) return;

        StackPane fromCell = cells[move.getFromRow()][move.getFromCol()];
        StackPane toCell = cells[move.getToRow()][move.getToCol()];

        // Calculate pixel distance to move
        double dx = (move.getToCol() - move.getFromCol()) * cellSize;
        double dy = (move.getToRow() - move.getFromRow()) * cellSize;

        TranslateTransition translate = new TranslateTransition(Duration.millis(400), piece);
        translate.setByX(dx);
        translate.setByY(dy);

        translate.setOnFinished(e -> {
            // Update model
            pieces[move.getFromRow()][move.getFromCol()] = null;
            pieces[move.getToRow()][move.getToCol()] = piece;
            piece.setPosition(move.getToRow(), move.getToCol());

            // Update UI - move piece to new cell
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
     * Removes a piece from the board with a fade-out animation.
     *
     * @param row Row of the piece to remove
     * @param col Column of the piece to remove
     */
    private void removePiece(int row, int col) {
        Piece piece = pieces[row][col];
        if (piece != null) {
            StackPane cell = cells[row][col];

            // Fade out animation
            FadeTransition fade = new FadeTransition(Duration.millis(300), piece);
            fade.setToValue(0);
            fade.setOnFinished(e -> cell.getChildren().remove(piece));
            fade.play();

            pieces[row][col] = null;
        }
    }

    /**
     * Calculates all valid moves for a given piece.
     * If capture moves are available, only capture moves are returned (mandatory capture rule).
     *
     * @param piece The piece to calculate moves for
     * @return List of valid moves
     */
    private List<Move> getValidMovesForPiece(Piece piece) {
        List<Move> moves = new ArrayList<>();
        int row = piece.getRow();
        int col = piece.getCol();

        if (piece.isKing()) {
            addKingMoves(piece, row, col, moves);
        } else {
            addRegularPieceMoves(piece, row, col, moves);
        }

        // If captures are possible, return only captures (mandatory capture rule)
        List<Move> captureMoves = moves.stream()
                .filter(Move::isCapture)
                .collect(Collectors.toList());

        if (!captureMoves.isEmpty()) {
            return captureMoves;
        }

        return moves;
    }

    /**
     * Adds all possible moves for a regular (non-king) piece.
     * Regular pieces can move forward diagonally and must capture when possible.
     *
     * @param piece The piece to calculate moves for
     * @param row Current row
     * @param col Current column
     * @param moves List to add moves to
     */
    private void addRegularPieceMoves(Piece piece, int row, int col, List<Move> moves) {
        int direction = -1; // Always upward after rotation

        for (int dCol : new int[]{-1, 1}) {
            // Regular move (one square diagonally forward)
            int newRow = row + direction;
            int newCol = col + dCol;

            if (isValidPosition(newRow, newCol) && pieces[newRow][newCol] == null) {
                boolean promotesToKing = checkPromotion(piece, newRow);
                moves.add(new Move(row, col, newRow, newCol, MoveType.NORMAL, promotesToKing));
            }
        }

        // Explore all possible multi-jump capture sequences
        List<Position> emptyPath = new ArrayList<>();
        emptyPath.add(new Position(row, col));
        exploreCaptures(piece, row, col, new boolean[SIZE][SIZE], new ArrayList<>(), emptyPath, moves);
    }

    /**
     * Recursively explores all possible capture sequences for a piece.
     * This handles multi-jump captures where a piece can capture multiple opponents in one turn.
     *
     * @param piece The piece making captures
     * @param row Current row position in the sequence
     * @param col Current column position in the sequence
     * @param visited Array tracking visited positions to prevent cycles
     * @param capturedSoFar List of pieces captured in this sequence
     * @param pathSoFar Path of positions visited in this capture sequence
     * @param allMoves List to add complete capture moves to
     */
    private void exploreCaptures(Piece piece, int row, int col, boolean[][] visited,
                                 List<Position> capturedSoFar, List<Position> pathSoFar,
                                 List<Move> allMoves) {
        boolean foundFurtherCapture = false;
        int[] rowDirs = piece.isKing() ? new int[]{-1, 1} : new int[]{-1, 1};

        for (int dRow : rowDirs) {
            for (int dCol : new int[]{-1, 1}) {
                int jumpRow = row + dRow * 2;
                int jumpCol = col + dCol * 2;
                int midRow = row + dRow;
                int midCol = col + dCol;

                // Check if this is a valid capture: empty landing square, not visited,
                // enemy piece in between, and that enemy hasn't been captured yet
                if (isValidPosition(jumpRow, jumpCol) &&
                        pieces[jumpRow][jumpCol] == null &&
                        !visited[jumpRow][jumpCol] &&
                        pieces[midRow][midCol] != null &&
                        pieces[midRow][midCol].getColor() != piece.getColor() &&
                        !isCaptured(midRow, midCol, capturedSoFar)) {

                    foundFurtherCapture = true;
                    visited[jumpRow][jumpCol] = true;

                    // Build new state for recursive call
                    List<Position> newCaptured = new ArrayList<>(capturedSoFar);
                    newCaptured.add(new Position(midRow, midCol));

                    List<Position> newPath = new ArrayList<>(pathSoFar);
                    newPath.add(new Position(jumpRow, jumpCol));

                    // Recursively look for more captures from the new position
                    exploreCaptures(piece, jumpRow, jumpCol, visited, newCaptured, newPath, allMoves);

                    visited[jumpRow][jumpCol] = false;
                }
            }
        }

        // If no further captures possible and we've captured at least one piece,
        // this is a complete capture sequence - add it to the moves list
        if (!foundFurtherCapture && !capturedSoFar.isEmpty()) {
            MoveType type = capturedSoFar.size() > 1 ? MoveType.MULTI_CAPTURE : MoveType.CAPTURE;
            Move move = new Move(piece.getRow(), piece.getCol(), row, col, type,
                    checkPromotion(piece, row));

            for (Position pos : capturedSoFar) {
                move.addCapturedPosition(pos.getRow(), pos.getCol());
            }

            for (Position pathPos : pathSoFar) {
                move.addPathPosition(pathPos.getRow(), pathPos.getCol());
            }

            allMoves.add(move);

            System.out.println("Created capture move:");
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

    /**
     * Checks if a position has already been captured in the current capture sequence.
     *
     * @param row Row to check
     * @param col Column to check
     * @param captured List of already captured positions
     * @return True if this position was already captured
     */
    private boolean isCaptured(int row, int col, List<Position> captured) {
        for (Position pos : captured) {
            if (pos.getRow() == row && pos.getCol() == col) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds all possible moves for a king piece.
     * Kings can move multiple squares diagonally in any direction and capture over distance.
     *
     * @param piece The king piece
     * @param row Current row
     * @param col Current column
     * @param moves List to add moves to
     */
    private void addKingMoves(Piece piece, int row, int col, List<Move> moves) {
        // Kings can move in all 4 diagonal directions
        int[][] directions = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

        for (int[] dir : directions) {
            int dRow = dir[0];
            int dCol = dir[1];

            // Regular moves (until first obstacle)
            for (int distance = 1; distance < SIZE; distance++) {
                int newRow = row + dRow * distance;
                int newCol = col + dCol * distance;

                if (!isValidPosition(newRow, newCol)) break;

                if (pieces[newRow][newCol] == null) {
                    // Empty square - can move here
                    moves.add(new Move(row, col, newRow, newCol, MoveType.NORMAL, false));
                } else {
                    // Found a piece - check if we can capture it
                    if (pieces[newRow][newCol].getColor() != piece.getColor()) {
                        // It's an opponent - check for space beyond to land
                        addKingCaptureMove(piece, row, col, newRow, newCol, dRow, dCol, moves);
                    }
                    break; // Stop at first piece encountered
                }
            }
        }

        // Explore multi-jump capture sequences for kings
        List<Position> initialPath = new ArrayList<>();
        initialPath.add(new Position(row, col));

        exploreKingCaptures(piece, row, col, new boolean[SIZE][SIZE], new ArrayList<>(), initialPath, moves);
    }

    /**
     * Recursively explores all possible capture sequences for a king.
     * Kings can capture pieces at a distance and land multiple squares beyond them.
     *
     * @param piece The king making captures
     * @param row Current row position
     * @param col Current column position
     * @param visited Array tracking visited positions
     * @param capturedSoFar List of captured pieces in this sequence
     * @param pathSoFar Complete path of positions in this capture sequence
     * @param allMoves List to add complete capture moves to
     */
    private void exploreKingCaptures(Piece piece, int row, int col,
                                     boolean[][] visited, List<Position> capturedSoFar,
                                     List<Position> pathSoFar, List<Move> allMoves) {
        boolean foundCapture = false;
        int[][] directions = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

        for (int[] dir : directions) {
            int dRow = dir[0];
            int dCol = dir[1];

            // Look for enemy pieces along this diagonal
            for (int distance = 1; distance < SIZE; distance++) {
                int enemyRow = row + dRow * distance;
                int enemyCol = col + dCol * distance;

                if (!isValidPosition(enemyRow, enemyCol)) break;

                if (pieces[enemyRow][enemyCol] != null) {
                    // Found a piece - check if it's an uncaptured enemy
                    if (pieces[enemyRow][enemyCol].getColor() != piece.getColor() &&
                            !isCaptured(enemyRow, enemyCol, capturedSoFar)) {

                        // Look for all possible landing squares beyond the enemy piece
                        for (int landDistance = 1; landDistance < SIZE; landDistance++) {
                            int landRow = enemyRow + dRow * landDistance;
                            int landCol = enemyCol + dCol * landDistance;

                            if (!isValidPosition(landRow, landCol)) break;

                            if (pieces[landRow][landCol] == null && !visited[landRow][landCol]) {
                                // Valid landing square found
                                foundCapture = true;
                                visited[landRow][landCol] = true;

                                List<Position> newCaptured = new ArrayList<>(capturedSoFar);
                                newCaptured.add(new Position(enemyRow, enemyCol));

                                List<Position> newPath = new ArrayList<>(pathSoFar);
                                newPath.add(new Position(landRow, landCol));

                                // Recursively look for more captures from the landing position
                                exploreKingCaptures(piece, landRow, landCol, visited, newCaptured, newPath, allMoves);

                                visited[landRow][landCol] = false;
                            } else if (pieces[landRow][landCol] != null) {
                                break; // Hit another piece, can't land beyond it
                            }
                        }
                    }
                    break; // Stop at first piece in this direction
                }
            }
        }

        // If no further captures and we've captured at least one piece,
        // add this complete capture sequence to the moves list
        if (!foundCapture && !capturedSoFar.isEmpty()) {
            MoveType type = capturedSoFar.size() > 1 ? MoveType.MULTI_CAPTURE : MoveType.CAPTURE;
            Move move = new Move(piece.getRow(), piece.getCol(), row, col, type, false);

            for (Position pos : capturedSoFar) {
                move.addCapturedPosition(pos.getRow(), pos.getCol());
            }

            for (Position pathPos : pathSoFar) {
                move.addPathPosition(pathPos.getRow(), pathPos.getCol());
            }

            allMoves.add(move);

            System.out.println("Created king capture move:");
            System.out.println("   Path: " + pathSoFar.size() + " positions");
        }
    }

    /**
     * Adds a single capture move for a king where it captures one piece and lands beyond it.
     *
     * @param piece The king piece
     * @param fromRow Starting row
     * @param fromCol Starting column
     * @param enemyRow Row of enemy piece to capture
     * @param enemyCol Column of enemy piece to capture
     * @param dRow Row direction (-1 or 1)
     * @param dCol Column direction (-1 or 1)
     * @param moves List to add moves to
     */
    private void addKingCaptureMove(Piece piece, int fromRow, int fromCol,
                                    int enemyRow, int enemyCol, int dRow, int dCol,
                                    List<Move> moves) {
        // Find all possible landing positions beyond the enemy piece
        for (int distance = 1; distance < SIZE; distance++) {
            int landRow = enemyRow + dRow * distance;
            int landCol = enemyCol + dCol * distance;

            if (!isValidPosition(landRow, landCol)) break;

            if (pieces[landRow][landCol] == null) {
                Move move = new Move(fromRow, fromCol, landRow, landCol, MoveType.CAPTURE, false);
                move.addCapturedPosition(enemyRow, enemyCol);
                moves.add(move);
            } else {
                break; // Hit another piece
            }
        }
    }

    /**
     * Checks if a piece should be promoted to king after moving to a new row.
     * Regular pieces promote when reaching the opposite end of the board.
     *
     * @param piece The piece to check
     * @param newRow The row the piece is moving to
     * @return True if the piece should be promoted
     */
    private boolean checkPromotion(Piece piece, int newRow) {
        if (piece.isKing()) return false;
        return newRow == 0;
    }

    /**
     * Checks if a position is within the board boundaries.
     *
     * @param row Row to check
     * @param col Column to check
     * @return True if position is valid
     */
    private boolean isValidPosition(int row, int col) {
        return row >= 0 && row < SIZE && col >= 0 && col < SIZE;
    }

    /**
     * Resizes the board and all its elements based on available space.
     * Maintains square aspect ratio and updates all piece sizes.
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

        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if (pieces[row][col] != null) {
                    pieces[row][col].updateSize(cellSize);
                }
            }
        }
    }

    /**
     * Sets the board state from a 2D array representation.
     * Clears existing pieces and creates new ones based on the provided state.
     *
     * @param boardState 2D array where each value represents a piece type
     */
    public void setBoardState(int[][] boardState) {
        // Clear current board
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if (pieces[row][col] != null) {
                    cells[row][col].getChildren().remove(pieces[row][col]);
                    pieces[row][col] = null;
                }
            }
        }

        // Set new state
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
     * Gets the current board state as a 2D array.
     * Each element contains the piece type value at that position.
     *
     * @return 2D array representing the board state
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

    /**
     * Gets the current turn color.
     *
     * @return PieceColor representing whose turn it is
     */
    public PieceColor getCurrentTurn() {
        return currentTurn;
    }

    /**
     * Sets the current turn and deselects any selected piece.
     *
     * @param turn The color whose turn it is
     */
    public void setCurrentTurn(PieceColor turn) {
        this.currentTurn = turn;
        deselectPiece();
    }

    /**
     * Sets the callback to be invoked when a move is attempted.
     *
     * @param callback Callback function to handle move attempts
     */
    public void setOnMoveAttempt(MoveCallback callback) {
        this.onMoveAttempt = callback;
    }

    /**
     * Sets the callback to be invoked when a piece is selected.
     *
     * @param callback Callback function to handle piece selection
     */
    public void setOnPieceSelected(SelectionCallback callback) {
        this.onPieceSelected = callback;
    }

    /**
     * Sets the color assigned to this player.
     * Only pieces of this color can be selected and moved.
     *
     * @param myColor The player's assigned color
     */
    public void setMyColor(PieceColor myColor) {
        this.myColor = myColor;
    }

    /**
     * Gets the currently selected piece.
     *
     * @return The selected piece, or null if none selected
     */
    public Piece getSelectedPiece() {
        return selectedPiece;
    }

    /**
     * Checks if an animation is currently playing.
     *
     * @return True if animating
     */
    public boolean isAnimating() {
        return animating;
    }

    /**
     * Callback interface for handling move attempts.
     * Typically implemented to send moves to the server.
     */
    @FunctionalInterface
    public interface MoveCallback {
        void onMove(Move move);
    }

    /**
     * Callback interface for handling piece selection events.
     */
    @FunctionalInterface
    public interface SelectionCallback {
        void onPieceSelected(Piece piece);
    }
}