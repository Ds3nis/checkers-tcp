package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.*;
import com.checkerstcp.checkerstcp.network.ClientManager;
import com.checkerstcp.checkerstcp.network.Message;
import com.checkerstcp.checkerstcp.network.OpCode;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.URL;
import java.time.LocalTime;
import java.util.*;

public class GameController implements Initializable {

    @FXML
    private Button burgerMenuBtn;

    @FXML
    private Label chatBtn;

    @FXML
    private ImageView chatBtnImg;

    @FXML
    private HBox footer;

    @FXML
    private Region footerSpacerRegion;

    @FXML
    private GridPane gameBoard;

    @FXML
    private Region headerSpacerRegion;

    @FXML
    private BorderPane mainBorderPane;

    @FXML
    private Label netStateLbl;

    @FXML
    private Label roomLbl;

    @FXML
    private Label roomNameLbl;

    private VBox sideMenu;
    private StackPane overlayLayer;
    private boolean menuVisible = false;

    private CheckersBoard board;
    private ClientManager clientManager;

    private String roomName;
    private String opponentName;

    private PieceColor myColor;
    private boolean isMyTurn = false;

    private GameInfoPanelController myInfoPanel;
    private GameInfoPanelController opponentInfoPanel;

    private List<PlayerMoveItem> moveHistory = new ArrayList<>();
    private Move pendingMove;
    private int[][] deferredServerState;




    @Override
    public void initialize(URL location, ResourceBundle resources) {
        clientManager = ClientManager.getInstance();
        board = new CheckersBoard();
        setupMessageHandlers();
        StackPane container = new StackPane(board);
        mainBorderPane.setCenter(container);

        setupBoardCallbacks();


        createSlideMenu();
        burgerMenuBtn.setOnAction(e -> toggleMenu());

        setupGameInfoPanels();

        updateConnectionStatus();

        board.setOnAnimationFinished(() -> {
            if (deferredServerState != null) {
                System.out.println("Applying deferred server state");
                syncBoardIfNeeded(deferredServerState);
                deferredServerState = null;
                pendingMove = null;
            }
        });
    }

    private void setupBoardCallbacks() {
        // Коли гравець спробує зробити хід
        board.setOnMoveAttempt(move -> {
            if (!isMyTurn) {
                showError("Počkejte na svůj tah!");
                return;
            }

            // Відправити хід на сервер
            sendMoveToServer(move);

            // Тимчасово заблокувати дошку
            isMyTurn = false;
            updateTurnIndicator();
        });

        // Коли вибрана шашка
        board.setOnPieceSelected(piece -> {
            System.out.println("Selected: " + piece);
        });
    }

    private void setupMessageHandlers() {
        clientManager.registerMessageHandler(OpCode.GAME_START, this::handleGameStart);

        clientManager.registerMessageHandler(OpCode.GAME_STATE, this::handleGameState);

        clientManager.registerMessageHandler(OpCode.INVALID_MOVE, this::handleInvalidMove);

        clientManager.registerMessageHandler(OpCode.GAME_END, this::handleGameEnd);

        clientManager.registerMessageHandler(OpCode.ROOM_LEFT, this::handlePlayerLeft);
    }

    private void handleGameStart(Message message) {
        Platform.runLater(() -> {
            // Format: room_name,player1,player2,first_turn
            String[] parts = message.getData().split(",");
            if (parts.length >= 4) {
                roomName = parts[0];
                String player1 = parts[1];
                String player2 = parts[2];
                String firstTurn = parts[3];

                // Визначити мій колір
                String myName = clientManager.getCurrentClientId();
                if (myName.equals(player1)) {
                    myColor = PieceColor.WHITE;
                    opponentName = player2;
                } else {
                    myColor = PieceColor.BLACK;
                    opponentName = player1;
                }

                isMyTurn = firstTurn.equals(myName);

                roomNameLbl.setText("Pokoj: " + roomName);
                updateTurnIndicator();

                System.out.println("Game started! My color: " + myColor + ", My turn: " + isMyTurn);
            }
        });
    }

    private void handleGameState(Message message) {
        Platform.runLater(() -> {
            try {
                String jsonData = message.getData();
                System.out.println("GAME_STATE received: " + jsonData);

                // Парсинг дошки
                int[][] boardState = GameStateParser.parseBoardFromJson(jsonData);

                System.out.println("My color:" + myColor);
                System.out.println("My turn:" + isMyTurn);
                System.out.println(Arrays.deepToString(board.getBoardState()));
                if (myColor == PieceColor.BLACK) {
                    boardState = BoardRotation.rotateBoard(boardState);
                    System.out.println("Board rotated for BLACK player");
                }

                if (board.isAnimating()) {
                    System.out.println("⏳ Animation in progress, deferring sync");
                    deferredServerState = boardState;
                    return;
                }

                syncBoardIfNeeded(boardState);

                // Визначити чий хід
                String currentTurnPlayer = GameStateParser.getJsonValue(jsonData, "current_turn");
                System.out.println("Current turn player: " + currentTurnPlayer);
                if (currentTurnPlayer != null) {
                    isMyTurn = currentTurnPlayer.equals(clientManager.getCurrentClientId());
                    System.out.println("Current turn: " + currentTurnPlayer + ", My turn: " + isMyTurn);
                }

                // Встановити колір для дошки
                PieceColor turnColor = isMyTurn ? myColor : myColor.opposite();
                board.setCurrentTurn(turnColor);

                updateTurnIndicator();

            } catch (Exception e) {
                System.err.println("Error parsing game state: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void syncBoardIfNeeded(int[][] serverState) {
        int[][] localState = board.getBoardState();

        if (boardsEqual(localState, serverState)) {
            System.out.println("Board already in sync");
            return;
        }

        System.out.println("Board out of sync, applying server state");
        board.setBoardState(serverState);
    }

    private boolean boardsEqual(int[][] a, int[][] b) {
        if (a.length != b.length) return false;

        for (int i = 0; i < a.length; i++) {
            if (!Arrays.equals(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }


    private void handleInvalidMove(Message message) {
        Platform.runLater(() -> {
            showError("Neplatný tah: " + message.getData());
            // Повернути можливість ходити
            isMyTurn = true;
            updateTurnIndicator();
        });
    }

    private void handleGameEnd(Message message) {
        Platform.runLater(() -> {
            // Формат: winner,reason
            String[] parts = message.getData().split(",");
            String winner = parts.length > 0 ? parts[0] : "Neznámý";
            String reason = parts.length > 1 ? parts[1] : "konec hry";

            boolean iWon = winner.equals(clientManager.getCurrentClientId());

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Hra skončila");
            alert.setHeaderText(iWon ? "Gratulujeme! Vyhráli jste!" : "Prohráli jste");
            alert.setContentText("Důvod: " + reason);

            alert.showAndWait().ifPresent(response -> {
                returnToLobby();
            });
        });
    }

    private void handlePlayerLeft(Message message) {
        String[] parts = message.getData().split(",");
        String roomName = parts[0];
        String playerName = parts[1];
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Hráč " + playerName + " opustil hru");
            alert.setHeaderText("Soupeř se odpojil");
            alert.setContentText("Hra skončila");

            alert.showAndWait().ifPresent(response -> {
                returnToLobby();
            });
        });
    }

    private void sendMoveToServer(Move move) {
        if (clientManager.isConnected() && roomName != null) {
            int fromRow = move.getFromRow();
            int fromCol = move.getFromCol();
            int toRow = move.getToRow();
            int toCol = move.getToCol();

            if (myColor == PieceColor.BLACK) {
                BoardRotation.RotatedMove rotated = BoardRotation.rotateCoordinates(
                        fromRow, fromCol, toRow, toCol, 8
                );
                fromRow = rotated.fromRow;
                fromCol = rotated.fromCol;
                toRow = rotated.toRow;
                toCol = rotated.toCol;

                System.out.println("Coordinates rotated back for server: " +
                        "(" + move.getFromRow() + "," + move.getFromCol() + ")->" +
                        "(" + move.getToRow() + "," + move.getToCol() + ") => " +
                        "(" + fromRow + "," + fromCol + ")->" +
                        "(" + toRow + "," + toCol + ")");
            }

            pendingMove = move;
            board.executeMove(move);
            addMoveToHistory(move);
            clientManager.sendMove(fromRow, fromCol, toRow, toCol);
        }
    }

    private void addMoveToHistory(Move move) {
        Position from = new Position(move.getFromRow(), move.getFromCol());
        Position to = new Position(move.getToRow(), move.getToCol());

        PlayerMoveItem moveItem = new PlayerMoveItem(
                from.toString(),
                to.toString(),
                LocalTime.now()
        );

        moveHistory.add(moveItem);

        updateMovesPanel(moveItem);
    }

    private void updateMovesPanel(PlayerMoveItem move) {
        // TODO: Додати до відповідної панелі (myInfoPanel або opponentInfoPanel)
        // В залежності від того, чий це був хід
    }


    private void updateTurnIndicator() {
        if (isMyTurn) {
            netStateLbl.setText("Váš tah!");
            netStateLbl.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
        } else {
            netStateLbl.setText("Pohyb soupeře...");
            netStateLbl.setStyle("-fx-text-fill: orange;");
        }
    }

    private void updateConnectionStatus() {
        clientManager.statusMessageProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                if (!isMyTurn && newVal != null) {
                    // Показувати статус тільки коли не наш хід
                    roomLbl.setText(newVal);
                }
            });
        });
    }

    private void setupGameInfoPanels() {
        try {
            // Ліва панель (гравець)
            URL leftPanelUrl = Main.class.getResource("game-info-panel.fxml");
            FXMLLoader leftLoader = new FXMLLoader(leftPanelUrl);
            VBox leftPanel = leftLoader.load();
            myInfoPanel = leftLoader.getController();

            GameInfoPanel myInfo = new GameInfoPanel(true, false);
            myInfoPanel.setData(myInfo);
            mainBorderPane.setLeft(leftPanel);

            // Права панель (супротивник)
            URL rightPanelUrl = Main.class.getResource("game-info-panel.fxml");
            FXMLLoader rightLoader = new FXMLLoader(rightPanelUrl);
            VBox rightPanel = rightLoader.load();
            opponentInfoPanel = rightLoader.getController();

            GameInfoPanel opponentInfo = new GameInfoPanel(false, true);
            opponentInfoPanel.setData(opponentInfo);
            mainBorderPane.setRight(rightPanel);

        } catch (Exception e) {
            System.err.println("Error loading info panels: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createSlideMenu() {
        overlayLayer = new StackPane();
        overlayLayer.setStyle("-fx-background-color: rgba(0,0,0,0.4);");
        overlayLayer.setVisible(false);

        sideMenu = new VBox(15);
        sideMenu.setStyle("""
                -fx-background-color: rgba(30,30,46,0.97);
                -fx-padding: 20;
                -fx-border-color: #444;
                -fx-border-width: 0 0 0 1;
                """);
        sideMenu.setPrefWidth(220);
        sideMenu.setTranslateX(220);

        Button leaveBtn = new Button("Покинути гру");
        Button rulesBtn = new Button("Правила гри");
        Button settingsBtn = new Button("Налаштування");

        leaveBtn.getStyleClass().add("menu-button");
        rulesBtn.getStyleClass().add("menu-button");
        settingsBtn.getStyleClass().add("menu-button");

        leaveBtn.setOnAction(e -> handleLeaveGame());
        rulesBtn.setOnAction(e -> handleShowRules());
        settingsBtn.setOnAction(e -> handleSettings());

        sideMenu.getChildren().addAll(leaveBtn, rulesBtn, settingsBtn);

        overlayLayer.getChildren().add(sideMenu);
        overlayLayer.setOnMouseClicked(e -> {
            if (menuVisible) toggleMenu();
        });

        StackPane menuContainer = new StackPane(overlayLayer);
        StackPane.setAlignment(overlayLayer, javafx.geometry.Pos.CENTER_RIGHT);
    }

    private void toggleMenu() {
        TranslateTransition slide = new TranslateTransition(Duration.millis(300), sideMenu);
        FadeTransition fade = new FadeTransition(Duration.millis(300), overlayLayer);

        if (!menuVisible) {
            overlayLayer.setVisible(true);
            slide.setFromX(220);
            slide.setToX(0);
            fade.setFromValue(0);
            fade.setToValue(1);
        } else {
            slide.setFromX(0);
            slide.setToX(220);
            fade.setFromValue(1);
            fade.setToValue(0);
            fade.setOnFinished(e -> overlayLayer.setVisible(false));
        }

        slide.play();
        fade.play();
        menuVisible = !menuVisible;
    }

    private void handleLeaveGame() {
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Підтвердження");
        confirmation.setHeaderText("Покинути гру?");
        confirmation.setContentText("Ви впевнені, що хочете покинути поточну гру?");

        confirmation.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                // Відправити повідомлення на сервер
                if (clientManager.isConnected()) {
                    clientManager.leaveRoom();
                }
                returnToLobby();
            }
        });
    }

    /**
     * Повернення до лобі
     */
    private void returnToLobby() {
        try {
            URL fxmlUrl = Main.class.getResource("lobby-view.fxml");
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Scene lobbyScene = new Scene(loader.load());

            Stage stage = (Stage) mainBorderPane.getScene().getWindow();
            stage.setScene(lobbyScene);
            stage.centerOnScreen();

            fullCleanup();

        } catch (Exception e) {
            System.err.println("Error returning to lobby: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Показати помилку
     */
    private void showError(String message) {
        new GameAlertDialog(
                AlertVariant.ERROR,
                "Chyba",
                message,
                () -> System.out.println(message),
                null,
                true
        ).show();
    }

    /**
     * Ініціалізація гри з даними кімнати
     */
    public void initGame(String roomName, String opponent, PieceColor myColor,
                         boolean myTurn, int[][] initialBoardState) {
        this.roomName = roomName;
        this.opponentName = opponent;
        this.myColor = myColor;
        this.isMyTurn = myTurn;


        // ✅ Підготуй дошку ДО лямбди
        final int[][] boardToSet;
        if (myColor == PieceColor.BLACK) {
            boardToSet = BoardRotation.rotateBoard(initialBoardState);
            System.out.println("Initial board rotated for BLACK player");
        } else {
            boardToSet = initialBoardState;
        }

        Platform.runLater(() -> {
            roomNameLbl.setText(roomName);

            // ✅ Використовуй final змінну
            board.setBoardState(boardToSet);

            board.setCurrentTurn(myColor);

            updateTurnIndicator();

            System.out.println("Game initialized:");
            System.out.println("  Room: " + roomName);
            System.out.println("  Opponent: " + opponent);
            System.out.println("  My color: " + myColor);
            System.out.println("  My turn: " + myTurn);
            System.out.println("  Board state set successfully");
        });
    }


    public void fullCleanup() {
        clientManager.unregisterMessageHandler(OpCode.GAME_START);
        clientManager.unregisterMessageHandler(OpCode.GAME_STATE);
        clientManager.unregisterMessageHandler(OpCode.INVALID_MOVE);
        clientManager.unregisterMessageHandler(OpCode.GAME_END);
        clientManager.unregisterMessageHandler(OpCode.ROOM_LEFT);
    }


    private void handleSettings() {
        System.out.println("⚙️ Відкриття налаштувань");
    }

    private void handleShowRules() {
        System.out.println("📜 Відображення правил гри");
    }

    public PieceColor getMyColor() {
        return myColor;
    }

}
