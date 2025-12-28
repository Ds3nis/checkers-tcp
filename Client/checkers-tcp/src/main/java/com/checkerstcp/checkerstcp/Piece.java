package com.checkerstcp.checkerstcp;

import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.InnerShadow;

public class Piece extends StackPane {
    private final PieceType type;
    private final PieceColor color;
    private boolean isKing;
    private int row;
    private int col;

    private Circle baseCircle;
    private Circle kingMarker;

    private static final double PIECE_RADIUS_RATIO = 0.35;

    public Piece(PieceType type, PieceColor color, int row, int col) {
        this.type = type;
        this.color = color;
        this.isKing = (type == PieceType.WHITE_KING || type == PieceType.BLACK_KING);
        this.row = row;
        this.col = col;

        createVisuals();
    }

    private void createVisuals() {
        baseCircle = new Circle();
        baseCircle.setFill(getPieceColor());
        baseCircle.setStroke(Color.BLACK);
        baseCircle.setStrokeWidth(2);

        DropShadow dropShadow = new DropShadow();
        dropShadow.setRadius(5);
        dropShadow.setOffsetY(2);
        dropShadow.setColor(Color.rgb(0, 0, 0, 0.4));

        InnerShadow innerShadow = new InnerShadow();
        innerShadow.setRadius(3);
        innerShadow.setOffsetY(-1);
        innerShadow.setColor(Color.rgb(255, 255, 255, 0.3));

        baseCircle.setEffect(dropShadow);

        getChildren().add(baseCircle);

        if (isKing) {
            addKingMarker();
        }

        getStyleClass().add("piece");
        if (color == PieceColor.WHITE) {
            getStyleClass().add("white-piece");
        } else {
            getStyleClass().add("black-piece");
        }
    }

    private void addKingMarker() {
        kingMarker = new Circle();
        kingMarker.setFill(Color.GOLD);
        kingMarker.setStroke(Color.DARKGOLDENROD);
        kingMarker.setStrokeWidth(1.5);
        getChildren().add(kingMarker);
    }

    private Color getPieceColor() {
        return switch (color) {
            case WHITE -> Color.web("#F0E68C");
            case BLACK -> Color.web("#8B4513");
        };
    }


    public void updateSize(double cellSize) {
        double radius = cellSize * PIECE_RADIUS_RATIO;
        baseCircle.setRadius(radius);

        if (kingMarker != null) {
            kingMarker.setRadius(radius * 0.4);
        }
    }


    public void promoteToKing() {
        if (!isKing) {
            isKing = true;
            if (kingMarker == null) {
                addKingMarker();
            }
        }
    }


    public void setSelected(boolean selected) {
        if (selected) {
            baseCircle.setStroke(Color.YELLOW);
            baseCircle.setStrokeWidth(4);

            DropShadow glow = new DropShadow();
            glow.setColor(Color.YELLOW);
            glow.setRadius(15);
            baseCircle.setEffect(glow);
        } else {
            baseCircle.setStroke(Color.BLACK);
            baseCircle.setStrokeWidth(2);

            DropShadow normalShadow = new DropShadow();
            normalShadow.setRadius(5);
            normalShadow.setOffsetY(2);
            normalShadow.setColor(Color.rgb(0, 0, 0, 0.4));
            baseCircle.setEffect(normalShadow);
        }
    }


    public void setHovered(boolean hovered) {
        if (hovered) {
            setScaleX(1.1);
            setScaleY(1.1);
        } else {
            setScaleX(1.0);
            setScaleY(1.0);
        }
    }

    public PieceType getType() {
        return type;
    }

    public PieceColor getColor() {
        return color;
    }

    public boolean isKing() {
        return isKing;
    }

    public int getRow() {
        return row;
    }

    public int getCol() {
        return col;
    }

    public void setRow(int row) {
        this.row = row;
    }

    public void setCol(int col) {
        this.col = col;
    }

    public void setPosition(int row, int col) {
        this.row = row;
        this.col = col;
    }

    @Override
    public String toString() {
        return String.format("Piece[%s %s at (%d,%d)%s]",
                color, type, row, col, isKing ? " KING" : "");
    }
}