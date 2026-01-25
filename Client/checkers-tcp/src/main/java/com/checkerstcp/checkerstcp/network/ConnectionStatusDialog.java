package com.checkerstcp.checkerstcp.network;

import com.checkerstcp.checkerstcp.network.ReconnectManager.ReconnectStatus;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;
import javafx.util.Duration;

/**
 * Dialog for displaying connection status and reconnection progress.
 * Provides visual feedback during connection loss, automatic reconnection,
 * and manual reconnection attempts.
 *
 * <p>Dialog states:
 * <ul>
 *   <li>SHORT_DISCONNECT (0-40s): Automatic reconnection with progress indicator</li>
 *   <li>LONG_DISCONNECT (40-80s): Manual reconnection button available</li>
 *   <li>CRITICAL_TIMEOUT (80+s): Connection permanently lost, return to lobby</li>
 *   <li>RECONNECTED: Success message with auto-close</li>
 * </ul>
 *
 * <p>Features:
 * <ul>
 *   <li>Real-time status updates with attempt counter and duration</li>
 *   <li>Smooth fade-in/fade-out transitions</li>
 *   <li>Context-aware button visibility</li>
 *   <li>Opponent disconnect/reconnect notifications</li>
 *   <li>Catppuccin color scheme styling</li>
 * </ul>
 */
public class ConnectionStatusDialog {
    private Stage stage;
    private Label statusLabel;
    private Label detailsLabel;
    private ProgressIndicator progressIndicator;
    private Button cancelButton;
    private Button manualReconnectBtn;
    private VBox contentBox;

    private Runnable onCancel;
    private Runnable onManualReconnect;
    private boolean isShowing = false;

    /**
     * Constructs a new ConnectionStatusDialog with default styling.
     */
    public ConnectionStatusDialog() {
        createDialog();
    }

    /**
     * Creates and configures the dialog UI components.
     * Sets up layout, styling, and event handlers.
     */
    private void createDialog() {
        stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("Stav připojení");

        // Main container with Catppuccin Mocha theme
        contentBox = new VBox(20);
        contentBox.setAlignment(Pos.CENTER);
        contentBox.setPadding(new Insets(30));
        contentBox.setStyle("""
            -fx-background-color: #1e1e2e;
            -fx-background-radius: 15;
            -fx-border-color: #89b4fa;
            -fx-border-width: 2;
            -fx-border-radius: 15;
            -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0, 0, 5);
        """);

        // Progress indicator
        progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(60, 60);
        progressIndicator.setStyle("-fx-progress-color: #89b4fa;");

        // Status label
        statusLabel = new Label("Kontrola připojení...");
        statusLabel.setStyle("""
            -fx-font-size: 18px;
            -fx-font-weight: bold;
            -fx-text-fill: #cdd6f4;
        """);

        // Details label
        detailsLabel = new Label("");
        detailsLabel.setStyle("""
            -fx-font-size: 14px;
            -fx-text-fill: #a6adc8;
            -fx-wrap-text: true;
        """);
        detailsLabel.setMaxWidth(350);
        detailsLabel.setAlignment(Pos.CENTER);

        // Manual reconnect button (initially hidden)
        manualReconnectBtn = new Button("Zkusit znovu");
        manualReconnectBtn.setVisible(false);
        manualReconnectBtn.setManaged(false);
        manualReconnectBtn.setStyle("""
            -fx-background-color: #89b4fa;
            -fx-text-fill: #1e1e2e;
            -fx-font-size: 14px;
            -fx-font-weight: bold;
            -fx-padding: 10 20;
            -fx-cursor: hand;
            -fx-background-radius: 8;
        """);

        // Button hover effects
        manualReconnectBtn.setOnMouseEntered(e -> manualReconnectBtn.setStyle("""
            -fx-background-color: #b4befe;
            -fx-text-fill: #1e1e2e;
            -fx-font-size: 14px;
            -fx-font-weight: bold;
            -fx-padding: 10 20;
            -fx-cursor: hand;
            -fx-background-radius: 8;
        """));


        manualReconnectBtn.setOnMouseExited(e -> manualReconnectBtn.setStyle("""
            -fx-background-color: #89b4fa;
            -fx-text-fill: #1e1e2e;
            -fx-font-size: 14px;
            -fx-font-weight: bold;
            -fx-padding: 10 20;
            -fx-cursor: hand;
            -fx-background-radius: 8;
        """));

        manualReconnectBtn.setOnAction(e -> handleManualReconnect());

        // Cancel button
        cancelButton = new Button("Zrušit pokus");
        cancelButton.setStyle("""
            -fx-background-color: #45475a;
            -fx-text-fill: #cdd6f4;
            -fx-font-size: 14px;
            -fx-padding: 10 20;
            -fx-cursor: hand;
            -fx-background-radius: 8;
        """);

        cancelButton.setOnMouseEntered(e -> cancelButton.setStyle("""
            -fx-background-color: #f38ba8;
            -fx-text-fill: #1e1e2e;
            -fx-font-size: 14px;
            -fx-padding: 10 20;
            -fx-cursor: hand;
            -fx-background-radius: 8;
        """));

        cancelButton.setOnMouseExited(e -> cancelButton.setStyle("""
            -fx-background-color: #45475a;
            -fx-text-fill: #cdd6f4;
            -fx-font-size: 14px;
            -fx-padding: 10 20;
            -fx-cursor: hand;
            -fx-background-radius: 8;
        """));

        cancelButton.setOnAction(e -> {
            if (onCancel != null) {
                onCancel.run();
            }
            hide();
        });

        contentBox.getChildren().addAll(
                progressIndicator,
                statusLabel,
                detailsLabel,
                manualReconnectBtn,
                cancelButton
        );

        Scene scene = new Scene(contentBox);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);

        // Prevent user from closing dialog directly
        stage.setOnCloseRequest(e -> e.consume());
    }

    /**
     * Handles manual reconnection button click.
     * Disables button during attempt and executes callback in background thread.
     */
    private void handleManualReconnect() {
        manualReconnectBtn.setDisable(true);
        manualReconnectBtn.setText("Připojování...");

        // Execute callback in background thread
        if (onManualReconnect != null) {
            new Thread(() -> {
                onManualReconnect.run();

                // Re-enable button if still visible after attempt
                Platform.runLater(() -> {
                    if (manualReconnectBtn.isVisible()) {
                        manualReconnectBtn.setDisable(false);
                        manualReconnectBtn.setText("Zkusit znovu");
                    }
                });
            }).start();
        }
    }

    /**
     * Shows the dialog with fade-in animation.
     * Thread-safe: Can be called from any thread.
     */
    public void show() {
        if (isShowing) {
            return;
        }

        Platform.runLater(() -> {
            stage.show();
            stage.centerOnScreen();
            isShowing = true;

            FadeTransition fade = new FadeTransition(Duration.millis(300), contentBox);
            fade.setFromValue(0);
            fade.setToValue(1);
            fade.play();
        });
    }

    /**
     * Hides the dialog with fade-out animation.
     * Thread-safe: Can be called from any thread.
     */
    public void hide() {
        if (!isShowing) {
            return;
        }

        Platform.runLater(() -> {
            FadeTransition fade = new FadeTransition(Duration.millis(300), contentBox);
            fade.setFromValue(1);
            fade.setToValue(0);
            fade.setOnFinished(e -> {
                stage.hide();
                isShowing = false;
            });
            fade.play();
        });
    }

    /**
     * Updates dialog based on reconnection status.
     * Adjusts UI elements, messages, and button visibility for each state.
     *
     * @param status Current reconnection status
     * @param attempts Number of reconnection attempts made
     * @param duration Duration of disconnection in seconds
     */
    public void updateConnectionStatus(ReconnectStatus status,
                                       int attempts, long duration) {
        Platform.runLater(() -> {
            switch (status) {
                case SHORT_DISCONNECT:
                    // 0-40 seconds: Automatic reconnection
                    progressIndicator.setVisible(true);
                    statusLabel.setText("Automatické připojování...");
                    statusLabel.setStyle("""
                        -fx-font-size: 18px;
                        -fx-font-weight: bold;
                        -fx-text-fill: #89b4fa;
                    """);
                    detailsLabel.setText(String.format(
                            "Pokus %d\nOdpojeno: %d sek",
                            attempts, duration
                    ));

                    // Hide manual reconnect button
                    manualReconnectBtn.setVisible(false);
                    manualReconnectBtn.setManaged(false);

                    // Show cancel button
                    cancelButton.setVisible(true);
                    cancelButton.setManaged(true);
                    cancelButton.setText("Zrušit pokus");
                    break;

                case LONG_DISCONNECT:
                    // 40-80 seconds: Show manual reconnect button

                    progressIndicator.setVisible(true);
                    statusLabel.setText("Dlouhé odpojení");
                    statusLabel.setStyle("""
                        -fx-font-size: 18px;
                        -fx-font-weight: bold;
                        -fx-text-fill: #f9e2af;
                    """);
                    detailsLabel.setText(String.format(
                            "Odpojeno: %d sek\n\n" +
                                    "Stiskněte tlačítko pro pokus o připojení",
                            duration
                    ));

                    // Show manual reconnect button
                    manualReconnectBtn.setVisible(true);
                    manualReconnectBtn.setManaged(true);
                    manualReconnectBtn.setDisable(false);
                    manualReconnectBtn.setText("Zkusit znovu");

                    // Change cancel button text
                    cancelButton.setVisible(true);
                    cancelButton.setManaged(true);
                    cancelButton.setText("Vrátit se do lobby");
                    break;

                case CRITICAL_TIMEOUT:
                    // 80+ seconds: Server disconnected client
                    progressIndicator.setVisible(false);
                    statusLabel.setText("Připojení ztraceno");
                    statusLabel.setStyle("""
                        -fx-font-size: 18px;
                        -fx-font-weight: bold;
                        -fx-text-fill: #f38ba8;
                    """);
                    detailsLabel.setText(
                            "Překročena maximální doba čekání.\n\n" +
                                    "Budete vráceni do lobby."
                    );

                    // Hide all buttons
                    manualReconnectBtn.setVisible(false);
                    manualReconnectBtn.setManaged(false);
                    cancelButton.setVisible(false);
                    cancelButton.setManaged(false);

                    // Auto-close after 3 seconds
                    PauseTransition pause = new PauseTransition(Duration.seconds(3));
                    pause.setOnFinished(e -> {
                        hide();
                        if (onCancel != null) {
                            onCancel.run();
                        }
                    });
                    pause.play();
                    break;

                case RECONNECTED:
                    // Successfully reconnected
                    progressIndicator.setVisible(false);
                    statusLabel.setText("Připojení obnoveno!");
                    statusLabel.setStyle("""
                        -fx-font-size: 18px;
                        -fx-font-weight: bold;
                        -fx-text-fill: #a6e3a1;
                    """);
                    detailsLabel.setText("Hra pokračuje...");

                    // Hide all buttons
                    manualReconnectBtn.setVisible(false);
                    manualReconnectBtn.setManaged(false);
                    cancelButton.setVisible(false);
                    cancelButton.setManaged(false);

                    // Auto-close after 2 seconds
                    PauseTransition pauseReconnected = new PauseTransition(Duration.seconds(2));
                    pauseReconnected.setOnFinished(e -> hide());
                    pauseReconnected.play();
                    break;
            }
        });
    }

    /**
     * Shows notification that opponent has disconnected.
     * Displays pause message and wait indicator.
     *
     * @param opponentName Name of disconnected opponent
     */
    public void showOpponentDisconnected(String opponentName) {
        Platform.runLater(() -> {
            show();
            progressIndicator.setVisible(true);
            statusLabel.setText("Hra pozastavena");
            statusLabel.setStyle("""
                -fx-font-size: 18px;
                -fx-font-weight: bold;
                -fx-text-fill: #f9e2af;
            """);
            detailsLabel.setText(String.format(
                    "Soupeř %s ztratil připojení\n\nČekáme na jeho návrat...",
                    opponentName
            ));

            manualReconnectBtn.setVisible(false);
            manualReconnectBtn.setManaged(false);

            cancelButton.setText("Opustit hru");
            cancelButton.setVisible(true);
            cancelButton.setManaged(true);
        });
    }

    /**
     * Shows notification that opponent has reconnected.
     * Displays success message and auto-closes after brief delay.
     *
     * @param opponentName Name of reconnected opponent
     */
    public void showOpponentReconnected(String opponentName) {
        Platform.runLater(() -> {
            show();
            progressIndicator.setVisible(false);
            statusLabel.setText("Hra obnovena!");
            statusLabel.setStyle("""
                -fx-font-size: 18px;
                -fx-font-weight: bold;
                -fx-text-fill: #a6e3a1;
            """);
            detailsLabel.setText(String.format(
                    "Soupeř %s se vrátil\n\nPokračujeme ve hře!",
                    opponentName
            ));

            manualReconnectBtn.setVisible(false);
            manualReconnectBtn.setManaged(false);
            cancelButton.setVisible(false);
            cancelButton.setManaged(false);

            // Auto-hide after 2 seconds
            PauseTransition pause = new PauseTransition(Duration.seconds(2));
            pause.setOnFinished(e -> hide());
            pause.play();
        });
    }

    // ========== Callback setters ==========

    /**
     * Sets callback for cancel button action.
     *
     * @param callback Runnable to execute when cancel is clicked
     */
    public void setOnCancel(Runnable callback) {
        this.onCancel = callback;
    }

    /**
     * Sets callback for manual reconnect button action.
     *
     * @param callback Runnable to execute when manual reconnect is clicked
     */
    public void setOnManualReconnect(Runnable callback) {
        this.onManualReconnect = callback;
    }

    /**
     * Checks if dialog is currently showing.
     *
     * @return true if dialog is visible
     */
    public boolean isShowing() {
        return isShowing;
    }
}