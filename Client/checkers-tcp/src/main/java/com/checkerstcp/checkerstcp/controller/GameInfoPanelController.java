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

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Ініціалізуємо контейнер для ходів
        listBox = new VBox(5);
        listBox.setFillWidth(true);
        VBox.setVgrow(listBox, Priority.ALWAYS);
        playerScrollMoves.setFitToWidth(true);
        playerScrollMoves.setContent(listBox);
    }

    public void setData(GameInfoPanel gameInfoPanel) {
        this.gameInfoPanel = gameInfoPanel;
        updateUI();
    }

    /**
     * Оновити UI на основі даних з GameInfoPanel
     */
    private void updateUI() {
        if (gameInfoPanel == null) return;

        Platform.runLater(() -> {
            if (gameInfoPanel.getPlayerName() != null && !gameInfoPanel.getPlayerName().isEmpty()) {
                playerNameLbl.setText(gameInfoPanel.getPlayerName());
            } else {
                playerNameLbl.setText("Hráč");
            }

            if (gameInfoPanel.getPieceColor() != null) {
                String colorText = gameInfoPanel.getPieceColor() == com.checkerstcp.checkerstcp.PieceColor.WHITE
                        ? "Bílé ♟" : "Černé ♟";
                String styleText = gameInfoPanel.getPieceColor() == PieceColor.WHITE ? "white-piece" : "black-piece";
                pieceColorLbl.getStyleClass().add(styleText);
                pieceColorNamelbl.setText(colorText);
            } else {
                pieceColorNamelbl.setText("N/A");
            }

            numOfPiecesLbl.setText(String.valueOf(gameInfoPanel.getNumOfPieces()));

            if (gameInfoPanel.getStatus() != null) {
                playerMoveStatusLbl.setText(gameInfoPanel.getStatus());
            }

            if (movesCard != null) {
                movesCard.setVisible(gameInfoPanel.isCurrentPlayer());
                movesCard.setManaged(gameInfoPanel.isCurrentPlayer());
            }
        });
    }


    public void updatePlayerInfo(String name, PieceColor color, int pieces, String status) {
        if (gameInfoPanel == null) return;

        gameInfoPanel.setPlayerName(name);
        gameInfoPanel.setPieceColor(color);
        gameInfoPanel.setNumOfPieces(pieces);
        gameInfoPanel.setStatus(status);

        updateUI();
    }


    public void updateStatus(String status) {
        if (gameInfoPanel == null) return;

        gameInfoPanel.setStatus(status);
        Platform.runLater(() -> playerMoveStatusLbl.setText(status));
    }

    public void updatePiecesCount(int count) {
        if (gameInfoPanel == null) return;

        gameInfoPanel.setNumOfPieces(count);
        Platform.runLater(() -> numOfPiecesLbl.setText(String.valueOf(count)));
    }

    public void addMove(PlayerMoveItem moveItem) {
        if (gameInfoPanel == null || !gameInfoPanel.isCurrentPlayer()) return;

        Platform.runLater(() -> {
            try {
                URL fxmlUrl = Main.class.getResource("moveitem.fxml");
                FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                        fxmlUrl,
                        "FXML 'moveitem.fxml' not found"
                ));

                HBox moveItemView = loader.load();
                PlayerMoveItemController controller = loader.getController();
                controller.setPlayerMoveItem(moveItem);

                HBox.setHgrow(moveItemView, Priority.ALWAYS);
                moveItemView.setMaxWidth(Double.MAX_VALUE);

                listBox.getChildren().add(moveItemView);

                playerMovesLbl.setText("Tahy: " + listBox.getChildren().size());

                playerScrollMoves.setVvalue(1.0);

            } catch (IOException e) {
                System.err.println("Error loading move item: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }


    public void clearMoves() {
        Platform.runLater(() -> {
            listBox.getChildren().clear();
            playerMovesLbl.setText("Tahy: 0");
        });
    }

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