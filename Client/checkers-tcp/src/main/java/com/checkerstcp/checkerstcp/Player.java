package com.checkerstcp.checkerstcp;

import java.time.LocalDate;
import java.util.Random;

/**
 * Represents a player in the game.
 * Can be initialized with custom login or auto-generated name.
 */
public class Player {

    private String login;


    public Player(String login) {
        this.login = login;
    }

    /**
     * Creates player with randomly generated name.
     */
    public Player() {
        this(generateRandomName());
    }

    /**
     * Generates random player name with current date.
     *
     * @return Generated player name
     */
    private static String generateRandomName(){
        return "Player" + new Random().nextInt(9999) + LocalDate.now().toString();
    }
}
