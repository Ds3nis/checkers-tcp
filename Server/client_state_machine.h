//
// Created by Denis on 10.01.2026.
//

#ifndef SERVER_CLIENT_STATE_MACHINE_H
#define SERVER_CLIENT_STATE_MACHINE_H

#include <stdbool.h>
#include "protocol.h"

// Forward declaration to avoid circular dependency
typedef struct Client Client;


/**
 * Client game states for state machine.
 * Defines the logical states a client can be in during gameplay.
 */
typedef enum {
    CLIENT_GAME_STATE_NOT_LOGGED_IN, // Initial state, no authentication
    CLIENT_GAME_STATE_IN_LOBBY, // Authenticated, browsing rooms
    CLIENT_GAME_STATE_IN_ROOM_WAITING, // In room, waiting for opponent
    CLIENT_GAME_STATE_IN_GAME // Active game in progress
} ClientGameState;

/**
 * Structure containing allowed operations for a state.
 */
typedef struct {
    OpCode allowed_ops[30];
    int count;
} AllowedOperations;

// ========== STATE MACHINE FUNCTIONS ==========

/**
 * Converts game state enum to string.
 */
const char* client_game_state_to_string(ClientGameState state);

/**
 * Gets list of operations allowed in given state.
 */
AllowedOperations get_allowed_operations(ClientGameState state);

/**
 * Checks if operation is allowed in current state.
 */
bool is_operation_allowed(ClientGameState state, OpCode op);

/**
 * Transitions client to new game state with logging.
 */
void transition_client_state(Client *client, ClientGameState new_state);

#endif //SERVER_CLIENT_STATE_MACHINE_H