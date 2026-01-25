package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.*;
import com.checkerstcp.checkerstcp.network.ClientManager;
import com.checkerstcp.checkerstcp.network.ConnectionStatusDialog;
import com.checkerstcp.checkerstcp.network.Message;
import com.checkerstcp.checkerstcp.network.OpCode;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.*;

/**
 * Controller for the game lobby interface.
 * Manages server connection, room listing, room creation/joining,
 * and transitions to game view when a match starts.
 *
 * <p>Main responsibilities:
 * <ul>
 *   <li>Server connection management (connect/disconnect)</li>
 *   <li>Room list display and updates</li>
 *   <li>Room creation with validation</li>
 *   <li>Room joining with status checks</li>
 *   <li>Reconnection handling and UI feedback</li>
 *   <li>Transition to game view when match starts</li>
 * </ul>
 *
 * <p>Message flow:
 * <ol>
 *   <li>User connects → LOGIN → LOGIN_OK</li>
 *   <li>Server sends ROOMS_LIST with available rooms</li>
 *   <li>User creates/joins room → ROOM_CREATED/ROOM_JOINED</li>
 *   <li>When 2 players ready → GAME_START → GAME_STATE</li>
 *   <li>Transition to GameController with initial board state</li>
 * </ol>
 *
 * <p>Thread safety: All UI updates are performed on JavaFX Application Thread
 * using Platform.runLater() to ensure safe concurrent access from network threads.
 */
public class LobbyController implements Initializable {

    @FXML
    private Button connectBtn;

    @FXML
    private Button createRoomBtn;

    @FXML
    private TextField ipField;

    @FXML
    private TextField portField;

    @FXML
    private TextField clientNameField;

    @FXML
    private Label netStateLbl;

    @FXML
    private Button reloadBtn;

    private List<GameRoom>  rooms = new ArrayList<>();

    @FXML
    private ScrollPane scrollPane;

    private VBox roomListBox;

    private ClientManager clientManager;

    private static final int MAX_ROOMS_DISPLAY = 50;

    // Pending game state (waiting for GAME_STATE after GAME_START)
    private String pendingRoomName;
    private String pendingPlayer1;
    private String pendingPlayer2;
    private String pendingFirstTurn;
    private int[][] pendingBoardState;
    private boolean waitingForGameState = false;

    private ConnectionStatusDialog reconnectDialog;

    /**
     * Constructs LobbyController and gets ClientManager singleton instance.
     */
    public LobbyController() {
        clientManager = ClientManager.getInstance();
    }


    /**
     * Initializes the controller after FXML loading.
     * Sets up UI, binds connection state, registers message handlers,
     * configures reconnection callbacks, and loads initial room list.
     *
     * @param location Location used to resolve relative paths (unused)
     * @param resources Resources used to localize root object (unused)
     */
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupUI();

        bindConnectionState();

        setupMessageHandlers();

        reconnectDialog = clientManager.getReconnectDialog();
        setupReconnectCallbacks();

        setupButtonHandlers();

        restoreConnectionState();

        loadInitialRooms();
    }

    /**
     * Initializes UI components and layout.
     * Creates room list container, disables buttons, and sets initial button text.
     */
    private void setupUI() {
        roomListBox = new VBox(10);
        roomListBox.setFillWidth(true);
        roomListBox.setPadding(new Insets(3, 5, 3, 5));
        scrollPane.setContent(roomListBox);
        scrollPane.setFitToWidth(true);
        createRoomBtn.setDisable(true);
        reloadBtn.setDisable(true);

        updateConnectButtonText(false);
    }

    /**
     * Binds UI elements to ClientManager observable properties.
     * Updates connection indicator, button states, and room list based on connection state.
     */
    private void bindConnectionState() {
        // Bind status message label
        clientManager.statusMessageProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                netStateLbl.setText(newVal);
                updateConnectionIndicator();
            });
        });

        // Bind connection state changes
        clientManager.connectedProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                updateConnectButtonText(newVal);
                createRoomBtn.setDisable(!newVal);
                reloadBtn.setDisable(!newVal);
                ipField.setDisable(newVal);
                portField.setDisable(newVal);
                clientNameField.setDisable(newVal);

                if (newVal) {
                    requestRoomsList();
                } else {
                    clearRoomList();
                }
            });
        });
    }

    /**
     * Restores UI state if already connected to server.
     * Called during initialization to handle reconnection scenarios.
     */
    private void restoreConnectionState() {
        if (clientManager.isConnected()) {
            System.out.println("Restoring connection state - already connected");

            Platform.runLater(() -> {
                updateConnectButtonText(true);

                createRoomBtn.setDisable(false);
                reloadBtn.setDisable(false);

                ipField.setDisable(true);
                portField.setDisable(true);
                clientNameField.setDisable(true);

                netStateLbl.setText("Připojeno");
                updateConnectionIndicator();

                requestRoomsList();
            });
        }
    }

    /**
     * Updates connect button text and styling based on connection state.
     *
     * @param isConnected true if connected to server
     */
    private void updateConnectButtonText(boolean isConnected) {
        if (isConnected) {
            connectBtn.setText("Odpojit");
            connectBtn.getStyleClass().removeAll("connect-button");
            connectBtn.getStyleClass().add("disconnect-button");
        } else {
            connectBtn.setText("Připojit se");
            connectBtn.getStyleClass().removeAll("disconnect-button");
            connectBtn.getStyleClass().add("connect-button");
        }
    }

    /**
     * Updates connection status indicator styling.
     * Applies appropriate CSS class based on connection state.
     */
    private void updateConnectionIndicator() {
        if (clientManager.isConnected()) {
            netStateLbl.getStyleClass().removeAll("status-disconnected", "status-error");
            netStateLbl.getStyleClass().add("status-connected");
        } else {
            netStateLbl.getStyleClass().removeAll("status-connected", "status-error");
            netStateLbl.getStyleClass().add("status-disconnected");
        }
    }

    /**
     * Registers message handlers for all lobby-related protocol messages.
     * Handlers process server responses and update UI accordingly.
     */
    private void setupMessageHandlers() {
        clientManager.registerMessageHandler(OpCode.LOGIN_OK, this::handleLoginOk);

        clientManager.registerMessageHandler(OpCode.LOGIN_FAIL, this::handleLoginFail);

        clientManager.registerMessageHandler(OpCode.ROOM_JOINED, this::handleRoomJoined);
        clientManager.registerMessageHandler(OpCode.ROOM_CREATED, this::handleRoomCreated);

        clientManager.registerMessageHandler(OpCode.ROOM_FAIL, this::handleRoomFail);
        clientManager.registerMessageHandler(OpCode.ROOM_FULL, this::handleRoomFull);
        clientManager.registerMessageHandler(OpCode.ROOMS_LIST, this::handleRoomsList);
        clientManager.registerMessageHandler(OpCode.GAME_START, this::handleGameStart);
        clientManager.registerMessageHandler(OpCode.GAME_STATE, this::handleGameState);

        clientManager.addRoomListUpdateHandler(this::updateRoomList);
    }

    /**
     * Sets up callbacks for reconnection events.
     * Configures dialog behavior and handles reconnection outcomes.
     */
    private void setupReconnectCallbacks() {
        // Connection lost callback
        clientManager.setOnConnectionLost(() -> {
            Platform.runLater(() -> {
                System.out.println("Connection lost in lobby!");
                reconnectDialog.show();
            });
        });

        // Reconnection success callback
        clientManager.setOnReconnectSuccess(() -> {
            Platform.runLater(() -> {
                System.out.println("Connection restored in lobby!");
                requestRoomsList();
            });
        });

        // Reconnection failure callback
        clientManager.setOnReconnectFailed(() -> {
            Platform.runLater(() -> {
                System.err.println("Reconnect failed in lobby!");

                showError(
                        "Připojení ztraceno",
                        "Nepodařilo se obnovit připojení k serveru.\n" +
                                "Zkontrolujte prosím připojení k internetu."
                );
            });
        });

        // Dialog cancel button callback
        reconnectDialog.setOnCancel(() -> {
            clientManager.disconnect();
        });
    }

    /**
     * Sets up button event handlers.
     */
    private void setupButtonHandlers() {
        connectBtn.setOnAction(this::handleConnectDisconnect);
        createRoomBtn.setOnAction(this::handleCreateRoom);
        reloadBtn.setOnAction(this::handleReload);
    }

    /**
     * Handles connect/disconnect button click.
     * Toggles between connect and disconnect based on current state.
     *
     * @param event Button action event
     */
    private void handleConnectDisconnect(ActionEvent event) {
        if (clientManager.isConnected()) {
            handleDisconnect();
        } else {
            handleConnect();
        }
    }

    /**
     * Handles connection attempt to server.
     * Validates input fields and initiates connection in background thread.
     */
    private void handleConnect() {
        String host = ipField.getText().trim();
        String portStr = portField.getText().trim();
        String username = clientNameField.getText().trim();

        // Validate input
        if (host.isEmpty()) {
            showError("Chyba", "Zadejte IP adresu serveru");
            return;
        }

        if (username.isEmpty()) {
            showError("Chyba", "Zadejte uživatelské jméno");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            showError("Chyba", "Nesprávný port (1-65535)");
            return;
        }

        netStateLbl.setText("Připojování k ...");

        // Connect in background thread to avoid blocking UI
        new Thread(() -> {
            boolean success = clientManager.connect(host, port, username);
            if (!success) {
                Platform.runLater(() -> {
                    showError("Chyba připojení",
                            "Nepodařilo se připojit k serveru.\n" +
                                    "Zkontrolujte adresu a port.");
                    netStateLbl.setText("Není připojeno");
                });
            }
        }).start();
    }

    /**
     * Handles disconnection request from server.
     * Shows confirmation dialog before disconnecting.
     */
    private void handleDisconnect() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Potvrzení");
        alert.setHeaderText("Odpojení od serveru");
        alert.setContentText("Jste si jisti, že se chcete odpojit?");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            clientManager.disconnect();
            clearRoomList();
            netStateLbl.setText("Odpojeno");
        }
    }


    /**
     * Handles room creation request.
     * Shows dialog for room name input and validates before creating.
     *
     * @param event Button action event
     */
    private void handleCreateRoom(ActionEvent event) {
        if (!clientManager.isConnected()) {
            showError("Chyba", "Nejprve se připojte k serveru");
            return;
        }

        // Show room name input dialog
        TextInputDialog dialog = new TextInputDialog("Room_" + (new Random().nextInt(100)));
        dialog.setTitle("Vytvořit pokoj");
        dialog.setHeaderText("Vytvoření nové herny");
        dialog.setContentText("Název pokoje:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(roomName -> {
            // Validate room name
            if (roomName.trim().isEmpty()) {
                showError("Chyba", "Název místnosti nemůže být prázdný");
                return;
            }

            if (roomName.length() > 32) {
                showError("Chyba", "Název místnosti je příliš dlouhý (max. 32 znaků)");
                return;
            }

            if (clientManager.getAvailableRooms().size() >= MAX_ROOMS_DISPLAY) {
                showError("Chyba", "Dosáhli jsme maximálního počtu pokojů");
                return;
            }

            clientManager.createRoom(roomName.trim());
            netStateLbl.setText("Vytváření pokoje...");
        });
    }



    /**
     * Handles room list reload request.
     * Requests updated room list from server.
     *
     * @param event Button action event
     */
    private void handleReload(ActionEvent event) {
        if (!clientManager.isConnected()) {
            showError("Chyba", "Nejprve se připojte k serveru");
            return;
        }

        netStateLbl.setText("Aktualizace seznamu pokojů...");
        requestRoomsList();
    }

    /**
     * Requests room list from server.
     * Only sends request if connected.
     */
    private void requestRoomsList() {
        if (!clientManager.isConnected()) {
            return;
        }

        clientManager.requestRoomsList();
    }


    // ========== Message Handlers ==========

    /**
     * Handles successful login confirmation from server.
     *
     * @param message LOGIN_OK message
     */
    private void handleLoginOk(Message message) {
        Platform.runLater(() -> {
            showInfo("Úspěch", "Úspěšně připojeno k serveru!");
        });

    }

    /**
     * Handles login failure notification from server.
     *
     * @param message LOGIN_FAIL message with failure reason
     */
    private void handleLoginFail(Message message) {
        Platform.runLater(() -> {
            showError("Chyba při přihlášení", "Nepodařilo se přihlásit: " + message.getData());
        });
    }

    /**
     * Handles room joined confirmation from server.
     * Shows different messages based on whether waiting for opponent.
     *
     * Protocol format: "room_name,player_count"
     *
     * @param message ROOM_JOINED message
     */
    private void handleRoomJoined(Message message) {
        Platform.runLater(() -> {
            String[] parts = message.getData().split(",");
            if (parts.length >= 2) {
                String roomName = parts[0];
                int playerCount = Integer.parseInt(parts[1]);

                if (playerCount == 1) {
                    showInfo("Pokoj připojen",
                            "Připojeno k pokoji '" + roomName + "'.\n" +
                                    "Čekání na druhého hráče...");
                    requestRoomsList();
                } else {
                    System.out.println("Game starting in room: " + roomName);
                    requestRoomsList();
                }
            }
        });
    }

    /**
     * Handles room operation failure notification.
     *
     * @param message ROOM_FAIL message with failure reason
     */
    private void handleRoomFail(Message message) {
        Platform.runLater(() -> {
            showError("Chyba místnosti", "Operace se nezdařila: " + message.getData());
        });
    }

    /**
     * Handles room full notification.
     *
     * @param message ROOM_FULL message
     */
    private void handleRoomFull(Message message) {
        Platform.runLater(() -> {
            showError("Pokoj je plný", "Nelze se připojit – místnost je již plná");
        });
    }

    /**
     * Handles room list update from server.
     * Actual list processing is done by updateRoomList callback.
     *
     * @param message ROOMS_LIST message with JSON data
     */
    private void handleRoomsList(Message message) {
        Platform.runLater(() -> {
            System.out.println("Received rooms list from server");
        });
    }

    /**
     * Handles room creation confirmation.
     *
     * @param message ROOM_CREATED message
     */
    private void handleRoomCreated(Message message) {
        Platform.runLater(() -> {
            showInfo("Pokoj vytvořen", "Místnost byla úspěšně vytvořena!");
            requestRoomsList();
        });
    }

    /**
     * Handles game start notification from server.
     * Stores game information and waits for GAME_STATE message with board.
     *
     * Protocol format: "room_name,player1,player2,first_turn"
     *
     * @param message GAME_START message
     */
    private void handleGameStart(Message message) {
        Platform.runLater(() -> {
            try {
                String[] parts = message.getData().split(",");
                if (parts.length >= 4) {
                    pendingRoomName = parts[0];
                    pendingPlayer1 = parts[1];
                    pendingPlayer2 = parts[2];
                    pendingFirstTurn = parts[3];

                    waitingForGameState = true;

                    System.out.println("GAME_START received, waiting for GAME_STATE...");
                    System.out.println("  Room: " + pendingRoomName);
                    System.out.println("  Player1 (WHITE): " + pendingPlayer1);
                    System.out.println("  Player2 (BLACK): " + pendingPlayer2);
                    System.out.println("  First turn: " + pendingFirstTurn);
                }
            } catch (Exception e) {
                System.err.println("Error handling GAME_START: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Handles initial game state from server.
     * Parses board state and opens game view if waiting for game start.
     *
     * @param message GAME_STATE message with JSON board data
     */
    private void handleGameState(Message message) {
        Platform.runLater(() -> {
            try {
                if (waitingForGameState) {
                    String jsonData = message.getData();
                    System.out.println("GAME_STATE received: " + jsonData);

                    pendingBoardState = GameStateParser.parseBoardFromJson(jsonData);

                    openGameView(pendingRoomName, pendingPlayer1, pendingPlayer2,
                            pendingFirstTurn, pendingBoardState);

                    waitingForGameState = false;
                    pendingBoardState = null;
                }
            } catch (Exception e) {
                System.err.println("Error handling GAME_STATE in lobby: " + e.getMessage());
                e.printStackTrace();
                waitingForGameState = false;
            }
        });
    }

    // ========== Room List Management ==========

    /**
     * Loads initial empty room list placeholder.
     */
    private void loadInitialRooms() {
        roomListBox.setAlignment(Pos.CENTER);
        Label emptyLabel = new Label("Připojte se k serveru pro zobrazení mistnosti");
        emptyLabel.getStyleClass().add("empty-list-label");
        roomListBox.getChildren().add(emptyLabel);
    }



    /**
     * Updates room list display with new data from server.
     * Loads FXML for each room item and adds to scrollable list.
     *
     * Thread-safe: Updates UI on JavaFX Application Thread.
     *
     * @param rooms List of available rooms to display
     */
    private void updateRoomList(List<GameRoom> rooms) {
        Platform.runLater(() -> {
            clearRoomList();

            if (rooms.isEmpty()) {
                Label emptyLabel = new Label("Žádné pokoje nejsou k dispozici");
                emptyLabel.getStyleClass().add("empty-list-label");
                roomListBox.getChildren().add(emptyLabel);
                return;
            }

            // Create room item for each room
            for (GameRoom gameRoom : rooms) {
                try {
                    URL fxmlUrl = Main.class.getResource("roomitem-view.fxml");
                    FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                            fxmlUrl,
                            "FXML 'roomitem-view.fxml' not found"
                    ));

                    HBox roomCard = loader.load();
                    RoomItemController roomItemController = loader.getController();
                    roomItemController.setData(gameRoom);
                    roomItemController.setLobbyController(this);

                    roomCard.setMaxWidth(Double.MAX_VALUE);
                    HBox.setHgrow(roomCard, javafx.scene.layout.Priority.ALWAYS);
                    roomListBox.setAlignment(Pos.TOP_LEFT);
                    roomListBox.getChildren().add(roomCard);

                } catch (Exception e) {
                    System.err.println("Error loading room item: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Clears all rooms from the list display.
     */
    private void clearRoomList() {
        roomListBox.getChildren().clear();
    }


    /**
     * Joins a game room.
     * Called by RoomItemController when user clicks join button.
     * Validates room status before sending join request.
     *
     * @param room Room to join
     */
    public void joinRoom(GameRoom room) {
        if (!clientManager.isConnected()) {
            showError("Chyba", "Nejprve se připojte k serveru");
            return;
        }

        if (room.getRoomStatus() == RoomStatus.FULL) {
            showError("Pokoj je plný", "Tato místnost je již plná");
            return;
        }

        clientManager.joinRoom(room.getName());
        netStateLbl.setText("Připojování k pokoji...");
    }

    // ========== Helper Methods ==========

    /**
     * Shows error dialog with title and message.
     *
     * @param title Dialog title
     * @param message Error message
     */
    private void showError(String title, String message) {
        new GameAlertDialog(
                AlertVariant.ERROR,
                title,
                message,
                () -> System.out.println(message),
                null,
                true
        ).show();
    }

    /**
     * Shows info dialog with title and message.
     *
     * @param title Dialog title
     * @param message Info message
     */
    private void showInfo(String title, String message) {
        new GameAlertDialog(
                AlertVariant.INFO,
                title,
                message,
                null,
                null,
                false
        ).show();
    }

    /**
     * Cleans up resources when leaving lobby.
     * Unregisters message handlers and callbacks.
     * Should be called before transitioning to game view.
     */
    public void cleanup() {
        clientManager.unregisterMessageHandler(OpCode.LOGIN_OK);
        clientManager.unregisterMessageHandler(OpCode.LOGIN_FAIL);
        clientManager.unregisterMessageHandler(OpCode.ROOM_JOINED);
        clientManager.unregisterMessageHandler(OpCode.ROOM_CREATED);
        clientManager.unregisterMessageHandler(OpCode.ROOM_FAIL);
        clientManager.unregisterMessageHandler(OpCode.ROOM_FULL);
        clientManager.unregisterMessageHandler(OpCode.ROOMS_LIST);
        clientManager.unregisterMessageHandler(OpCode.GAME_START);

        clientManager.setOnConnectionLost(null);
        clientManager.setOnReconnectSuccess(null);
        clientManager.setOnReconnectFailed(null);

        if (reconnectDialog != null && reconnectDialog.isShowing()) {
            reconnectDialog.hide();
        }
    }


    /**
     * Opens game view with initialized game state.
     * Loads game FXML, configures game controller, and switches scenes.
     *
     * @param roomName Name of the game room
     * @param player1 First player name (white pieces)
     * @param player2 Second player name (black pieces)
     * @param firstTurn Name of player who moves first
     * @param initialBoardState Initial board state as 2D array
     */
    private void openGameView(String roomName, String player1, String player2,
                              String firstTurn, int[][] initialBoardState) {
        try {
            System.out.println("Opening game view for room: " + roomName);

            URL fxmlUrl = Main.class.getResource("game-view.fxml");
            if (fxmlUrl == null) {
                showError("Chyba", "FXML 'game-view.fxml' nebyl nalezen");
                return;
            }

            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            javafx.scene.Parent root = loader.load();
            javafx.scene.Scene gameScene = new javafx.scene.Scene(root);

            GameController gameController = loader.getController();

            // Determine player color and opponent
            String myName = clientManager.getCurrentClientId();
            PieceColor myColor;
            String opponent;

            if (myName.equals(player1)) {
                myColor = PieceColor.WHITE;
                opponent = player2;
            } else {
                myColor = PieceColor.BLACK;
                opponent = player1;
            }

            boolean myTurn = firstTurn.equals(myName);

            System.out.println("My name: " + myName);
            System.out.println("My color: " + myColor);
            System.out.println("Opponent: " + opponent);
            System.out.println("My turn: " + myTurn);

            // Initialize game controller
            gameController.initGame(roomName, opponent, myColor, myTurn, initialBoardState);

            // Switch to game scene
            javafx.stage.Stage stage = (javafx.stage.Stage) connectBtn.getScene().getWindow();
            stage.setFullScreen(true);
            stage.setScene(gameScene);
            stage.centerOnScreen();

            // Clean up lobby resources
            cleanup();

            System.out.println("Successfully switched to game view");

        } catch (Exception e) {
            System.err.println("Error opening game view: " + e.getMessage());
            e.printStackTrace();
            showError("Chyba", "Nepodařilo se otevřít herní obrazovku: " + e.getMessage());
        }
    }

}
