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

/**
 * Controller for individual room list items in the lobby.
 * Displays room information and provides join functionality.
 *
 * <p>Displays:
 * <ul>
 *   <li>Room name/number</li>
 *   <li>Player count (X/2)</li>
 *   <li>Room status (Waiting, Full, Playing)</li>
 *   <li>Join button (disabled for full/playing rooms)</li>
 * </ul>
 *
 * <p>Interaction:
 * <ul>
 *   <li>Single click: Select room</li>
 *   <li>Double click: Join room (if available)</li>
 *   <li>Button click: Join room</li>
 * </ul>
 */
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


    /**
     * Sets the room data and updates UI display.
     *
     * @param gameRoom Room information to display
     */
    public void setData(GameRoom gameRoom){
        this.gameRoom = gameRoom;
        updateUI();
    }

    /**
     * Sets the parent lobby controller for join operations.
     *
     * @param lobbyController Controller that handles room joining
     */
    public void setLobbyController(LobbyController lobbyController) {
        this.lobbyController = lobbyController;
    }


    /**
     * Updates UI elements based on current room data.
     * Configures button state based on room availability.
     */
    private void updateUI() {
        if (gameRoom == null) return;

        // Update player count display
        this.numOfPlayersLbl.setText(gameRoom.getNumPlayers() + "/2 " + "hráčů");

        // Update room name
        this.roomNumberLbl.setText(gameRoom.getName());

        // Update room status indicator
        RoomStatus roomStatus = gameRoom.getRoomStatus();
        setRoomStatus(roomStatus);

        // Configure join button based on room status
        if (roomStatus == RoomStatus.WAITING_FOR_PLAYERS) {
            connectToRoomBtn.setText("Připojit se");
            connectToRoomBtn.setDisable(false);
        } else {
            connectToRoomBtn.setDisable(true);
        }
    }

    /**
     * Updates room status display with appropriate styling.
     * Applies CSS classes for visual indication of room state.
     *
     * @param roomStatus Current status of the room
     * @throws IllegalArgumentException if room status is invalid
     */
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

    /**
     * Handles room join request.
     * Delegates to LobbyController for actual join operation.
     *
     * @param event Action event from button or double-click
     */
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

    /**
     * Initializes the controller after FXML loading.
     * Sets up event handlers for button click and double-click.
     *
     * @param location Location used to resolve relative paths (unused)
     * @param resources Resources used to localize root object (unused)
     */
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
