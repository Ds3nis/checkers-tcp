//
// Created by Denis on 10.01.2026.
//

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "client_state_machine.h"
#include "server.h"


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

AllowedOperations get_allowed_operations(ClientGameState state) {
    AllowedOperations ops;
    ops.count = 0;

    switch (state) {
        case CLIENT_GAME_STATE_NOT_LOGGED_IN:
            ops.allowed_ops[ops.count++] = OP_LOGIN;
            ops.allowed_ops[ops.count++] = OP_PONG;  // Heartbeat завжди дозволений
            ops.allowed_ops[ops.count++] = OP_PING;
            break;

        case CLIENT_GAME_STATE_IN_LOBBY:
            ops.allowed_ops[ops.count++] = OP_CREATE_ROOM;
            ops.allowed_ops[ops.count++] = OP_JOIN_ROOM;
            ops.allowed_ops[ops.count++] = OP_LIST_ROOMS;
            ops.allowed_ops[ops.count++] = OP_PONG;
            ops.allowed_ops[ops.count++] = OP_PING;
            break;

        case CLIENT_GAME_STATE_IN_ROOM_WAITING:
            ops.allowed_ops[ops.count++] = OP_LEAVE_ROOM;
            ops.allowed_ops[ops.count++] = OP_PONG;
            ops.allowed_ops[ops.count++] = OP_PING;
            break;

        case CLIENT_GAME_STATE_IN_GAME:
            ops.allowed_ops[ops.count++] = OP_MOVE;
            ops.allowed_ops[ops.count++] = OP_MULTI_MOVE;
            ops.allowed_ops[ops.count++] = OP_LEAVE_ROOM;
            ops.allowed_ops[ops.count++] = OP_PONG;
            ops.allowed_ops[ops.count++] = OP_PING;
            ops.allowed_ops[ops.count++] = OP_RECONNECT_REQUEST;
            break;
    }

    return ops;
}

bool is_operation_allowed(ClientGameState state, OpCode op) {
    AllowedOperations ops = get_allowed_operations(state);

    for (int i = 0; i < ops.count; i++) {
        if (ops.allowed_ops[i] == op) {
            return true;
        }
    }

    return false;
}

// Змінити стан клієнта
void transition_client_state(Client *client, ClientGameState new_state) {
    printf("🔄 Client %s: %s → %s\n",
           client->client_id[0] ? client->client_id : "anonymous",
           client_game_state_to_string(client->game_state),
           client_game_state_to_string(new_state));

    client->game_state = new_state;
}

void log_invalid_operation_attempt(Client *client, OpCode attempted_op) {
    fprintf(stderr, "\n");
    fprintf(stderr, "═══════════════════════════════════════════════════\n");
    fprintf(stderr, "INVALID OPERATION ATTEMPT\n");
    fprintf(stderr, "═══════════════════════════════════════════════════\n");
    fprintf(stderr, "Client: %s (socket %d)\n",
            client->client_id[0] ? client->client_id : "NOT_LOGGED_IN",
            client->socket);
    fprintf(stderr, "Current State: %s\n", client_game_state_to_string(client->game_state));
    fprintf(stderr, "Attempted Operation: %d\n", attempted_op);
    fprintf(stderr, "Timestamp: %ld\n", time(NULL));

    fprintf(stderr, "Allowed Operations in this state: ");
    AllowedOperations ops = get_allowed_operations(client->game_state);
    for (int i = 0; i < ops.count; i++) {
        fprintf(stderr, "%d", ops.allowed_ops[i]);
        if (i < ops.count - 1) fprintf(stderr, ", ");
    }
    fprintf(stderr, "\n");
    fprintf(stderr, "═══════════════════════════════════════════════════\n");
    fprintf(stderr, "\n");
}

