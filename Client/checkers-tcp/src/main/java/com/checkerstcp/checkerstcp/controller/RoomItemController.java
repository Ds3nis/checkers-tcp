package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.GameRoom;
import com.checkerstcp.checkerstcp.RoomStatus;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.awt.event.ActionListener;
import java.net.URL;
import java.util.Objects;
import java.util.ResourceBundle;

public class RoomItemController implements Initializable{

    @FXML
    private Button connectToRoomBtn;

    @FXML
    private Label numOfPlayersLbl;

    @FXML
    private Label roomNumberLbl;

    @FXML
    private Label roomStatusLbl;

    GameRoom gameRoom;


    public void setData(GameRoom gameRoom){
        this.gameRoom = gameRoom;
        this.numOfPlayersLbl.setText(Integer.toString(gameRoom.getNumPlayers()));
        this.roomNumberLbl.setText(gameRoom.getName());
        setRoomStatus(gameRoom.getRoomStatus());
    }

    private void setRoomStatus(RoomStatus roomStatus){
        if (roomStatus.equals(RoomStatus.WAITING_FOR_PLAYERS)) {
            roomStatusLbl.getStyleClass().setAll("room-open");
        } else if (roomStatus.equals(RoomStatus.FULL)) {
            roomStatusLbl.getStyleClass().setAll("room-full");
        } else  if (roomStatus.equals(RoomStatus.PLAYING)) {
            roomStatusLbl.getStyleClass().setAll("room-playing");
        }else {
            throw new IllegalArgumentException("Incorrect room status");
        }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {

    }
}
