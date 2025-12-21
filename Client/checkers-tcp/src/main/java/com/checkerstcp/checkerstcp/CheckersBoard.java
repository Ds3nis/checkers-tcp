package com.checkerstcp.checkerstcp;

import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.awt.event.ActionListener;

public class CheckersBoard extends GridPane{
    private static final int SIZE = 8;
    private static final Color LIGHT_COLOR = Color.web("#D2B48C");
    private static final Color DARK_COLOR = Color.web("#5C4033");

    private double cellSize;

    public CheckersBoard() {
        setHgap(0);
        setVgap(0);
        setAlignment(Pos.CENTER);
        buildBoard();

        widthProperty().addListener((obs, oldVal, newVal) -> resizeBoard());
        heightProperty().addListener((obs, oldVal, newVal) -> resizeBoard());
    }

    private void buildBoard() {
        getChildren().clear();

        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                StackPane cell = new StackPane();
                cell.getStyleClass().add((row + col) % 2 == 0 ? "cell-light" : "cell-dark");
                add(cell, col, row);
            }
        }
        placePieces();
    }

    private void resizeBoard() {
        double size = Math.min(getWidth(), getHeight());
        cellSize = size / SIZE;

        setPrefSize(size, size);

        for (var node : getChildren()) {
            if (node instanceof Region region) {
                region.setPrefSize(cellSize, cellSize);
            }
        }
    }


    private void placePieces() {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < SIZE; col++) {
                if ((row + col) % 2 != 0) {
                    addPiece(row, col, Color.RED);
                }
            }
        }

        for (int row = SIZE - 3; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if ((row + col) % 2 != 0) {
                    addPiece(row, col, Color.BLACK);
                }
            }
        }
    }

    private void addPiece(int row, int col, Color color) {
        Circle piece = new Circle(20, color);
        piece.getStyleClass().add("piece");
        StackPane cell = getCell(row, col);
        cell.getChildren().add(piece);
    }

    private StackPane getCell(int row, int col) {
        for (var node : getChildren()) {
            if (getRowIndex(node) == row && getColumnIndex(node) == col) {
                return (StackPane) node;
            }
        }
        return null;
    }

    // приклад анімації
    public void animateMove(int fromRow, int fromCol, int toRow, int toCol) {
        StackPane fromCell = getCell(fromRow, fromCol);
        StackPane toCell = getCell(toRow, toCol);

        if (fromCell == null || toCell == null) return;

        Circle piece = (Circle) fromCell.getChildren().get(0);

        double dx = (toCol - fromCol) * cellSize;
        double dy = (toRow - fromRow) * cellSize;

        TranslateTransition tt = new TranslateTransition(Duration.millis(400), piece);
        tt.setByX(dx);
        tt.setByY(dy);
        tt.setOnFinished(e -> {
            fromCell.getChildren().clear();
            piece.setTranslateX(0);
            piece.setTranslateY(0);
            toCell.getChildren().add(piece);
        });
        tt.play();
    }
}
