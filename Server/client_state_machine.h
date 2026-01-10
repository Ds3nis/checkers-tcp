//
// Created by Denis on 10.01.2026.
//

#ifndef SERVER_CLIENT_STATE_MACHINE_H
#define SERVER_CLIENT_STATE_MACHINE_H

#include <stdbool.h>
#include "protocol.h"
#include "server.h"

typedef enum {
    CLIENT_GAME_STATE_NOT_LOGGED_IN,    // Ще не залогінений
    CLIENT_GAME_STATE_IN_LOBBY,         // Залогінений, в лобі
    CLIENT_GAME_STATE_IN_ROOM_WAITING,  // В кімнаті, чекає іншого гравця
    CLIENT_GAME_STATE_IN_GAME           // В активній грі
} ClientGameState;


typedef struct {
    OpCode allowed_ops[30];
    int count;
} AllowedOperations;

const char* client_game_state_to_string(ClientGameState state);
AllowedOperations get_allowed_operations(ClientGameState state);
bool is_operation_allowed(ClientGameState state, OpCode op);
void transition_client_state(Client *client, ClientGameState new_state);

#endif //SERVER_CLIENT_STATE_MACHINE_H