package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.GameRoom;
import com.checkerstcp.checkerstcp.Main;
import com.checkerstcp.checkerstcp.Player;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.*;

public class LobbyController implements Initializable {

    @FXML
    private Button connectBtn;

    @FXML
    private Button createRoomBtn;

    @FXML
    private TextField ipField;

    @FXML
    private Label netStateLbl;

    @FXML
    private Button reloadBtn;

    private List<GameRoom>  rooms = new ArrayList<>();

    @FXML
    private ScrollPane scrollPane;



    public LobbyController() {

    }

    private List<GameRoom> getRooms() throws Exception {
        GameRoom gameRoom;

        for (int i = 0; i < 5; i++) {
            gameRoom = new GameRoom(i,"Hra #" + i, List.of(new Player()));
            rooms.add(gameRoom);
        }

        return rooms;
    }


    @Override
    public void initialize(URL location, ResourceBundle resources) {
        try {
            getRooms();
            VBox listBox = new VBox(10);
            listBox.setFillWidth(true);
            listBox.setPadding(new Insets(3, 5, 3, 5));

            for (GameRoom gameRoom : rooms) {
                URL fxmlUrl = Main.class.getResource("roomitem-view.fxml");
                FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                        fxmlUrl,
                        "FXML 'roomitem-view.fxml' не знайдено в src/main/resources/com/checkerstcp/checkerstcp/"
                ));

                HBox roomCard = loader.load();
                RoomItemController roomItemController = loader.getController();
                roomItemController.setData(gameRoom);
                roomCard.setPrefWidth(scrollPane.getWidth());
                roomCard.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(roomCard, javafx.scene.layout.Priority.ALWAYS);

                listBox.getChildren().add(roomCard);
            }

            scrollPane.setContent(listBox);

            scrollPane.setFitToWidth(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
