package com.checkerstcp.checkerstcp;

public class GameInfoPanel {

    private boolean isRight;

    private boolean isLeft;

    public GameInfoPanel(boolean isLeft, boolean isRight){
        this.isLeft = isLeft;
        this.isRight = isRight;
    }


    public boolean isRight() {
        return isRight;
    }

    public boolean isLeft() {
        return isLeft;
    }
}
