package com.checkerstcp.checkerstcp.controller;

import com.checkerstcp.checkerstcp.PlayerMoveItem;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Controller for individual move history items.
 * Displays a single move in the player's move history list.
 *
 * <p>Shows:
 * <ul>
 *   <li>Move notation (from → to)</li>
 *   <li>Timestamp of when move was made</li>
 * </ul>
 *
 * <p>Example display: "A3 → B4" with timestamp "14:32:15"
 */
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

    /**
     * Sets the move data and updates display.
     * Formats move as "from → to" notation with timestamp.
     *
     * @param playerMoveItem Move information to display
     */
    public void setPlayerMoveItem(PlayerMoveItem playerMoveItem) {
        this.playerMoveItem = playerMoveItem;

        // Display move notation with arrow
        this.fromToMoveLbl.setText(
                playerMoveItem.getFromCellMove() + " → " + playerMoveItem.getToCellMove()
        );

        // Display move timestamp
        this.moveTimeLbl.setText(playerMoveItem.getMoveTime().toString());
    }
}
