package com.checkerstcp.checkerstcp.network;

public enum OpCode {
    LOGIN(1, "LOGIN"),
    LOGIN_OK(2, "LOGIN_OK"),
    LOGIN_FAIL(3, "LOGIN_FAIL"),
    CREATE_ROOM(4, "CREATE_ROOM"),
    JOIN_ROOM(5, "JOIN_ROOM"),
    ROOM_JOINED(6, "ROOM_JOINED"),
    ROOM_FULL(7, "ROOM_FULL"),
    ROOM_FAIL(8, "ROOM_FAIL"),
    GAME_START(9, "GAME_START"),
    MOVE(10, "MOVE"),
    INVALID_MOVE(11, "INVALID_MOVE"),
    GAME_STATE(12, "GAME_STATE"),
    GAME_END(13, "GAME_END"),
    LEAVE_ROOM(14, "LEAVE_ROOM"),
    ROOM_LEFT(15, "ROOM_LEFT"),
    PING(16, "PING"),
    PONG(17, "PONG"),
    ERROR(500, "ERROR");

    private final int code;
    private final String name;

    OpCode(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public static OpCode fromCode(int code) {
        for (OpCode op : values()) {
            if (op.code == code) {
                return op;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return name + "(" + code + ")";
    }
}