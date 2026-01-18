package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.*;
import com.checkerstcp.checkerstcp.network.ClientManager;
import com.checkerstcp.checkerstcp.network.ConnectionStatusDialog;
import com.checkerstcp.checkerstcp.network.Message;
import com.checkerstcp.checkerstcp.network.OpCode;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
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
    private StackPane rootStackPane;

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
    private ConnectionStatusDialog reconnectDialog;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        clientManager = ClientManager.getInstance();
        board = new CheckersBoard();
        setupMessageHandlers();
        StackPane container = new StackPane(board);
        mainBorderPane.setCenter(container);
        reconnectDialog = clientManager.getReconnectDialog();

        setupReconnectCallbacks();
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
        board.setOnMoveAttempt(move -> {
            if (!isMyTurn) {
                showError("Počkejte na svůj tah!");
                return;
            }

            sendMoveToServer(move);

            isMyTurn = false;
            updateTurnIndicator();
        });

        board.setOnPieceSelected(piece -> {
            System.out.println("Selected: " + piece);
        });
    }

    private void setupReconnectCallbacks() {
        clientManager.setOnConnectionLost(() -> {
            Platform.runLater(() -> {
                System.out.println("Connection lost in game!");
                reconnectDialog.show();

                if (board != null) {
                    board.setDisable(true);
                }
            });
        });


        clientManager.setOnReconnectSuccess(() -> {
            Platform.runLater(() -> {
                System.out.println("Connection restored in game!");
                if (board != null) {
                    board.setDisable(false);
                }
            });
        });

        clientManager.setOnReconnectFailed(() -> {
            Platform.runLater(() -> {
                System.err.println("Reconnect failed in game!");

                new GameAlertDialog(
                        AlertVariant.ERROR,
                        "Připojení ztraceno",
                        "Nepodařilo se obnovit připojení k serveru.\n" +
                                "Budete přesměrováni zpět do lobby.",
                        this::returnToLobby,
                        null,
                        false
                ).show();
            });
        });

        reconnectDialog.setOnCancel(() -> {
            Platform.runLater(() -> {
                clientManager.disconnect();
                returnToLobby();
            });
        });
    }

    private void setupMessageHandlers() {
        clientManager.registerMessageHandler(OpCode.GAME_START, this::handleGameStart);
        clientManager.registerMessageHandler(OpCode.GAME_STATE, this::handleGameState);
        clientManager.registerMessageHandler(OpCode.INVALID_MOVE, this::handleInvalidMove);
        clientManager.registerMessageHandler(OpCode.GAME_END, this::handleGameEnd);
        clientManager.registerMessageHandler(OpCode.ROOM_LEFT, this::handlePlayerLeft);
        clientManager.registerMessageHandler(OpCode.PLAYER_DISCONNECTED, this::handleOpponentDisconnected);
        clientManager.registerMessageHandler(OpCode.PLAYER_RECONNECTED, this::handleOpponentReconnected);
        clientManager.registerMessageHandler(OpCode.GAME_PAUSED, this::handleGamePaused);
        clientManager.registerMessageHandler(OpCode.GAME_RESUMED, this::handleGameResumed);
    }

    private void handleOpponentDisconnected(Message message) {
        Platform.runLater(() -> {
            String[] parts = message.getData().split(",");
            if (parts.length >= 2) {
                String roomName = parts[0];
                String playerName = parts[1];

                System.out.println("Opponent " + playerName + " disconnected");

                reconnectDialog.showOpponentDisconnected(playerName);

                if (board != null) {
                    board.setDisable(true);
                }

                netStateLbl.setText("Čekání na protivníka");
                netStateLbl.setStyle("-fx-text-fill: orange;");
            }
        });
    }

    private void handleOpponentReconnected(Message message) {
        Platform.runLater(() -> {
            String[] parts = message.getData().split(",");
            if (parts.length >= 2) {
                String roomName = parts[0];
                String playerName = parts[1];

                System.out.println("Opponent " + playerName + " reconnected");

                reconnectDialog.showOpponentReconnected(playerName);

                if (board != null) {
                    board.setDisable(false);
                }

                updateTurnIndicator();
            }
        });
    }

    private void handleGamePaused(Message message) {
        Platform.runLater(() -> {
            String roomName = message.getData();
            System.out.println("Game paused in room: " + roomName);

            netStateLbl.setText("Hra pozastavena");
            netStateLbl.setStyle("-fx-text-fill: orange;");

            if (board != null) {
                board.setDisable(true);
            }
        });
    }

    private void handleGameResumed(Message message) {
        Platform.runLater(() -> {
            String roomName = message.getData();
            System.out.println("Game resumed in room: " + roomName);

            netStateLbl.setText("Hra obnovena!");
            netStateLbl.setStyle("-fx-text-fill: green;");

            if (board != null) {
                board.setDisable(false);
            }

            updateTurnIndicator();

            javafx.animation.PauseTransition pause =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
            pause.setOnFinished(e -> updateTurnIndicator());
            pause.play();
        });
    }

    private void handleGameStart(Message message) {
        Platform.runLater(() -> {
            String[] parts = message.getData().split(",");
            if (parts.length >= 4) {
                roomName = parts[0];
                String player1 = parts[1];
                String player2 = parts[2];
                String firstTurn = parts[3];

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

                updateInfoPanels();

                System.out.println("Game started! My color: " + myColor + ", My turn: " + isMyTurn);
            }
        });
    }

    private void handleGameState(Message message) {
        Platform.runLater(() -> {
            try {
                String jsonData = message.getData();
                System.out.println("GAME_STATE received: " + jsonData);

                int[][] boardState = GameStateParser.parseBoardFromJson(jsonData);

                System.out.println("My color:" + myColor);
                System.out.println("My turn:" + isMyTurn);
                System.out.println(Arrays.deepToString(board.getBoardState()));

                if (myColor == PieceColor.BLACK) {
                    boardState = BoardRotation.rotateBoard(boardState);
                    System.out.println("Board rotated for BLACK player");
                }

                if (board.isAnimating()) {
                    System.out.println("Animation in progress, deferring sync");
                    deferredServerState = boardState;
                    return;
                }

                syncBoardIfNeeded(boardState);

                String currentTurnPlayer = GameStateParser.getJsonValue(jsonData, "current_turn");
                System.out.println("Current turn player: " + currentTurnPlayer);
                if (currentTurnPlayer != null) {
                    isMyTurn = currentTurnPlayer.equals(clientManager.getCurrentClientId());
                    System.out.println("Current turn: " + currentTurnPlayer + ", My turn: " + isMyTurn);
                }

                PieceColor turnColor = isMyTurn ? myColor : myColor.opposite();
                board.setCurrentTurn(turnColor);

                updateTurnIndicator();
                updatePiecesCount();

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
            isMyTurn = true;
            board.setCurrentTurn(myColor);
            updateTurnIndicator();
        });
    }

    private void handleGameEnd(Message message) {
        Platform.runLater(() -> {
            String[] parts = message.getData().split(",");
            String winner = parts.length > 0 ? parts[0] : "Neznámý";
            String reason = parts.length > 1 ? parts[1] : "konec hry";

            boolean iWon = winner.equals(clientManager.getCurrentClientId());

            new GameAlertDialog(
                    iWon ? AlertVariant.SUCCESS : AlertVariant.WARNING,
                    iWon ? "Gratulujeme! Vyhráli jste!" : "Prohráli jste",
                    "Důvod: " + reason,
                    this::returnToLobby,
                    null,
                    false
            ).show();
        });
    }

    private void handlePlayerLeft(Message message) {
        String[] parts = message.getData().split(",");
        String roomName = parts[0];
        String playerName = parts[1];
        Platform.runLater(() -> {
            new GameAlertDialog(
                    AlertVariant.WARNING,
                    "Hráč " + playerName + " opustil hru",
                    "Soupeř se odpojil. Hra skončila.",
                    this::returnToLobby,
                    null,
                    false
            ).show();
        });
    }


    private void sendMoveToServer(Move move) {
        if (clientManager.isConnected() && roomName != null) {
            if (move.isMultiCapture() && move.getPath().size() > 0) {
                sendMultiMoveToServer(move);
            } else {
                sendSingleMoveToServer(move);
            }
        }
    }

    private void sendSingleMoveToServer(Move move) {
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

            System.out.println("Coordinates rotated for server: " +
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

    /**
     * Відправка мульти-ходу
     */
    private void sendMultiMoveToServer(Move move) {
        System.out.println("=== SENDING MULTI-MOVE ===");

        // Отримати шлях руху (включає початок і кінець)
        List<Position> path = move.getPath();
        System.out.println("Path size: " + path.size());

        // Якщо шлях не заповнений - створити його з початку та кінця
        if (path.isEmpty()) {
            path = new ArrayList<>();
            path.add(new Position(move.getFromRow(), move.getFromCol()));
            path.add(new Position(move.getToRow(), move.getToCol()));
        }

        // Якщо чорний гравець - ротувати всі координати
        List<Position> serverPath = new ArrayList<>();
        if (myColor == PieceColor.BLACK) {
            for (Position pos : path) {
                int rotatedRow = 7 - pos.getRow();
                int rotatedCol = 7 - pos.getCol();
                serverPath.add(new Position(rotatedRow, rotatedCol));
                System.out.println("Rotated (" + pos.getRow() + "," + pos.getCol() + ") -> " +
                        "(" + rotatedRow + "," + rotatedCol + ")");
            }
        } else {
            serverPath = new ArrayList<>(path);
        }

        pendingMove = move;
        board.executeMove(move);
        addMoveToHistory(move);

        clientManager.sendMultiMove(serverPath);

        System.out.println("Multi-move sent with " + serverPath.size() + " positions");
    }

    private void addMoveToHistory(Move move) {
        Position from = new Position(move.getFromRow(), move.getFromCol());
        Position to = new Position(move.getToRow(), move.getToCol());

        LocalTime timestamp = LocalTime.now().withNano(0);

        PlayerMoveItem moveItem = new PlayerMoveItem(
                from.toString(),
                to.toString(),
                timestamp
        );

        moveHistory.add(moveItem);

        // Додати хід тільки на панель поточного гравця
        if (myInfoPanel != null) {
            myInfoPanel.addMove(moveItem);
        }
    }

    private void updateTurnIndicator() {
        if (isMyTurn) {
            netStateLbl.setText("Váš tah!");
            netStateLbl.setStyle("-fx-text-fill: green; -fx-font-weight: bold;");

            if (myInfoPanel != null) {
                myInfoPanel.updateStatus("Váš tah!");
            }
            if (opponentInfoPanel != null) {
                opponentInfoPanel.updateStatus("Čeká...");
            }
        } else {
            netStateLbl.setText("Pohyb soupeře...");
            netStateLbl.setStyle("-fx-text-fill: orange;");

            if (myInfoPanel != null) {
                myInfoPanel.updateStatus("Čeká...");
            }
            if (opponentInfoPanel != null) {
                opponentInfoPanel.updateStatus("Táhne...");
            }
        }
    }

    private void updateConnectionStatus() {
        clientManager.statusMessageProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                if (!isMyTurn && newVal != null) {
                    roomLbl.setText(newVal);
                }
            });
        });
    }

    private void setupGameInfoPanels() {
        try {
            URL leftPanelUrl = Main.class.getResource("game-info-panel.fxml");
            FXMLLoader leftLoader = new FXMLLoader(leftPanelUrl);
            VBox leftPanel = leftLoader.load();
            myInfoPanel = leftLoader.getController();

            GameInfoPanel myInfo = new GameInfoPanel();
            myInfo.setCurrentPlayer(true);
            myInfoPanel.setData(myInfo);
            mainBorderPane.setLeft(leftPanel);

            URL rightPanelUrl = Main.class.getResource("game-info-panel.fxml");
            FXMLLoader rightLoader = new FXMLLoader(rightPanelUrl);
            VBox rightPanel = rightLoader.load();
            opponentInfoPanel = rightLoader.getController();

            GameInfoPanel opponentInfo = new GameInfoPanel();
            opponentInfo.setCurrentPlayer(false);
            opponentInfoPanel.setData(opponentInfo);
            mainBorderPane.setRight(rightPanel);

        } catch (Exception e) {
            System.err.println("Error loading info panels: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Оновити інформацію на панелях
     */
    private void updateInfoPanels() {
        if (myInfoPanel != null && myColor != null) {
            String myName = clientManager.getCurrentClientId();
            int myPieces = countPieces(myColor);
            String myStatus = isMyTurn ? "Váš tah!" : "Čeká...";

            myInfoPanel.updatePlayerInfo(myName, myColor, myPieces, myStatus);
        }

        if (opponentInfoPanel != null && myColor != null && opponentName != null) {
            PieceColor opponentColor = myColor.opposite();
            int opponentPieces = countPieces(opponentColor);
            String opponentStatus = isMyTurn ? "Čeká..." : "Táhne...";

            opponentInfoPanel.updatePlayerInfo(opponentName, opponentColor, opponentPieces, opponentStatus);
        }
    }

    /**
     * Оновити кількість шашок на панелях
     */
    private void updatePiecesCount() {
        if (myInfoPanel != null && myColor != null) {
            int myPieces = countPieces(myColor);
            myInfoPanel.updatePiecesCount(myPieces);
        }

        if (opponentInfoPanel != null && myColor != null) {
            PieceColor opponentColor = myColor.opposite();
            int opponentPieces = countPieces(opponentColor);
            opponentInfoPanel.updatePiecesCount(opponentPieces);
        }
    }

    private int countPieces(PieceColor color) {
        int[][] boardState = board.getBoardState();
        int count = 0;

        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                PieceType type = PieceType.fromValue(boardState[row][col]);
                if (type != PieceType.EMPTY) {
                    PieceColor pieceColor = PieceColor.fromPieceType(type);
                    if (pieceColor == color) {
                        count++;
                    }
                }
            }
        }

        return count;
    }

    private void createSlideMenu() {
        overlayLayer = new StackPane();
        overlayLayer.setStyle("-fx-background-color: rgba(0,0,0,0.5);");
        overlayLayer.setVisible(false);
        overlayLayer.setMouseTransparent(false);
        overlayLayer.setPickOnBounds(true);

        sideMenu = new VBox(15);
        sideMenu.setStyle("""
        -fx-background-color: #1e1e2e;
        -fx-padding: 20;
        -fx-border-color: #89b4fa;
        -fx-border-width: 0 0 0 2;
        -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 10, 0, -5, 0);
    """);
        sideMenu.setPrefWidth(250);
        sideMenu.setMaxHeight(Double.MAX_VALUE);

        HBox menuHeader = new HBox(10);
        menuHeader.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label menuTitle = new Label("☰ Menu");
        menuTitle.setStyle("""
        -fx-font-size: 18px;
        -fx-font-weight: bold;
        -fx-text-fill: #cdd6f4;
    """);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕");
        closeBtn.setStyle("""
        -fx-background-color: transparent;
        -fx-text-fill: #cdd6f4;
        -fx-font-size: 20px;
        -fx-padding: 5 10;
        -fx-cursor: hand;
        -fx-background-radius: 5;
    """);
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle("""
        -fx-background-color: #45475a;
        -fx-text-fill: #f38ba8;
        -fx-font-size: 20px;
        -fx-padding: 5 10;
        -fx-cursor: hand;
        -fx-background-radius: 5;
    """));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle("""
        -fx-background-color: transparent;
        -fx-text-fill: #cdd6f4;
        -fx-font-size: 20px;
        -fx-padding: 5 10;
        -fx-cursor: hand;
        -fx-background-radius: 5;
    """));
        closeBtn.setOnAction(e -> toggleMenu());

        menuHeader.getChildren().addAll(menuTitle, headerSpacer, closeBtn);

        Separator separator = new Separator();
        separator.setStyle("-fx-background-color: #45475a;");

        Button leaveBtn = createMenuButton("🚪 Opustit hru", "#f38ba8");
        Button rulesBtn = createMenuButton("📜 Pravidla hry", "#89b4fa");
        Button infoBtn = createMenuButton("ℹ️ Informace o hře", "#a6e3a1");

        leaveBtn.setOnAction(e -> handleLeaveGame());
        rulesBtn.setOnAction(e -> handleShowRules());
        infoBtn.setOnAction(e -> handleShowGameInfo());

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label connectionInfo = new Label("Připojeno k: " + (roomName != null ? roomName : "N/A"));
        connectionInfo.setStyle("""
        -fx-font-size: 10px;
        -fx-text-fill: #6c7086;
        -fx-padding: 10 0 0 0;
    """);

        sideMenu.getChildren().addAll(
                menuHeader,
                separator,
                leaveBtn,
                rulesBtn,
                infoBtn,
                spacer,
                connectionInfo
        );

        StackPane.setAlignment(sideMenu, javafx.geometry.Pos.CENTER_RIGHT);
        overlayLayer.getChildren().add(sideMenu);

        sideMenu.setOnMouseClicked(e -> e.consume());

        overlayLayer.setOnMouseClicked(e -> {
            if (menuVisible) {
                toggleMenu();
                e.consume();
            }
        });

        rootStackPane.getChildren().add(overlayLayer);
    }

    private Button createMenuButton(String text, String accentColor) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setStyle(String.format("""
        -fx-background-color: #313244;
        -fx-text-fill: #cdd6f4;
        -fx-font-size: 14px;
        -fx-padding: 12 20;
        -fx-cursor: hand;
        -fx-background-radius: 8;
        -fx-border-radius: 8;
        -fx-border-color: transparent;
        -fx-border-width: 2;
        -fx-alignment: CENTER_LEFT;
    """));

        btn.setOnMouseEntered(e -> btn.setStyle(String.format("""
        -fx-background-color: #45475a;
        -fx-text-fill: %s;
        -fx-font-size: 14px;
        -fx-padding: 12 20;
        -fx-cursor: hand;
        -fx-background-radius: 8;
        -fx-border-radius: 8;
        -fx-border-color: %s;
        -fx-border-width: 2;
        -fx-alignment: CENTER_LEFT;
    """, accentColor, accentColor)));

        btn.setOnMouseExited(e -> btn.setStyle("""
        -fx-background-color: #313244;
        -fx-text-fill: #cdd6f4;
        -fx-font-size: 14px;
        -fx-padding: 12 20;
        -fx-cursor: hand;
        -fx-background-radius: 8;
        -fx-border-radius: 8;
        -fx-border-color: transparent;
        -fx-border-width: 2;
        -fx-alignment: CENTER_LEFT;
    """));

        return btn;
    }

    private void toggleMenu() {
        if (!menuVisible) {
            overlayLayer.setVisible(true);
            overlayLayer.setOpacity(0);
            sideMenu.setTranslateX(250);

            FadeTransition fadeIn = new FadeTransition(Duration.millis(300), overlayLayer);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);

            TranslateTransition slideIn = new TranslateTransition(Duration.millis(300), sideMenu);
            slideIn.setFromX(250);
            slideIn.setToX(0);

            fadeIn.play();
            slideIn.play();

            menuVisible = true;
        } else {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(300), overlayLayer);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);

            TranslateTransition slideOut = new TranslateTransition(Duration.millis(300), sideMenu);
            slideOut.setFromX(0);
            slideOut.setToX(250);

            fadeOut.setOnFinished(e -> overlayLayer.setVisible(false));

            fadeOut.play();
            slideOut.play();

            menuVisible = false;
        }
    }

    private void handleShowGameInfo() {
        toggleMenu();

        String colorText = myColor != null ?
                (myColor == PieceColor.WHITE ? "Bílé ♟" : "Černé ♟") : "N/A";

        String turnText = isMyTurn ? "Ano ✓" : "Ne ⏳";

        String info = String.format("""
        🏠 Místnost: %s
        👤 Vaše barva: %s
        🎮 Soupeř: %s
        ⏱️ Váš tah: %s
        📊 Počet tahů: %d
        """,
                roomName != null ? roomName : "N/A",
                colorText,
                opponentName != null ? opponentName : "N/A",
                turnText,
                moveHistory.size()
        );

        new GameAlertDialog(
                AlertVariant.INFO,
                "Informace o hře",
                info,
                () -> System.out.println("Info dialog closed"),
                null,
                false
        ).show();
    }

    private void handleShowRules() {
        toggleMenu();

        String rules = """
        📜 Základní pravidla dámy:
        
        • Bílí začínají první
        • Pohyb: diagonálně o jedno pole
        • Skok: přes soupeřovu figurku
        • Dáma: dosažením opačného konce
        • Dáma se může pohybovat na více polí
        • Povinný skok přes soupeře
        • Vítězí hráč, který sebere všechny figurky
        """;

        new GameAlertDialog(
                AlertVariant.INFO,
                "Pravidla hry",
                rules,
                () -> System.out.println("Rules dialog closed"),
                null,
                false
        ).show();
    }

    private void handleLeaveGame() {
        toggleMenu();

        new GameAlertDialog(
                AlertVariant.WARNING,
                "Opustit hru?",
                "Opravdu chcete opustit současnou hru?",
                () -> {
                    if (clientManager.isConnected()) {
                        clientManager.leaveRoom();
                    }
                    returnToLobby();
                },
                () -> System.out.println("Leave cancelled"),
                true
        ).show();
    }

    private void returnToLobby() {
        try {
            fullCleanup();
            URL fxmlUrl = Main.class.getResource("lobby-view.fxml");
            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            Scene lobbyScene = new Scene(loader.load());

            Stage stage = (Stage) mainBorderPane.getScene().getWindow();
            stage.setScene(lobbyScene);
            stage.centerOnScreen();


        } catch (Exception e) {
            System.err.println("Error returning to lobby: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void showError(String message) {
        new GameAlertDialog(
                AlertVariant.ERROR,
                "Chyba",
                message,
                () -> System.out.println(message),
                null,
                false
        ).show();
    }

    public void initGame(String roomName, String opponent, PieceColor myColor,
                         boolean myTurn, int[][] initialBoardState) {
        this.roomName = roomName;
        this.opponentName = opponent;
        this.myColor = myColor;
        this.isMyTurn = myTurn;
        board.setMyColor(myColor);

        final int[][] boardToSet;
        if (myColor == PieceColor.BLACK) {
            boardToSet = BoardRotation.rotateBoard(initialBoardState);
            System.out.println("Initial board rotated for BLACK player");
        } else {
            boardToSet = initialBoardState;
        }

        Platform.runLater(() -> {
            roomNameLbl.setText(roomName);

            board.setBoardState(boardToSet);

            board.setCurrentTurn(myColor);

            updateTurnIndicator();

            updateInfoPanels();

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

        clientManager.unregisterMessageHandler(OpCode.PLAYER_DISCONNECTED);
        clientManager.unregisterMessageHandler(OpCode.PLAYER_RECONNECTED);
        clientManager.unregisterMessageHandler(OpCode.GAME_PAUSED);
        clientManager.unregisterMessageHandler(OpCode.GAME_RESUMED);

        clientManager.setOnConnectionLost(null);
        clientManager.setOnReconnectSuccess(null);
        clientManager.setOnReconnectFailed(null);

        if (reconnectDialog != null && reconnectDialog.isShowing()) {
            reconnectDialog.hide();
        }
    }

    public PieceColor getMyColor() {
        return myColor;
    }
}