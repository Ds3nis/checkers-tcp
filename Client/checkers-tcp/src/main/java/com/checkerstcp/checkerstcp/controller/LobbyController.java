package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.*;
import com.checkerstcp.checkerstcp.network.ClientManager;
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


    public LobbyController() {
        clientManager = ClientManager.getInstance();
    }


    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupUI();

        bindConnectionState();

        setupMessageHandlers();

        setupButtonHandlers();

        loadInitialRooms();
    }

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

    private void bindConnectionState() {
        clientManager.statusMessageProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                netStateLbl.setText(newVal);
                updateConnectionIndicator();
            });
        });

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

    private void updateConnectionIndicator() {
        if (clientManager.isConnected()) {
            netStateLbl.getStyleClass().removeAll("status-disconnected", "status-error");
            netStateLbl.getStyleClass().add("status-connected");
        } else {
            netStateLbl.getStyleClass().removeAll("status-connected", "status-error");
            netStateLbl.getStyleClass().add("status-disconnected");
        }
    }

    private void setupMessageHandlers() {
        clientManager.registerMessageHandler(OpCode.LOGIN_OK, this::handleLoginOk);

        clientManager.registerMessageHandler(OpCode.LOGIN_FAIL, this::handleLoginFail);

        clientManager.registerMessageHandler(OpCode.ROOM_JOINED, this::handleRoomJoined);
        clientManager.registerMessageHandler(OpCode.ROOM_CREATED, this::handleRoomCreated);

        clientManager.registerMessageHandler(OpCode.ROOM_FAIL, this::handleRoomFail);
        clientManager.registerMessageHandler(OpCode.ROOM_FULL, this::handleRoomFull);
        clientManager.registerMessageHandler(OpCode.ROOMS_LIST, this::handleRoomsList);

        clientManager.addRoomListUpdateHandler(this::updateRoomList);
    }

    private void setupButtonHandlers() {
        connectBtn.setOnAction(this::handleConnectDisconnect);
        createRoomBtn.setOnAction(this::handleCreateRoom);
        reloadBtn.setOnAction(this::handleReload);
    }

    private void handleConnectDisconnect(ActionEvent event) {
        if (clientManager.isConnected()) {
            handleDisconnect();
        } else {
            handleConnect();
        }
    }

    private void handleConnect() {
        String host = ipField.getText().trim();
        String portStr = portField.getText().trim();
        String username = clientNameField.getText().trim();

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

    private void handleCreateRoom(ActionEvent event) {
        if (!clientManager.isConnected()) {
            showError("Chyba", "Nejprve se připojte k serveru");
            return;
        }

        TextInputDialog dialog = new TextInputDialog("Room " + (new Random().nextInt(100)));
        dialog.setTitle("Vytvořit pokoj");
        dialog.setHeaderText("Vytvoření nové herny");
        dialog.setContentText("Název pokoje:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(roomName -> {
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


    private void handleReload(ActionEvent event) {
        if (!clientManager.isConnected()) {
            showError("Chyba", "Nejprve se připojte k serveru");
            return;
        }

        netStateLbl.setText("Aktualizace seznamu pokojů...");
        requestRoomsList();
    }

    private void requestRoomsList() {
        if (!clientManager.isConnected()) {
            return;
        }

        clientManager.requestRoomsList();
    }


    private void handleLoginOk(Message message) {
        Platform.runLater(() -> {
            showInfo("Úspěch", "Úspěšně připojeno k serveru!");
        });
    }

    private void handleLoginFail(Message message) {
        Platform.runLater(() -> {
            showError("Chyba při přihlášení", "Nepodařilo se přihlásit: " + message.getData());
        });
    }

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
                    // TODO: Відкрити game-view
                }
            }
        });
    }

    private void handleRoomFail(Message message) {
        Platform.runLater(() -> {
            showError("Chyba místnosti", "Operace se nezdařila: " + message.getData());
        });
    }

    private void handleRoomFull(Message message) {
        Platform.runLater(() -> {
            showError("Pokoj je plný", "Nelze se připojit – místnost je již plná");
        });
    }

    private void handleRoomsList(Message message) {
        Platform.runLater(() -> {
            System.out.println("Received rooms list from server");
            // ClientManager вже оброблює це повідомлення
        });
    }

    private void handleRoomCreated(Message message) {
        Platform.runLater(() -> {
            showInfo("Pokoj vytvořen", "Místnost byla úspěšně vytvořena!");
            requestRoomsList();
        });
    }

    // ========== Робота зі списком кімнат ==========

    private void loadInitialRooms() {
        roomListBox.setAlignment(Pos.CENTER);
        Label emptyLabel = new Label("Připojte se k serveru pro zobrazení mistnosti");
        emptyLabel.getStyleClass().add("empty-list-label");
        roomListBox.getChildren().add(emptyLabel);
    }


    private void updateRoomList(List<GameRoom> rooms) {
        Platform.runLater(() -> {
            clearRoomList();

            if (rooms.isEmpty()) {
                Label emptyLabel = new Label("Žádné pokoje nejsou k dispozici");
                emptyLabel.getStyleClass().add("empty-list-label");
                roomListBox.getChildren().add(emptyLabel);
                return;
            }

            for (GameRoom gameRoom : rooms) {
                try {
                    URL fxmlUrl = Main.class.getResource("roomitem-view.fxml");
                    FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                            fxmlUrl,
                            "FXML 'roomitem-view.fxml' не знайдено"
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


    private void clearRoomList() {
        roomListBox.getChildren().clear();
    }


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

    // ========== Допоміжні методи ==========

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


    public void cleanup() {
        clientManager.unregisterMessageHandler(OpCode.LOGIN_OK);
        clientManager.unregisterMessageHandler(OpCode.LOGIN_FAIL);
        clientManager.unregisterMessageHandler(OpCode.ROOM_JOINED);
        clientManager.unregisterMessageHandler(OpCode.ROOM_CREATED);
        clientManager.unregisterMessageHandler(OpCode.ROOM_FAIL);
        clientManager.unregisterMessageHandler(OpCode.ROOM_FULL);
        clientManager.unregisterMessageHandler(OpCode.ROOMS_LIST);
    }

}
