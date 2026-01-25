package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.GameInfoPanel;
import com.checkerstcp.checkerstcp.Main;
import com.checkerstcp.checkerstcp.PieceColor;
import com.checkerstcp.checkerstcp.PlayerMoveItem;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.net.URL;
import java.util.*;

/**
 * Controller for the game information panel displaying player stats and move history.
 * Manages the display of player name, piece color, piece count, current status,
 * and a scrollable list of moves made during the game.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Display player information (name, color, piece count)</li>
 *   <li>Show current turn status</li>
 *   <li>Maintain scrollable move history for current player</li>
 *   <li>Update UI elements based on game state changes</li>
 *   <li>Show/hide move history based on whose turn it is</li>
 * </ul>
 *
 * <p>Thread safety: All UI updates are performed on JavaFX Application Thread
 * using Platform.runLater() to ensure safe concurrent access.
 */
public class GameInfoPanelController implements Initializable {

    @FXML
    private Label numOfPiecesLbl;

    @FXML
    private Label pieceColorLbl;

    @FXML
    private Label pieceColorNamelbl;

    @FXML
    private HBox playerInfoPanel;

    @FXML
    private Label playerMoveStatusLbl;

    @FXML
    private Label playerMovesLbl;

    @FXML
    private VBox movesCard;

    @FXML
    private Label playerNameLbl;

    @FXML
    private ScrollPane playerScrollMoves;

    private GameInfoPanel gameInfoPanel;
    private VBox listBox;

    /**
     * Initializes the controller after FXML loading.
     * Sets up the scrollable container for move history.
     *
     * @param location Location used to resolve relative paths (unused)
     * @param resources Resources used to localize root object (unused)
     */
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Initialize container for move history
        listBox = new VBox(5);
        listBox.setFillWidth(true);
        VBox.setVgrow(listBox, Priority.ALWAYS);
        playerScrollMoves.setFitToWidth(true);
        playerScrollMoves.setContent(listBox);
    }

    /**
     * Sets the data model for this panel and updates UI.
     *
     * @param gameInfoPanel Data model containing player information
     */
    public void setData(GameInfoPanel gameInfoPanel) {
        this.gameInfoPanel = gameInfoPanel;
        updateUI();
    }

    /**
     * Updates UI elements based on current GameInfoPanel data.
     * Thread-safe: Executes on JavaFX Application Thread.
     *
     * Updates:
     * <ul>
     *   <li>Player name display</li>
     *   <li>Piece color indicator and text</li>
     *   <li>Remaining piece count</li>
     *   <li>Current status message</li>
     *   <li>Move history visibility</li>
     * </ul>
     */
    private void updateUI() {
        if (gameInfoPanel == null) return;

        Platform.runLater(() -> {
            // Update player name
            if (gameInfoPanel.getPlayerName() != null && !gameInfoPanel.getPlayerName().isEmpty()) {
                playerNameLbl.setText(gameInfoPanel.getPlayerName());
            } else {
                playerNameLbl.setText("Hráč");
            }

            // Update piece color display
            if (gameInfoPanel.getPieceColor() != null) {
                String colorText = gameInfoPanel.getPieceColor() == com.checkerstcp.checkerstcp.PieceColor.WHITE
                        ? "Bílé ♟" : "Černé ♟";
                String styleText = gameInfoPanel.getPieceColor() == PieceColor.WHITE ? "white-piece" : "black-piece";
                pieceColorLbl.getStyleClass().add(styleText);
                pieceColorNamelbl.setText(colorText);
            } else {
                pieceColorNamelbl.setText("N/A");
            }

            // Update piece count
            numOfPiecesLbl.setText(String.valueOf(gameInfoPanel.getNumOfPieces()));

            // Update status message
            if (gameInfoPanel.getStatus() != null) {
                playerMoveStatusLbl.setText(gameInfoPanel.getStatus());
            }

            // Show/hide move history based on current player
            if (movesCard != null) {
                movesCard.setVisible(gameInfoPanel.isCurrentPlayer());
                movesCard.setManaged(gameInfoPanel.isCurrentPlayer());
            }
        });
    }



    /**
     * Updates all player information at once.
     * Convenience method for updating multiple fields simultaneously.
     *
     * @param name Player name
     * @param color Piece color (WHITE or BLACK)
     * @param pieces Number of remaining pieces
     * @param status Current status message (e.g., "Your turn", "Waiting...")
     */
    public void updatePlayerInfo(String name, PieceColor color, int pieces, String status) {
        if (gameInfoPanel == null) return;

        gameInfoPanel.setPlayerName(name);
        gameInfoPanel.setPieceColor(color);
        gameInfoPanel.setNumOfPieces(pieces);
        gameInfoPanel.setStatus(status);

        updateUI();
    }

    /**
     * Updates only the status message.
     * Thread-safe: Updates UI on JavaFX Application Thread.
     *
     * @param status New status message to display
     */
    public void updateStatus(String status) {
        if (gameInfoPanel == null) return;

        gameInfoPanel.setStatus(status);
        Platform.runLater(() -> playerMoveStatusLbl.setText(status));
    }

    /**
     * Updates the remaining piece count.
     * Thread-safe: Updates UI on JavaFX Application Thread.
     *
     * @param count New piece count
     */
    public void updatePiecesCount(int count) {
        if (gameInfoPanel == null) return;

        gameInfoPanel.setNumOfPieces(count);
        Platform.runLater(() -> numOfPiecesLbl.setText(String.valueOf(count)));
    }

    /**
     * Adds a move to the move history list.
     * Only adds move if this is the current player's panel.
     * Automatically scrolls to show newest move.
     *
     * Thread-safe: Loads FXML and updates UI on JavaFX Application Thread.
     *
     * @param moveItem Move information to add to history
     */
    public void addMove(PlayerMoveItem moveItem) {
        if (gameInfoPanel == null || !gameInfoPanel.isCurrentPlayer()) return;

        Platform.runLater(() -> {
            try {
                // Load move item FXML
                URL fxmlUrl = Main.class.getResource("moveitem.fxml");
                FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                        fxmlUrl,
                        "FXML 'moveitem.fxml' not found"
                ));

                HBox moveItemView = loader.load();
                PlayerMoveItemController controller = loader.getController();
                controller.setPlayerMoveItem(moveItem);

                // Configure layout properties
                HBox.setHgrow(moveItemView, Priority.ALWAYS);
                moveItemView.setMaxWidth(Double.MAX_VALUE);

                // Add to move history
                listBox.getChildren().add(moveItemView);

                // Update move counter
                playerMovesLbl.setText("Tahy: " + listBox.getChildren().size());

                // Scroll to bottom to show newest move
                playerScrollMoves.setVvalue(1.0);

            } catch (IOException e) {
                System.err.println("Error loading move item: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Clears all moves from the history list.
     * Thread-safe: Updates UI on JavaFX Application Thread.
     * Typically called at the start of a new game.
     */
    public void clearMoves() {
        Platform.runLater(() -> {
            listBox.getChildren().clear();
            playerMovesLbl.setText("Tahy: 0");
        });
    }

    /**
     * Sets whether this panel represents the current player.
     * Controls visibility of move history card.
     *
     * @param isCurrentPlayer true if this is the current player's panel
     */
    public void setIsCurrentPlayer(boolean isCurrentPlayer) {
        if (gameInfoPanel == null) return;

        gameInfoPanel.setCurrentPlayer(isCurrentPlayer);
        Platform.runLater(() -> {
            if (movesCard != null) {
                movesCard.setVisible(isCurrentPlayer);
                movesCard.setManaged(isCurrentPlayer);
            }
        });
    }
}