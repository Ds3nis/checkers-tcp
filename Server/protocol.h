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
    OP_MULTI_MOVE = 21,
    OP_PLAYER_DISCONNECTED = 22,
    OP_PLAYER_RECONNECTING = 23,
    OP_PLAYER_RECONNECTED = 24,
    OP_RECONNECT_REQUEST = 25,
    OP_RECONNECT_OK = 26,
    OP_RECONNECT_FAIL = 27,
    OP_GAME_PAUSED = 28,
    OP_GAME_RESUMED = 29,
    OP_ERROR = 500
} OpCode;

// Message structure
typedef struct {
    OpCode op;
    int len;
    char data[MAX_DATA_LEN];
} Message;


typedef enum {
    DISCONNECT_REASON_INVALID_PREFIX,
    DISCONNECT_REASON_INVALID_FORMAT,
    DISCONNECT_REASON_INVALID_OPCODE,
    DISCONNECT_REASON_INVALID_LENGTH,
    DISCONNECT_REASON_DATA_MISMATCH,
    DISCONNECT_REASON_BUFFER_OVERFLOW,
    DISCONNECT_REASON_TOO_MANY_VIOLATIONS,
    DISCONNECT_REASON_SUSPICIOUS_ACTIVITY
} DisconnectReason;

typedef struct {
    int invalid_message_count;      // Кількість невалідних повідомлень
    int unknown_opcode_count;       // Кількість невідомих OpCode
    time_t last_violation_time;     // Час останнього порушення
} ClientViolations;

#define MAX_VIOLATIONS 1
#define VIOLATION_RESET_TIME 60

// Function prototypes
int parse_message(const char *buffer, Message *msg, DisconnectReason *disconnect_reason);
int create_message(char *buffer, OpCode op, const char *data);
void log_message(const char *prefix, const Message *msg);

bool is_valid_opcode(int op);

bool is_numeric_string(const char *str, int len);

bool should_disconnect_client(ClientViolations *violations);

void log_security_violation(int client_socket, const char *client_id,
                            DisconnectReason reason, const char *raw_message);

const char* get_disconnect_reason_string(DisconnectReason reason);


void disconnect_malicious_client(struct Server *server, struct Client *client,
                                DisconnectReason reason, const char *raw_message);

#endif //SERVER_PROTOCOL_H