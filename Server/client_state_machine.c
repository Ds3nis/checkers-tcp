//
// Created by Denis on 10.01.2026.
//

#include "client_state_machine.h"
#include "server.h"
#include <stdio.h>
#include <string.h>

/**
 * Converts client game state enum to human-readable string.
 *
 * @param state Game state to convert
 * @return String representation of the state
 */
const char* client_game_state_to_string(ClientGameState state) {
    switch (state) {
        case CLIENT_GAME_STATE_NOT_LOGGED_IN:
            return "NOT_LOGGED_IN";
        case CLIENT_GAME_STATE_IN_LOBBY:
            return "IN_LOBBY";
        case CLIENT_GAME_STATE_IN_ROOM_WAITING:
            return "IN_ROOM_WAITING";
        case CLIENT_GAME_STATE_IN_GAME:
            return "IN_GAME";
        default:
            return "UNKNOWN";
    }
}

/**
 * Gets list of operations allowed in a given game state.
 * Implements the client state machine's operation whitelist.
 *
 * @param state Current game state
 * @return Structure containing allowed operations
 */
AllowedOperations get_allowed_operations(ClientGameState state) {
    AllowedOperations ops;
    ops.count = 0;

    switch (state) {
        case CLIENT_GAME_STATE_NOT_LOGGED_IN:
            ops.allowed_ops[ops.count++] = OP_LOGIN;
            ops.allowed_ops[ops.count++] = OP_PONG;
            ops.allowed_ops[ops.count++] = OP_PING;
            ops.allowed_ops[ops.count++] = OP_RECONNECT_REQUEST;
            ops.allowed_ops[ops.count++] = OP_ERROR;
            break;

        case CLIENT_GAME_STATE_IN_LOBBY:
            ops.allowed_ops[ops.count++] = OP_CREATE_ROOM;
            ops.allowed_ops[ops.count++] = OP_JOIN_ROOM;
            ops.allowed_ops[ops.count++] = OP_LIST_ROOMS;
            ops.allowed_ops[ops.count++] = OP_PONG;
            ops.allowed_ops[ops.count++] = OP_PING;
            ops.allowed_ops[ops.count++] = OP_RECONNECT_REQUEST;
            ops.allowed_ops[ops.count++] = OP_ERROR;
            break;

        case CLIENT_GAME_STATE_IN_ROOM_WAITING:
            ops.allowed_ops[ops.count++] = OP_LEAVE_ROOM;
            ops.allowed_ops[ops.count++] = OP_JOIN_ROOM;
            ops.allowed_ops[ops.count++] = OP_LIST_ROOMS;
            ops.allowed_ops[ops.count++] = OP_PONG;
            ops.allowed_ops[ops.count++] = OP_PING;
            ops.allowed_ops[ops.count++] = OP_RECONNECT_REQUEST;
            ops.allowed_ops[ops.count++] = OP_ERROR;
            break;

        case CLIENT_GAME_STATE_IN_GAME:
            ops.allowed_ops[ops.count++] = OP_MOVE;
            ops.allowed_ops[ops.count++] = OP_MULTI_MOVE;
            ops.allowed_ops[ops.count++] = OP_LIST_ROOMS;
            ops.allowed_ops[ops.count++] = OP_LEAVE_ROOM;
            ops.allowed_ops[ops.count++] = OP_PONG;
            ops.allowed_ops[ops.count++] = OP_PING;
            ops.allowed_ops[ops.count++] = OP_RECONNECT_REQUEST;
            ops.allowed_ops[ops.count++] = OP_ERROR;
            break;
    }

    return ops;
}

/**
 * Checks if an operation is allowed in the current game state.
 *
 * @param state Current game state
 * @param op Operation to validate
 * @return true if operation is allowed, false otherwise
 */
bool is_operation_allowed(ClientGameState state, OpCode op) {
    AllowedOperations ops = get_allowed_operations(state);

    for (int i = 0; i < ops.count; i++) {
        if (ops.allowed_ops[i] == op) {
            return true;
        }
    }

    return false;
}

/**
 * Transitions client to a new game state.
 * Logs the state transition for debugging purposes.
 *
 * @param client Pointer to the client
 * @param new_state New game state to transition to
 */
void transition_client_state(Client *client, ClientGameState new_state) {
    printf("Client %s: %s → %s\n",
           client->client_id[0] ? client->client_id : "anonymous",
           client_game_state_to_string(client->game_state),
           client_game_state_to_string(new_state));

    client->game_state = new_state;
}



