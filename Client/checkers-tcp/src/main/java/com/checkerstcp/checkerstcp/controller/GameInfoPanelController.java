package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.GameInfoPanel;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;

public class GameInfoPanelController {

    @FXML
    private Label numOfPiecesLbl;

    @FXML
    private Label pieceColorNamelbl;

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


    public void setData(GameInfoPanel gameInfoPanel) {

    }

}
