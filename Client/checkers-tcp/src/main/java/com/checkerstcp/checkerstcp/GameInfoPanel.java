package com.checkerstcp.checkerstcp;

public class GameInfoPanel {
    private String playerName;
    private PieceColor pieceColor;
    private int numOfPieces;
    private String status;
    private boolean isCurrentPlayer;

    public GameInfoPanel(){
        this.playerName = "";
        this.pieceColor = null;
        this.numOfPieces = 0;
        this.status = "Čekání...";
        this.isCurrentPlayer = false;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public PieceColor getPieceColor() {
        return pieceColor;
    }

    public void setPieceColor(PieceColor pieceColor) {
        this.pieceColor = pieceColor;
    }

    public int getNumOfPieces() {
        return numOfPieces;
    }

    public void setNumOfPieces(int numOfPieces) {
        this.numOfPieces = numOfPieces;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isCurrentPlayer() {
        return isCurrentPlayer;
    }

    public void setCurrentPlayer(boolean currentPlayer) {
        isCurrentPlayer = currentPlayer;
    }
}