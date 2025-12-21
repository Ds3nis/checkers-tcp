package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.GameInfoPanel;
import com.checkerstcp.checkerstcp.Main;
import com.checkerstcp.checkerstcp.PlayerMoveItem;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class GameInfoPanelController implements Initializable {

    @FXML
    private Label numOfPiecesLbl;

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

    List<PlayerMoveItem>  playerMoveItems = new ArrayList<PlayerMoveItem>();

    public void getPlayerMoveItems() {
        PlayerMoveItem playerMoveItem;
        playerMoveItem = new PlayerMoveItem("D4", "C6", LocalTime.now().truncatedTo(ChronoUnit.SECONDS));
        playerMoveItems.add(playerMoveItem);
        playerMoveItem = new PlayerMoveItem("D5", "C9", LocalTime.now().truncatedTo(ChronoUnit.SECONDS));
        playerMoveItems.add(playerMoveItem);
        playerMoveItem = new PlayerMoveItem("D5", "C9", LocalTime.now().truncatedTo(ChronoUnit.SECONDS));
        playerMoveItems.add(playerMoveItem);
        playerMoveItem = new PlayerMoveItem("D5", "C9", LocalTime.now().truncatedTo(ChronoUnit.SECONDS));
        playerMoveItems.add(playerMoveItem);
        playerMoveItem = new PlayerMoveItem("D5", "C9", LocalTime.now().truncatedTo(ChronoUnit.SECONDS));
        playerMoveItems.add(playerMoveItem);
    }

    public void setData(GameInfoPanel gameInfoPanel) {
        this.gameInfoPanel = gameInfoPanel;
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        getPlayerMoveItems();
        VBox listBox = new VBox(5);
        listBox.setFillWidth(true);
        VBox.setVgrow(listBox, Priority.ALWAYS);
        for(PlayerMoveItem playerMoveItem : playerMoveItems) {
            URL fxmlUrl = Main.class.getResource("moveitem.fxml");
            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                    fxmlUrl,
                    "FXML 'moveitem.fxml' nebyl nalezen v src/main/resources/com/checkerstcp/checkerstcp/"
            ));
            try {
                HBox moveItem = loader.load();
                PlayerMoveItemController playerMoveItemController = loader.getController();
                playerScrollMoves.setFitToWidth(true);
                playerMoveItemController.setPlayerMoveItem(playerMoveItem);
                HBox.setHgrow(moveItem, javafx.scene.layout.Priority.ALWAYS);
                moveItem.setMaxWidth(Double.MAX_VALUE);
                listBox.getChildren().add(moveItem);
                playerScrollMoves.setContent(listBox);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }
}
