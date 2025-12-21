package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.PlayerMoveItem;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

public class PlayerMoveItemController {

    @FXML
    private Label fromToMoveLbl;

    @FXML
    private VBox leftSectionCard;

    @FXML
    private HBox moveItemCard;

    @FXML
    private Label moveTimeLbl;

    private PlayerMoveItem playerMoveItem;

    public void setPlayerMoveItem(PlayerMoveItem playerMoveItem) {
        this.playerMoveItem = playerMoveItem;
        this.fromToMoveLbl.setText(playerMoveItem.getFromCellMove() + " → " +  playerMoveItem.getToCellMove());
        this.moveTimeLbl.setText(playerMoveItem.getMoveTime().toString());

    }
}
