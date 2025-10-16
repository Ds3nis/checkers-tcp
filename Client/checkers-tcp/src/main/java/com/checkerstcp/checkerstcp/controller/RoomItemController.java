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

    private void handleConnectToRoom(ActionEvent event)  {
            URL fxmlUrl = Main.class.getResource("game-view.fxml");
            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                    fxmlUrl,
                    "FXML 'roomitem-view.fxml' не знайдено в src/main/resources/com/checkerstcp/checkerstcp/"
            ));
        Scene gameScene = null;
        try {
            gameScene = new Scene(loader.load());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Stage stage = (Stage) connectToRoomBtn.getScene().getWindow();
            stage.setScene(gameScene);
            stage.centerOnScreen();
            stage.setFullScreen(true);
            stage.show();

            GameController controller = loader.getController();
//            controller.initGameRoom(gameRoom);

    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        connectToRoomBtn.setOnAction(this::handleConnectToRoom);
    }

}
