//
// Created by denkhuda on 11/17/25.
//

#include "protocol.h"
#include "server.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <time.h>
#include <ctype.h>
#include <unistd.h>

/**
 * Converts disconnect reason enum to human-readable string.
 *
 * @param reason Disconnect reason code
 * @return String description of the reason
 */
const char* get_disconnect_reason_string(DisconnectReason reason) {
    switch (reason) {
        case DISCONNECT_REASON_INVALID_PREFIX:
            return "Invalid message prefix";
        case DISCONNECT_REASON_INVALID_FORMAT:
            return "Invalid message format";
        case DISCONNECT_REASON_INVALID_OPCODE:
            return "Invalid operation code";
        case DISCONNECT_REASON_INVALID_LENGTH:
            return "Invalid length field";
        case DISCONNECT_REASON_DATA_MISMATCH:
            return "Data length mismatch";
        case DISCONNECT_REASON_BUFFER_OVERFLOW:
            return "Buffer overflow attempt";
        case DISCONNECT_REASON_TOO_MANY_VIOLATIONS:
            return "Too many protocol violations";
        case DISCONNECT_REASON_SUSPICIOUS_ACTIVITY:
            return "Suspicious activity detected";
        default:
            return "Unknown reason";
    }
}

/**
 * Validates if operation code is within valid range.
 *
 * @param op Operation code to validate
 * @return true if valid, false otherwise
 */
bool is_valid_opcode(int op) {
    return (op >= OP_LOGIN && op <= OP_GAME_RESUMED) || op == OP_ERROR;
}

/**
 * Checks if string contains only numeric digits.
 * Used for validating OpCode and Length fields in protocol parsing.
 *
 * @param str String to check
 * @param len Length of string to check
 * @return true if all characters are digits, false otherwise
 */
bool is_numeric_string(const char *str, int len) {
    if (len <= 0) return false;

    for (int i = 0; i < len; i++) {
        if (!isdigit(str[i])) {
            return false;
        }
    }
    return true;
}

/**
 * Checks if client should be disconnected based on violation history.
 * Implements violation tracking with time-based reset mechanism.
 *
 * @param violations Pointer to client's violation tracking structure
 * @return true if client should be disconnected
 */
bool should_disconnect_client(ClientViolations *violations) {
    time_t now = time(NULL);

    // Reset violations after timeout period
    if (violations->last_violation_time > 0 &&
        (now - violations->last_violation_time) > VIOLATION_RESET_TIME) {
        violations->invalid_message_count = 0;
        violations->unknown_opcode_count = 0;
        }

    violations->last_violation_time = now;
    violations->invalid_message_count++;

    if (violations->invalid_message_count >= MAX_VIOLATIONS) {
        fprintf(stderr, "SECURITY: Client exceeded violation limit (%d/%d)\n",
                violations->invalid_message_count, MAX_VIOLATIONS);
        return true;
    }

    return false;
}



/**
 * Parses incoming protocol message.
 *
 * Protocol format: DENTCP|OP|LEN|DATA\n
 * - DENTCP: Fixed prefix for protocol identification
 * - OP: Two-digit operation code (01-99 || 500)
 * - LEN: Four-digit data length (0000-9999)
 * - DATA: Variable length payload
 *
 * Performs extensive validation to prevent malicious input:
 * - Verifies protocol prefix
 * - Validates field separators
 * - Checks numeric fields contain only digits
 * - Validates OpCode is in valid range
 * - Prevents buffer overflow on data field
 *
 * @param buffer Input buffer containing raw message
 * @param msg Output message structure to populate
 * @param disconnect_reason Output reason code if parsing fails
 * @return 0 on success, -1 on failure
 */
int parse_message(const char *buffer, Message *msg, DisconnectReason *disconnect_reason) {
    if (!buffer || !msg || !disconnect_reason) {
        return -1;
    }
    // Validate protocol prefix
    if (strncmp(buffer, PREFIX, PREFIX_LEN) != 0) {
        fprintf(stderr, "Invalid message prefix\n");
        fprintf(stderr, "Expected: %s, Got: %.6s\n", PREFIX, buffer);
        *disconnect_reason = DISCONNECT_REASON_INVALID_PREFIX;
        return -1;
    }

    if (buffer[PREFIX_LEN] != '|') {
        fprintf(stderr, "SECURITY: Missing separator after prefix\n");
        *disconnect_reason = DISCONNECT_REASON_INVALID_FORMAT;
        return -1;
    }

    char temp[16];
    const char *ptr = buffer + PREFIX_LEN + 1; // Skip "DENTCP|"

    // Extract and validate OP field
    const char *next_pipe = strchr(ptr, '|');
    if (!next_pipe) {
        fprintf(stderr, "SECURITY: Missing OP separator\n");
        *disconnect_reason = DISCONNECT_REASON_INVALID_FORMAT;
        return -1;
    }

    int op_len = next_pipe - ptr;
    if (op_len <= 0 || (size_t)op_len >= sizeof(temp)) {
        fprintf(stderr, "SECURITY: Invalid OP length: %d\n", op_len);
        *disconnect_reason = DISCONNECT_REASON_INVALID_FORMAT;
        return -1;
    }

    if (!is_numeric_string(ptr, op_len)) {
        fprintf(stderr, "SECURITY: OP contains non-numeric characters\n");
        *disconnect_reason = DISCONNECT_REASON_INVALID_OPCODE;
        return -1;
    }

    strncpy(temp, ptr, op_len);
    temp[op_len] = '\0';
    msg->op = atoi(temp);

    if (!is_valid_opcode(msg->op)) {
        fprintf(stderr, "SECURITY: Invalid OpCode: %d\n", msg->op);
        *disconnect_reason = DISCONNECT_REASON_INVALID_OPCODE;
        return -1;
    }

    // Extract and validate LEN field
    ptr = next_pipe + 1;
    next_pipe = strchr(ptr, '|');
    if (!next_pipe) {
        fprintf(stderr, "SECURITY: Missing LEN separator\n");
        *disconnect_reason = DISCONNECT_REASON_INVALID_FORMAT;
        return -1;
    }

    int len_len = next_pipe - ptr;
    if (len_len <= 0 || len_len >= sizeof(temp)) {
        fprintf(stderr, "SECURITY: Invalid LEN length: %d\n", len_len);
        *disconnect_reason = DISCONNECT_REASON_INVALID_LENGTH;
        return -1;
    }

    if (!is_numeric_string(ptr, len_len)) {
        fprintf(stderr, "SECURITY: LEN contains non-numeric characters\n");
        *disconnect_reason = DISCONNECT_REASON_INVALID_LENGTH;
        return -1;
    }

    strncpy(temp, ptr, len_len);
    temp[len_len] = '\0';
    msg->len = atoi(temp);

    if (msg->len < 0 || msg->len > MAX_DATA_LEN - 1) {
        fprintf(stderr, "SECURITY: Invalid LEN value: %d (max: %d)\n",
                msg->len, MAX_DATA_LEN - 1);
        *disconnect_reason = DISCONNECT_REASON_INVALID_LENGTH;
        return -1;
    }

    // Extract DATA field
    ptr = next_pipe + 1;
    int data_len = strlen(ptr);
    if (data_len > MAX_DATA_LEN - 1) {
        fprintf(stderr, "SECURITY: Data too long: %d (max: %d)\n",
                data_len, MAX_DATA_LEN - 1);
        *disconnect_reason = DISCONNECT_REASON_BUFFER_OVERFLOW;
        return -1;
    }



    strncpy(msg->data, ptr, MAX_DATA_LEN - 1);
    msg->data[MAX_DATA_LEN - 1] = '\0';


    // printf("Message data %s",msg->data);
    // printf("\n");
    // printf("Message len %d",msg->len);
    // printf("Message operation code %d", msg->op);

    return 0;
}

/**
 * Creates a protocol message for sending.
 *
 * Format: DENTCP|OP|LEN|DATA\n
 *
 * @param buffer Output buffer to write message to
 * @param op Operation code
 * @param data Message payload (NULL for empty data)
 * @return Number of bytes written, or -1 on error
 */
int create_message(char *buffer, OpCode op, const char *data) {
    int data_len = data ? strlen(data) : 0;
    int written = snprintf(buffer, MAX_MESSAGE_LEN, "%s|%02d|%04d|%s\n",
                          PREFIX, op, data_len, data ? data : "");

    if (written >= MAX_MESSAGE_LEN) {
        fprintf(stderr, "Message too long\n");
        return -1;
    }

    return written;
}

/**
 * Logs a parsed message for debugging.
 *
 * @param prefix Log prefix (e.g., "SEND", "RECV")
 * @param msg Message to log
 */
void log_message(const char *prefix, const Message *msg) {
    printf("[%s] OP=%02d LEN=%d DATA=%s\n", prefix, msg->op, msg->len, msg->data);
}
