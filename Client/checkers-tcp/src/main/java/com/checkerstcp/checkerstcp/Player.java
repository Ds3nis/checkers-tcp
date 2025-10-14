package com.checkerstcp.checkerstcp;

import java.time.LocalDate;
import java.util.Random;

public class Player {

    private String login;


    public Player(String login) {
        this.login = login;
    }

    public Player() {
        this(generateRandomName());
    }

    private static String generateRandomName(){
        return "Player" + new Random().nextInt(9999) + LocalDate.now().toString();
    }
}
