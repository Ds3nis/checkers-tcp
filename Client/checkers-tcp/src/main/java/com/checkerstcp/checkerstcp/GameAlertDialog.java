package com.checkerstcp.checkerstcp;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.Objects;

public class GameAlertDialog extends Stage {

    private final VBox root = new VBox(16);
    private final Label titleLabel = new Label();
    private final Label descriptionLabel = new Label();
    private final HBox buttonBox = new HBox(12);
    private final StackPane iconWrapper = new StackPane();
    private final ProgressIndicator loader = new ProgressIndicator();
    private double xOffset = 0;
    private double yOffset = 0;


    public GameAlertDialog(
            AlertVariant variant,
            String title,
            String description,
            Runnable onConfirm,
            Runnable onCancel,
            boolean showCancel
    ) {
        initStyle(StageStyle.TRANSPARENT);
        initModality(Modality.APPLICATION_MODAL);

        root.getStyleClass().addAll("game-dialog", variant.name().toLowerCase());

        // Icon
        iconWrapper.getStyleClass().add("dialog-icon");

        if (variant == AlertVariant.LOADING) {
            loader.setPrefSize(32, 32);
            iconWrapper.getChildren().add(loader);
        } else {
            Label icon = new Label(getIconSymbol(variant));
            icon.getStyleClass().add("icon-symbol");
            iconWrapper.getChildren().add(icon);
        }

        // Title
        titleLabel.setText(title);
        titleLabel.getStyleClass().add("dialog-title");

        // Description
        if (description != null) {
            descriptionLabel.setText(description);
            descriptionLabel.getStyleClass().add("dialog-description");
        }

        // Buttons
        if (variant != AlertVariant.LOADING) {
            if (showCancel) {
                Button cancelBtn = new Button("Zrušit");
                cancelBtn.getStyleClass().add("btn-cancel");
                cancelBtn.setOnAction(e -> {
                    if (onCancel != null) onCancel.run();
                    close();
                });
                buttonBox.getChildren().add(cancelBtn);
            }

            Button confirmBtn = new Button("OK");
            confirmBtn.getStyleClass().add("btn-confirm");
            confirmBtn.setOnAction(e -> {
                if (onConfirm != null) onConfirm.run();
                close();
            });

            buttonBox.getChildren().add(confirmBtn);
        }

        buttonBox.getStyleClass().add("dialog-buttons");

        root.getChildren().addAll(iconWrapper, titleLabel);

        if (description != null) {
            root.getChildren().add(descriptionLabel);
        }

        if (variant != AlertVariant.LOADING) {
            root.getChildren().add(buttonBox);
        }

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().addAll(
                Objects.requireNonNull(getClass().getResource("css/modals/dialogs.css")).toExternalForm(),
                Objects.requireNonNull(getClass().getResource("css/modals/success.css")).toExternalForm(),
                Objects.requireNonNull(getClass().getResource("css/modals/info.css")).toExternalForm(),
                Objects.requireNonNull(getClass().getResource("css/modals/warning.css")).toExternalForm(),
                Objects.requireNonNull(getClass().getResource("css/modals/error.css")).toExternalForm(),
                Objects.requireNonNull(getClass().getResource("css/modals/loading.css")).toExternalForm()
        );

        root.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });

        root.setOnMouseDragged(event -> {
            setX(event.getScreenX() - xOffset);
            setY(event.getScreenY() - yOffset);
        });

        setScene(scene);
    }

    private String getIconSymbol(AlertVariant variant) {
        return switch (variant) {
            case SUCCESS -> "✔";
            case ERROR -> "✖";
            case WARNING -> "⚠";
            case INFO -> "ℹ";
            default -> "";
        };
    }
}
