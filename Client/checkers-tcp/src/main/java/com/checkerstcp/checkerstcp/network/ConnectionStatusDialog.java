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
 * Діалог для відображення статусу з'єднання та реконекту
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

    public ConnectionStatusDialog() {
        createDialog();
    }

    private void createDialog() {
        stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("Stav připojení");

        // Головний контейнер
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

        // Прогрес індикатор
        progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(60, 60);
        progressIndicator.setStyle("-fx-progress-color: #89b4fa;");

        // Статус лейбл
        statusLabel = new Label("Kontrola připojení...");
        statusLabel.setStyle("""
            -fx-font-size: 18px;
            -fx-font-weight: bold;
            -fx-text-fill: #cdd6f4;
        """);

        // Деталі
        detailsLabel = new Label("");
        detailsLabel.setStyle("""
            -fx-font-size: 14px;
            -fx-text-fill: #a6adc8;
            -fx-wrap-text: true;
        """);
        detailsLabel.setMaxWidth(350);
        detailsLabel.setAlignment(Pos.CENTER);

        // Кнопка ручного реконекту (спочатку прихована)
        manualReconnectBtn = new Button("Zkusit znovu");
        manualReconnectBtn.setVisible(false);
        manualReconnectBtn.setManaged(false); // Не займає місце коли прихована
        manualReconnectBtn.setStyle("""
            -fx-background-color: #89b4fa;
            -fx-text-fill: #1e1e2e;
            -fx-font-size: 14px;
            -fx-font-weight: bold;
            -fx-padding: 10 20;
            -fx-cursor: hand;
            -fx-background-radius: 8;
        """);

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

        // Кнопка скасування
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

        stage.setOnCloseRequest(e -> e.consume());
    }

    private void handleManualReconnect() {
        manualReconnectBtn.setDisable(true);
        manualReconnectBtn.setText("Připojování...");

        // Викликати callback (буде встановлено з ClientManager)
        if (onManualReconnect != null) {
            new Thread(() -> {
                onManualReconnect.run();

                // Якщо не вдалося - увімкнути кнопку знову
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
     * Показати діалог
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
     * Сховати діалог
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
     * Оновити статус підключення
     */
    public void updateConnectionStatus(ReconnectStatus status,
                                       int attempts, long duration) {
        Platform.runLater(() -> {
            switch (status) {
                case SHORT_DISCONNECT:
                    // 0-40 секунд: автоматичний реконект
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

                    // Сховати кнопку ручного реконекту
                    manualReconnectBtn.setVisible(false);
                    manualReconnectBtn.setManaged(false);

                    // Показати кнопку скасування
                    cancelButton.setVisible(true);
                    cancelButton.setManaged(true);
                    cancelButton.setText("Zrušit pokus");
                    break;

                case LONG_DISCONNECT:
                    // 40-80 секунд: показати кнопку ручного реконекту
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

                    // Показати кнопку ручного реконекту
                    manualReconnectBtn.setVisible(true);
                    manualReconnectBtn.setManaged(true);
                    manualReconnectBtn.setDisable(false);
                    manualReconnectBtn.setText("Zkusit znovu");

                    // Сховати кнопку скасування (або змінити на "Vrátit se do lobby")
                    cancelButton.setVisible(true);
                    cancelButton.setManaged(true);
                    cancelButton.setText("Vrátit se do lobby");
                    break;

                case CRITICAL_TIMEOUT:
                    // 80+ секунд: сервер відключив
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

                    // Сховати всі кнопки
                    manualReconnectBtn.setVisible(false);
                    manualReconnectBtn.setManaged(false);
                    cancelButton.setVisible(false);
                    cancelButton.setManaged(false);

                    // Автоматично закрити через 3 секунди
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
                    progressIndicator.setVisible(false);
                    statusLabel.setText("Připojení obnoveno!");
                    statusLabel.setStyle("""
                        -fx-font-size: 18px;
                        -fx-font-weight: bold;
                        -fx-text-fill: #a6e3a1;
                    """);
                    detailsLabel.setText("Hra pokračuje...");

                    // Сховати всі кнопки
                    manualReconnectBtn.setVisible(false);
                    manualReconnectBtn.setManaged(false);
                    cancelButton.setVisible(false);
                    cancelButton.setManaged(false);

                    // Автоматично закрити через 2 секунди
                    PauseTransition pauseReconnected = new PauseTransition(Duration.seconds(2));
                    pauseReconnected.setOnFinished(e -> hide());
                    pauseReconnected.play();
                    break;
            }
        });
    }

    /**
     * Показати повідомлення про відключення супротивника
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
     * Показати повідомлення про повернення супротивника
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

            // Автоматично сховати через 2 секунди
            PauseTransition pause = new PauseTransition(Duration.seconds(2));
            pause.setOnFinished(e -> hide());
            pause.play();
        });
    }

    // ========== Сеттери для callbacks ==========

    public void setOnCancel(Runnable callback) {
        this.onCancel = callback;
    }

    public void setOnManualReconnect(Runnable callback) {
        this.onManualReconnect = callback;
    }

    public boolean isShowing() {
        return isShowing;
    }
}