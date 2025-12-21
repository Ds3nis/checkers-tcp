package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.*;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;

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

    private List<GameInfoPanel>  gameInfoPanels =  new ArrayList<>(2);

    private void getGameInfoPanels() {
        GameInfoPanel gameInfoPanel;

        gameInfoPanel = new GameInfoPanel(true, false);
        gameInfoPanels.add(gameInfoPanel);
        gameInfoPanel = new GameInfoPanel(false, true);
        gameInfoPanels.add(gameInfoPanel);
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        getGameInfoPanels();
        CheckersBoard board = new CheckersBoard();
        StackPane container = new StackPane(board);
        mainBorderPane.setCenter(container);
        container.setOnMouseClicked(e -> board.animateMove(2, 1, 3, 2));
        createSlideMenu();
        burgerMenuBtn.setOnAction(e -> toggleMenu());
        try {
            for (GameInfoPanel gameInfoPanel : gameInfoPanels) {
                URL fxmlUrl = Main.class.getResource("game-info-panel.fxml");
                FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                        fxmlUrl,
                        "FXML 'game-info-panel.fxml' nebyl nalezen v src/main/resources/com/checkerstcp/checkerstcp/"
                ));

                VBox panel = loader.load();
                GameInfoPanelController gameInfoPanelController = loader.getController();
                gameInfoPanelController.setData(gameInfoPanel);

                if (gameInfoPanel.isLeft()){
                    mainBorderPane.setLeft(panel);
                }else if (gameInfoPanel.isRight()){
                    mainBorderPane.setRight(panel);
                }
            }
        }catch (Exception e){

        }

    }

    private void createSlideMenu() {
        // затемнений шар поверх гри
        overlayLayer = new StackPane();
        overlayLayer.setStyle("-fx-background-color: rgba(0,0,0,0.4);");
        overlayLayer.setVisible(false);

        // меню (виїжджає з правого боку)
        sideMenu = new VBox(15);
        sideMenu.setStyle("""
                -fx-background-color: rgba(30,30,46,0.97);
                -fx-padding: 20;
                -fx-border-color: #444;
                -fx-border-width: 0 0 0 1;
                """);
        sideMenu.setPrefWidth(220);
        sideMenu.setTranslateX(220); // стартова позиція – поза екраном праворуч

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

        // накладаємо меню на основну BorderPane
        overlayLayer.getChildren().add(sideMenu);
        overlayLayer.setOnMouseClicked(e -> {
            // натискання поза меню закриває його
            if (menuVisible) toggleMenu();
        });

        // додаємо до правого боку BorderPane
        mainBorderPane.setRight(overlayLayer);
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

    private void handleSettings() {
        System.out.println("⚙️ Відкриття налаштувань");
    }

    private void handleShowRules() {
        System.out.println("📜 Відображення правил гри");
    }

    private void handleLeaveGame() {
        System.out.println("🚪 Повернення до лобі");
    }
}
