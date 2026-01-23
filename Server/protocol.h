//
// Created by Denis on 16.11.2025.
//

#ifndef SERVER_PROTOCOL_H
#define SERVER_PROTOCOL_H

#include <stdbool.h>
#include <time.h>
#include <ctype.h>

// Protocol constants
#define PREFIX "DENTCP"              // Protocol identifier prefix
#define PREFIX_LEN 6                 // Length of prefix string
#define MAX_MESSAGE_LEN 8192         // Maximum total message length
#define MAX_DATA_LEN (MAX_MESSAGE_LEN - PREFIX_LEN - 7) // Max payload size

/**
 * Protocol operation codes.
 * Defines all supported client-server operations.
 */
typedef enum {
    // Authentication
    OP_LOGIN = 1,
    OP_LOGIN_OK = 2,
    OP_LOGIN_FAIL = 3,

    // Room management
    OP_CREATE_ROOM = 4,
    OP_JOIN_ROOM = 5,
    OP_ROOM_JOINED = 6,
    OP_ROOM_FULL = 7,
    OP_ROOM_FAIL = 8,
    OP_ROOM_CREATED = 20,
    OP_LEAVE_ROOM = 14,
    OP_ROOM_LEFT = 15,
    OP_LIST_ROOMS = 18,
    OP_ROOMS_LIST = 19,

    // Game flow
    OP_GAME_START = 9,
    OP_MOVE = 10,
    OP_MULTI_MOVE = 21,
    OP_INVALID_MOVE = 11,
    OP_GAME_STATE = 12,
    OP_GAME_END = 13,
    OP_GAME_PAUSED = 28,
    OP_GAME_RESUMED = 29,

    // Connection monitoring
    OP_PING = 16,
    OP_PONG = 17,

    // Reconnection
    OP_PLAYER_DISCONNECTED = 22,
    OP_PLAYER_RECONNECTING = 23,
    OP_PLAYER_RECONNECTED = 24,
    OP_RECONNECT_REQUEST = 25,
    OP_RECONNECT_OK = 26,
    OP_RECONNECT_FAIL = 27,

    // Error handling
    OP_ERROR = 500
} OpCode;

/**
 * Parsed message structure.
 * Represents a complete protocol message after parsing.
 */
typedef struct {
    OpCode op;              // Operation code
    int len;                // Data length
    char data[MAX_DATA_LEN]; // Message payload
} Message;

/**
 * Reasons for disconnecting clients.
 * Used for security logging and violation tracking.
 */
typedef enum {
    DISCONNECT_REASON_INVALID_PREFIX,      // Wrong protocol prefix
    DISCONNECT_REASON_INVALID_FORMAT,      // Malformed message structure
    DISCONNECT_REASON_INVALID_OPCODE,      // OpCode out of valid range
    DISCONNECT_REASON_INVALID_LENGTH,      // Invalid length field
    DISCONNECT_REASON_DATA_MISMATCH,       // Data length doesn't match
    DISCONNECT_REASON_BUFFER_OVERFLOW,     // Attempted buffer overflow
    DISCONNECT_REASON_TOO_MANY_VIOLATIONS, // Exceeded violation threshold
    DISCONNECT_REASON_SUSPICIOUS_ACTIVITY  // Pattern of malicious behavior
} DisconnectReason;

/**
 * Client protocol violation tracking.
 * Tracks repeated violations to detect malicious clients.
 */
typedef struct {
    int invalid_message_count;  // Number of malformed messages
    int unknown_opcode_count;   // Number of invalid opcodes
    time_t last_violation_time; // Timestamp of last violation
} ClientViolations;

#define MAX_VIOLATIONS 1        // Maximum violations before disconnect
#define VIOLATION_RESET_TIME 60 // Time in seconds to reset violation counter

// ========== PROTOCOL FUNCTIONS ==========

/**
 * Parses raw protocol message into structured format.
 */
int parse_message(const char *buffer, Message *msg, DisconnectReason *disconnect_reason);

/**
 * Creates protocol message from operation and data.
 */
int create_message(char *buffer, OpCode op, const char *data);

/**
 * Logs message for debugging.
 */
void log_message(const char *prefix, const Message *msg);

/**
 * Validates if opcode is in valid range.
 */
bool is_valid_opcode(int op);

/**
 * Checks if string contains only numeric characters.
 */
bool is_numeric_string(const char *str, int len);

/**
 * Determines if client should be disconnected based on violations.
 */
bool should_disconnect_client(ClientViolations *violations);

/**
 * Converts disconnect reason to human-readable string.
 */
const char* get_disconnect_reason_string(DisconnectReason reason);

#endif //SERVER_PROTOCOL_H