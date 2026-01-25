package com.checkerstcp.checkerstcp;

/**
 * Game room status states.
 */
public enum RoomStatus {
    WAITING_FOR_PLAYERS,  // Waiting for second player
    PLAYING,              // Game in progress
    FULL,                 // Room has 2 players
    FINISHED              // Game completed
}