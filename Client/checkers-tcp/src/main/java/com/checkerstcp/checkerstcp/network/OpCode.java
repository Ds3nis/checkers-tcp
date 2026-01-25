package com.checkerstcp.checkerstcp.network;

/**
 * Protocol operation codes for client-server communication.
 * Defines all supported operations in the checkers game protocol.
 * Each operation has a unique numeric code and human-readable name.
 *
 * <p>Protocol format: DENTCP|OP|LEN|DATA\n
 * where OP is the two-digit operation code defined here.
 *
 * <p>Operation categories:
 * <ul>
 *   <li>Authentication (1-3): Login operations</li>
 *   <li>Room management (4-8, 14-15, 18-20): Create, join, leave rooms</li>
 *   <li>Game flow (9-13, 21, 28-29): Game start, moves, state updates</li>
 *   <li>Connection monitoring (16-17): PING/PONG heartbeat</li>
 *   <li>Reconnection (22-27): Disconnect/reconnect handling</li>
 *   <li>Error handling (500): General errors</li>
 * </ul>
 */
public enum OpCode {
    // Authentication
    LOGIN(1, "LOGIN"),                    // Client login request
    LOGIN_OK(2, "LOGIN_OK"),              // Login successful
    LOGIN_FAIL(3, "LOGIN_FAIL"),          // Login failed

    // Room management
    CREATE_ROOM(4, "CREATE_ROOM"),        // Create new game room
    JOIN_ROOM(5, "JOIN_ROOM"),            // Join existing room
    ROOM_JOINED(6, "ROOM_JOINED"),        // Successfully joined room
    ROOM_FULL(7, "ROOM_FULL"),            // Room is full (2 players max)
    ROOM_FAIL(8, "ROOM_FAIL"),            // Room operation failed
    ROOM_CREATED(20, "ROOM_CREATED"),     // Room created successfully
    LEAVE_ROOM(14, "LEAVE_ROOM"),         // Leave current room
    ROOM_LEFT(15, "ROOM_LEFT"),           // Successfully left room
    LIST_ROOMS(18, "LIST_ROOMS"),         // Request room list
    ROOMS_LIST(19, "ROOMS_LIST"),         // Room list response (JSON)

    // Game flow
    GAME_START(9, "GAME_START"),          // Game started with both players
    MOVE(10, "MOVE"),                     // Single move
    MULTI_MOVE(21, "MULTI_MOVE"),         // Multi-jump move sequence
    INVALID_MOVE(11, "INVALID_MOVE"),     // Move validation failed
    GAME_STATE(12, "GAME_STATE"),         // Board state update (JSON)
    GAME_END(13, "GAME_END"),             // Game finished
    GAME_PAUSED(28, "GAME_PAUSED"),       // Game paused (player disconnected)
    GAME_RESUMED(29, "GAME_RESUMED"),     // Game resumed (player reconnected)

    // Connection monitoring
    PING(16, "PING"),                     // Heartbeat ping
    PONG(17, "PONG"),                     // Heartbeat pong response

    // Reconnection
    PLAYER_DISCONNECTED(22, "PLAYER_DISCONNECTED"),     // Opponent disconnected
    PLAYER_RECONNECTING(23, "PLAYER_RECONNECTING"),     // Opponent reconnecting
    PLAYER_RECONNECTED(24, "PLAYER_RECONNECTED"),       // Opponent reconnected
    RECONNECT_REQUEST(25, "RECONNECT_REQUEST"),         // Request session restore
    RECONNECT_OK(26, "RECONNECT_OK"),                   // Reconnection successful
    RECONNECT_FAIL(27, "RECONNECT_FAIL"),               // Reconnection failed

    // Error handling
    ERROR(500, "ERROR");                  // General error message

    private final int code;
    private final String name;

    /**
     * Constructs an OpCode with numeric code and name.
     *
     * @param code Numeric operation code (sent in protocol)
     * @param name Human-readable operation name
     */
    OpCode(int code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * Gets the numeric code for this operation.
     *
     * @return Numeric operation code
     */
    public int getCode() {
        return code;
    }

    /**
     * Gets the human-readable name for this operation.
     *
     * @return Operation name
     */
    public String getName() {
        return name;
    }

    /**
     * Finds an OpCode by its numeric code value.
     * Used when parsing messages from the server.
     *
     * @param code Numeric operation code
     * @return Corresponding OpCode enum, or null if code is invalid
     */
    public static OpCode fromCode(int code) {
        for (OpCode op : values()) {
            if (op.code == code) {
                return op;
            }
        }
        return null;
    }

    /**
     * Returns string representation of this operation.
     * Format: "NAME(code)"
     *
     * @return Formatted string with name and code
     */
    @Override
    public String toString() {
        return name + "(" + code + ")";
    }
}