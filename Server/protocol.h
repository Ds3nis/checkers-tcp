//
// Created by Denis on 16.11.2025.
//

#ifndef SERVER_PROTOCOL_H
#define SERVER_PROTOCOL_H

#define PREFIX "DENTCP"
#define PREFIX_LEN 6
#define MAX_MESSAGE_LEN 8192
#define MAX_DATA_LEN (MAX_MESSAGE_LEN - PREFIX_LEN - 7) // PREFIX|OP|LEN|

// Operation codes
typedef enum {
    OP_LOGIN = 1,
    OP_LOGIN_OK = 2,
    OP_LOGIN_FAIL = 3,
    OP_CREATE_ROOM = 4,
    OP_JOIN_ROOM = 5,
    OP_ROOM_JOINED = 6,
    OP_ROOM_FULL = 7,
    OP_ROOM_FAIL = 8,
    OP_GAME_START = 9,
    OP_MOVE = 10,
    OP_INVALID_MOVE = 11,
    OP_GAME_STATE = 12,
    OP_GAME_END = 13,
    OP_LEAVE_ROOM = 14,
    OP_ROOM_LEFT = 15,
    OP_PING = 16,
    OP_PONG = 17,
    OP_LIST_ROOMS = 18,
    OP_ROOMS_LIST = 19,
    OP_ROOM_CREATED = 20,
    OP_ERROR = 500
} OpCode;

// Message structure
typedef struct {
    OpCode op;
    int len;
    char data[MAX_DATA_LEN];
} Message;

// Function prototypes
int parse_message(const char *buffer, Message *msg);
int create_message(char *buffer, OpCode op, const char *data);
void log_message(const char *prefix, const Message *msg);



#endif //SERVER_PROTOCOL_H