package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.GameInfoPanel;
import com.checkerstcp.checkerstcp.GameRoom;
import com.checkerstcp.checkerstcp.Main;
import com.checkerstcp.checkerstcp.Player;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;

public class GameController implements Initializable {

    @FXML
    private BorderPane mainBorderPane;

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
    private Label netStateLbl;

    @FXML
    private Label numOfPiecesLbl;

    @FXML
    private Label pieceColorNamelbl;

    @FXML
    private ImageView pieceImage;

    @FXML
    private HBox playerInfoPanel;

    @FXML
    private Label playerMoveStatusLbl;

    @FXML
    private Label playerMovesLbl;

    @FXML
    private Label playerNameLbl;

    @FXML
    private ScrollPane playerScrollMoves;

    @FXML
    private Label roomLbl;

    @FXML
    private Label roomNameLbl;

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
}
