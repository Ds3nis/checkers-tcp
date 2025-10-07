package com.checkerstcp.checkerstcp.controller;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.net.URL;
import java.util.ResourceBundle;

public class LobbyController implements Initializable {

    public TextField serverField;
    public Button connectButton;
    public VBox roomList;
    public Button refreshButton;
    public Button createRoomButton;
    public Label netState;
    public Label emptyLabel;
    public ListView roomsList;
    public Button createRoomBtn;
    public Button refreshBtn;
    public Label statusLabel;
    public Button disconnectBtn;
    public Button connectBtn;
    public TextField hostField;
    public BorderPane root;
    public Label roomTitle;
    public Label players;
    public Label status;
    public Button joinBtn;

    public LobbyController() {

    }


    @Override
    public void initialize(URL location, ResourceBundle resources) {

    }
}
