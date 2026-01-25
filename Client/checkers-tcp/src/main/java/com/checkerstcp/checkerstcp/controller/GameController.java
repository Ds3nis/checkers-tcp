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

/**
 * Controller for the main game view.
 * Manages the checkers game board, player interactions, move validation,
 * server communication, and reconnection handling.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Game board visualization and interaction</li>
 *   <li>Move execution and server synchronization</li>
 *   <li>Turn management and validation</li>
 *   <li>Player information panels</li>
 *   <li>Connection loss handling and reconnection</li>
 *   <li>Game state synchronization with server</li>
 *   <li>Opponent disconnect/reconnect notifications</li>
 *   <li>Move history tracking</li>
 * </ul>
 *
 * <p>Board orientation:
 * <ul>
 *   <li>WHITE players see board in standard orientation</li>
 *   <li>BLACK players see rotated board (180 degrees)</li>
 *   <li>All moves are translated to server coordinates</li>
 * </ul>
 */
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

    // Deferred server state application (wait for animation to finish)
    private int[][] deferredServerState;
    private ConnectionStatusDialog reconnectDialog;

    /**
     * Initializes the controller.
     * Sets up the game board, message handlers, UI components,
     * and connection callbacks.
     *
     * @param location URL location (unused)
     * @param resources ResourceBundle (unused)
     */
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

        // Apply deferred state after animation completes
        board.setOnAnimationFinished(() -> {
            if (deferredServerState != null) {
                System.out.println("Applying deferred server state");
                syncBoardIfNeeded(deferredServerState);
                deferredServerState = null;
                pendingMove = null;
            }
        });
    }

    /**
     * Sets up callbacks for board interactions.
     * Handles move attempts and piece selection events.
     */
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

    /**
     * Sets up reconnection callbacks for connection loss handling.
     * Manages UI state during disconnection and reconnection attempts.
     */
    private void setupReconnectCallbacks() {
        clientManager.setOnConnectionLost(() -> {
            Platform.runLater(() -> {
                System.out.println("Connection lost in game!");
                reconnectDialog.show();

                // Disable board during reconnection
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

    /**
     * Registers message handlers for server protocol messages.
     * Maps OpCodes to their respective handler methods.
     */
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

    /**
     * Handles opponent disconnection notification.
     * Disables board and displays waiting dialog.
     *
     * Protocol format: "room_name,player_name"
     *
     * @param message PLAYER_DISCONNECTED message from server
     */
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

    /**
     * Handles opponent reconnection notification.
     * Re-enables board and displays success message.
     *
     * Protocol format: "room_name,player_name"
     *
     * @param message PLAYER_RECONNECTED message from server
     */
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

    /**
     * Handles game pause notification.
     * Disables board interaction during pause.
     *
     * @param message GAME_PAUSED message containing room name
     */
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

    /**
     * Handles game resume notification.
     * Re-enables board and updates turn indicator.
     *
     * @param message GAME_RESUMED message containing room name
     */
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

            // Clear status message after 2 seconds
            javafx.animation.PauseTransition pause =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
            pause.setOnFinished(e -> updateTurnIndicator());
            pause.play();
        });
    }

    /**
     * Handles game start notification from server.
     * Initializes game state with player colors and turn order.
     *
     * Protocol format: "room_name,player1,player2,first_turn"
     *
     * @param message GAME_START message from server
     */
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

    /**
     * Handles board state update from server.
     * Parses JSON board state, applies board rotation for BLACK player,
     * and synchronizes with local board state.
     *
     * <p>Synchronization strategy:
     * <ul>
     *   <li>If animation in progress: Defer state update until animation completes</li>
     *   <li>If boards match: Skip update to avoid unnecessary rendering</li>
     *   <li>Otherwise: Apply server state immediately</li>
     * </ul>
     *
     * Protocol format: JSON with board array and current_turn field
     *
     * @param message GAME_STATE message from server
     */
    private void handleGameState(Message message) {
        Platform.runLater(() -> {
            try {
                String jsonData = message.getData();
                System.out.println("GAME_STATE received: " + jsonData);

                int[][] boardState = GameStateParser.parseBoardFromJson(jsonData);

                System.out.println("My color:" + myColor);
                System.out.println("My turn:" + isMyTurn);
                System.out.println(Arrays.deepToString(board.getBoardState()));

                // Rotate board for BLACK player perspective
                if (myColor == PieceColor.BLACK) {
                    boardState = BoardRotation.rotateBoard(boardState);
                    System.out.println("Board rotated for BLACK player");
                }

                // Defer sync if animation in progress
                if (board.isAnimating()) {
                    System.out.println("Animation in progress, deferring sync");
                    deferredServerState = boardState;
                    return;
                }

                syncBoardIfNeeded(boardState);

                // Update turn indicator
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

    /**
     * Synchronizes local board with server state if they differ.
     * Compares boards element-by-element before applying changes.
     *
     * @param serverState Board state from server
     */
    private void syncBoardIfNeeded(int[][] serverState) {
        int[][] localState = board.getBoardState();

        if (boardsEqual(localState, serverState)) {
            System.out.println("Board already in sync");
            return;
        }

        System.out.println("Board out of sync, applying server state");
        board.setBoardState(serverState);
    }

    /**
     * Checks if two board states are identical.
     *
     * @param a First board state
     * @param b Second board state
     * @return true if boards are equal
     */
    private boolean boardsEqual(int[][] a, int[][] b) {
        if (a.length != b.length) return false;

        for (int i = 0; i < a.length; i++) {
            if (!Arrays.equals(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Handles invalid move notification from server.
     * Restores player's turn and displays error message.
     *
     * @param message INVALID_MOVE message with reason
     */
    private void handleInvalidMove(Message message) {
        Platform.runLater(() -> {
            showError("Neplatný tah: " + message.getData());
            isMyTurn = true;
            board.setCurrentTurn(myColor);
            updateTurnIndicator();
        });
    }

    /**
     * Handles game end notification from server.
     * Displays result dialog and returns to lobby.
     *
     * Protocol format: "winner_name,reason"
     *
     * @param message GAME_END message from server
     */
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

    /**
     * Handles player left notification.
     * Displays notification and returns to lobby.
     *
     * Protocol format: "room_name,player_name"
     *
     * @param message ROOM_LEFT message from server
     */
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

    /**
     * Sends move to server with appropriate coordinate transformation.
     * Routes to single-move or multi-move handler based on move type.
     *
     * @param move Move to send
     */
    private void sendMoveToServer(Move move) {
        if (clientManager.isConnected() && roomName != null) {
            if (move.isMultiCapture() && move.getPath().size() > 0) {
                sendMultiMoveToServer(move);
            } else {
                sendSingleMoveToServer(move);
            }
        }
    }

    /**
     * Sends single move to server.
     * Rotates coordinates for BLACK player before transmission.
     * Executes local animation and adds move to history.
     *
     * @param move Single move to send
     */
    private void sendSingleMoveToServer(Move move) {
        int fromRow = move.getFromRow();
        int fromCol = move.getFromCol();
        int toRow = move.getToRow();
        int toCol = move.getToCol();

        // Rotate coordinates for BLACK player
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
     * Sends multi-jump move sequence to server.
     * Rotates entire path for BLACK player before transmission.
     *
     * <p>Path construction:
     * <ul>
     *   <li>If path already filled: Use existing path</li>
     *   <li>If path empty: Construct from start and end positions</li>
     * </ul>
     *
     * @param move Multi-capture move with path
     */
    private void sendMultiMoveToServer(Move move) {
        System.out.println("=== SENDING MULTI-MOVE ===");

        // Get move path (includes start and end)
        List<Position> path = move.getPath();
        System.out.println("Path size: " + path.size());

        // Construct path if not filled
        if (path.isEmpty()) {
            path = new ArrayList<>();
            path.add(new Position(move.getFromRow(), move.getFromCol()));
            path.add(new Position(move.getToRow(), move.getToCol()));
        }

        // Rotate all coordinates for BLACK player
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

    /**
     * Adds move to history and updates player info panel.
     * Records timestamp and position information.
     *
     * @param move Move to add to history
     */
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

        // Add move only to current player's panel
        if (myInfoPanel != null) {
            myInfoPanel.addMove(moveItem);
        }
    }

    /**
     * Updates turn indicator UI elements.
     * Updates status labels and player info panels based on current turn.
     */
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

    /**
     * Updates connection status display.
     * Binds status label to ClientManager's status property.
     */
    private void updateConnectionStatus() {
        clientManager.statusMessageProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                if (!isMyTurn && newVal != null) {
                    roomLbl.setText(newVal);
                }
            });
        });
    }

    /**
     * Sets up player information panels (left and right).
     * Loads FXML and initializes controllers for both players.
     */
    private void setupGameInfoPanels() {
        try {
            // Left panel (current player)
            URL leftPanelUrl = Main.class.getResource("game-info-panel.fxml");
            FXMLLoader leftLoader = new FXMLLoader(leftPanelUrl);
            VBox leftPanel = leftLoader.load();
            myInfoPanel = leftLoader.getController();

            GameInfoPanel myInfo = new GameInfoPanel();
            myInfo.setCurrentPlayer(true);
            myInfoPanel.setData(myInfo);
            mainBorderPane.setLeft(leftPanel);

            // Right panel (opponent)
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
     * Updates player information on both info panels.
     * Displays names, colors, piece counts, and status.
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
     * Updates piece count displays on info panels.
     * Called after each move to reflect current board state.
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

    /**
     * Counts pieces of specified color on the board.
     *
     * @param color Color to count
     * @return Number of pieces of that color
     */
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

    /**
     * Creates slide-out side menu with game options.
     * Implements smooth animation and overlay darkening.
     */
    private void createSlideMenu() {
        // Create overlay layer
        overlayLayer = new StackPane();
        overlayLayer.setStyle("-fx-background-color: rgba(0,0,0,0.5);");
        overlayLayer.setVisible(false);
        overlayLayer.setMouseTransparent(false);
        overlayLayer.setPickOnBounds(true);

        // Create side menu with Catppuccin styling
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

        // Menu header with close button
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

        // Close button hover effects
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

        // Menu buttons
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

        // Prevent menu clicks from closing overlay
        sideMenu.setOnMouseClicked(e -> e.consume());

        // Close menu when clicking overlay
        overlayLayer.setOnMouseClicked(e -> {
            if (menuVisible) {
                toggleMenu();
                e.consume();
            }
        });

        rootStackPane.getChildren().add(overlayLayer);
    }

    /**
     * Creates styled menu button with hover effects.
     *
     * @param text Button text
     * @param accentColor Hover accent color
     * @return Configured button
     */
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

        // Hover effect
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

    /**
     * Toggles side menu visibility with smooth animations.
     * Implements fade and slide transitions.
     */
    private void toggleMenu() {
        if (!menuVisible) {
            // Show menu
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
            // Hide menu
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

    /**
     * Displays game information dialog.
     * Shows room, colors, opponent, turn status, and move count.
     */
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

    /**
     * Displays checkers rules dialog.
     */
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

    /**
     * Handles leave game request.
     * Shows confirmation dialog before leaving.
     */
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

    /**
     * Returns to lobby view.
     * Performs full cleanup before scene transition.
     */
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

    /**
     * Shows error dialog to user.
     *
     * @param message Error message to display
     */
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

    /**
     * Initializes game with parameters from server.
     * Called when game starts or client reconnects.
     * Applies board rotation for BLACK player.
     *
     * @param roomName Name of the game room
     * @param opponent Opponent player name
     * @param myColor This player's piece color
     * @param myTurn Whether it's this player's turn
     * @param initialBoardState Initial board state from server
     */
    public void initGame(String roomName, String opponent, PieceColor myColor,
                         boolean myTurn, int[][] initialBoardState) {
        this.roomName = roomName;
        this.opponentName = opponent;
        this.myColor = myColor;
        this.isMyTurn = myTurn;
        board.setMyColor(myColor);

        // Rotate board for BLACK player
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

    /**
     * Performs complete cleanup before leaving game.
     * Unregisters message handlers and callbacks.
     * Hides reconnection dialog if visible.
     */
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

    /**
     * Gets this player's piece color.
     *
     * @return Player's piece color
     */
    public PieceColor getMyColor() {
        return myColor;
    }
}