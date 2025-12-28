package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.GameRoom;
import com.checkerstcp.checkerstcp.Main;
import com.checkerstcp.checkerstcp.RoomStatus;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import java.util.ResourceBundle;

public class RoomItemController implements Initializable{


    @FXML
    private HBox roomItemCard;

    @FXML
    private Button connectToRoomBtn;

    @FXML
    private Label numOfPlayersLbl;

    @FXML
    private Label roomNumberLbl;

    @FXML
    private Label roomStatusLbl;

    GameRoom gameRoom;
    private LobbyController lobbyController;


    public void setData(GameRoom gameRoom){
        this.gameRoom = gameRoom;
        updateUI();
    }

    public void setLobbyController(LobbyController lobbyController) {
        this.lobbyController = lobbyController;
    }

    private void updateUI() {
        if (gameRoom == null) return;

        this.numOfPlayersLbl.setText(gameRoom.getNumPlayers() + "/2 " + "hráčů");
        this.roomNumberLbl.setText(gameRoom.getName());
        RoomStatus roomStatus = gameRoom.getRoomStatus();
        setRoomStatus(roomStatus);

        if (roomStatus == RoomStatus.WAITING_FOR_PLAYERS) {
            connectToRoomBtn.setText("Připojit se");
            connectToRoomBtn.setDisable(false);
        }else {
            connectToRoomBtn.setDisable(true);
        }
    }

    private void setRoomStatus(RoomStatus roomStatus) {
        roomStatusLbl.getStyleClass().removeAll("room-open", "room-full", "room-playing");

        switch (roomStatus) {
            case WAITING_FOR_PLAYERS:
                roomStatusLbl.setText("Očekávání");
                roomStatusLbl.getStyleClass().add("room-open");
                break;
            case FULL:
                roomStatusLbl.setText("Plná");
                roomStatusLbl.getStyleClass().add("room-full");
                break;
            case PLAYING:
                roomStatusLbl.setText("Hra běží");
                roomStatusLbl.getStyleClass().add("room-pending");
                break;
            default:
                throw new IllegalArgumentException("Incorrect room status: " + roomStatus);
        }
    }

    private void handleConnectToRoom(ActionEvent event) {
        if (gameRoom == null) {
            System.err.println("GameRoom is null");
            return;
        }

        if (lobbyController != null) {
            lobbyController.joinRoom(gameRoom);
        } else {
            System.err.println("LobbyController is not set");
        }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        connectToRoomBtn.setOnAction(this::handleConnectToRoom);


        roomItemCard.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && !connectToRoomBtn.isDisable()) {
                handleConnectToRoom(new ActionEvent());
            }
        });
    }

}
