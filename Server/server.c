#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>
#include "server.h"
#include "protocol.h"

int server_init(Server *server, int port) {
    server->port = port;
    server->running = false;
    server->client_count = 0;
    server->room_count = 0;

    pthread_mutex_init(&server->clients_mutex, NULL);
    pthread_mutex_init(&server->rooms_mutex, NULL);

    memset(server->clients, 0, sizeof(server->clients));
    memset(server->rooms, 0, sizeof(server->rooms));

    // Create socket
    server->server_socket = socket(AF_INET, SOCK_STREAM, 0);
    if (server->server_socket < 0) {
        perror("Socket creation failed");
        return -1;
    }

    // Set socket options
    int opt = 1;
    if (setsockopt(server->server_socket, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
        perror("Setsockopt failed");
        close(server->server_socket);
        return -1;
    }

    // Bind socket
    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = INADDR_ANY;
    server_addr.sin_port = htons(port);

    if (bind(server->server_socket, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        perror("Bind failed");
        close(server->server_socket);
        return -1;
    }

    // Listen
    if (listen(server->server_socket, 10) < 0) {
        perror("Listen failed");
        close(server->server_socket);
        return -1;
    }

    printf("Server initialized on port %d\n", port);
    return 0;
}

void send_message(int socket, OpCode op, const char *data) {
    char buffer[MAX_MESSAGE_LEN];
    int len = create_message(buffer, op, data);
    if (len > 0) {
        send(socket, buffer, len, 0);
        send(socket, "\n", 1, 0);
    }
}

int add_client(Server *server, int socket) {
    pthread_mutex_lock(&server->clients_mutex);

    if (server->client_count >= MAX_CLIENTS) {
        pthread_mutex_unlock(&server->clients_mutex);
        return -1;
    }

    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (!server->clients[i].active) {
            server->clients[i].socket = socket;
            server->clients[i].active = true;
            server->clients[i].logged_in = false;
            server->clients[i].client_id[0] = '\0';
            server->clients[i].current_room[0] = '\0';
            server->client_count++;

            pthread_mutex_unlock(&server->clients_mutex);
            return i;
        }
    }

    pthread_mutex_unlock(&server->clients_mutex);
    return -1;
}

void remove_client(Server *server, const char *client_id) {
    pthread_mutex_lock(&server->clients_mutex);

    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active &&
            strcmp(server->clients[i].client_id, client_id) == 0) {

            // Leave room if in one
            if (server->clients[i].current_room[0] != '\0') {
                leave_room(server, server->clients[i].current_room, client_id);
            }

            close(server->clients[i].socket);
            server->clients[i].active = false;
            server->client_count--;
            printf("Client %s disconnected\n", client_id);
            break;
        }
    }

    pthread_mutex_unlock(&server->clients_mutex);
}

Client* find_client(Server *server, const char *client_id) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active &&
            strcmp(server->clients[i].client_id, client_id) == 0) {
            return &server->clients[i];
        }
    }
    return NULL;
}

Room* create_room(Server *server, const char *room_name, const char *creator) {
    pthread_mutex_lock(&server->rooms_mutex);

    // Check if room already exists
    for (int i = 0; i < MAX_ROOMS; i++) {
        if ((server->rooms[i].players_count > 0 || strlen(server->rooms[i].owner) > 0 ) &&
            strcmp(server->rooms[i].name, room_name) == 0) {
            pthread_mutex_unlock(&server->rooms_mutex);
            return NULL;
        }
    }

    // Find empty slot
    for (int i = 0; i < MAX_ROOMS; i++) {
        if (server->rooms[i].players_count == 0 && strlen(server->rooms[i].owner) == 0 ) {
            strncpy(server->rooms[i].name, room_name, MAX_ROOM_NAME - 1);
            strncpy(server->rooms[i].owner, creator, MAX_PLAYER_NAME - 1);
            server->rooms[i].owner[MAX_PLAYER_NAME - 1] = '\0';
            server->rooms[i].player1[0] = '\0';
            server->rooms[i].player2[0] = '\0';
            server->rooms[i].players_count = 0;
            server->rooms[i].game_started = false;
            server->room_count++;

            pthread_mutex_unlock(&server->rooms_mutex);
            return &server->rooms[i];
        }
    }

    pthread_mutex_unlock(&server->rooms_mutex);
    return NULL;
}

Room* find_room(Server *server, const char *room_name) {
    for (int i = 0; i < MAX_ROOMS; i++) {
        if ((server->rooms[i].players_count > 0 || strlen(server->rooms[i].owner) > 0)&&
            strcmp(server->rooms[i].name, room_name) == 0) {
            return &server->rooms[i];
        }
    }
    return NULL;
}

int join_room(Server *server, const char *room_name, const char *player_name) {
    pthread_mutex_lock(&server->rooms_mutex);

    Room *room = find_room(server, room_name);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return -1; // Room not found
    }

    if (room->players_count >= 2) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return -2; // Room full
    }

    // Check if player is already in this room
    if (strcmp(room->player1, player_name) == 0 ||
        strcmp(room->player2, player_name) == 0) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return -3; // Player already in room
    }

    // Check if player is in another room (need to check clients)
    // UNLOCK rooms_mutex BEFORE locking clients_mutex to avoid deadlock
    pthread_mutex_unlock(&server->rooms_mutex);

    pthread_mutex_lock(&server->clients_mutex);
    printf("%s player name \n", player_name);
    Client *client = find_client(server, player_name);
    if (!client) {
        pthread_mutex_unlock(&server->clients_mutex);
        return -5; // Client not found
    }

    printf("current room %s \n", client->current_room);
    if (client->current_room[0] != '\0') {
        pthread_mutex_unlock(&server->clients_mutex);
        return -4; // Already in another room
    }
    pthread_mutex_unlock(&server->clients_mutex);

    // Re-lock rooms_mutex to modify room
    pthread_mutex_lock(&server->rooms_mutex);

    // Re-check room still exists and is not full (could have changed)
    room = find_room(server, room_name);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return -1;
    }

    if (room->players_count >= 2) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return -2;
    }

    // Add player to first available slot
    if (room->player1[0] == '\0') {
        strncpy(room->player1, player_name, MAX_PLAYER_NAME - 1);
        room->player1[MAX_PLAYER_NAME - 1] = '\0';
        room->players_count = 1;
    } else if (room->player2[0] == '\0') {
        strncpy(room->player2, player_name, MAX_PLAYER_NAME - 1);
        room->player2[MAX_PLAYER_NAME - 1] = '\0';
        room->players_count = 2;

        // Start game only when second player joins
        init_game(&room->game, room->player1, room->player2);
        room->game_started = true;
    }

    pthread_mutex_unlock(&server->rooms_mutex);
    return 0;
}

void leave_room(Server *server, const char *room_name, const char *player_name) {
    pthread_mutex_lock(&server->rooms_mutex);

    Room *room = find_room(server, room_name);
    if (room) {
        room->players_count--;
        if (room->players_count == 0) {
            // Remove room
            memset(room, 0, sizeof(Room));
            server->room_count--;
        } else {
            // Notify other player
            Client *other = NULL;
            if (strcmp(room->player1, player_name) != 0) {
                other = find_client(server, room->player1);
            } else if (room->player2[0] != '\0') {
                other = find_client(server, room->player2);
            }

            if (other) {
                other->current_room[0] = '\0';
                char msg[256];
                snprintf(msg, sizeof(msg), "%s,%s", room_name, player_name);
                send_message(other->socket, OP_ROOM_LEFT, msg);
            }
            printf("players %d", room->players_count);
            memset(room, 0, sizeof(Room));
            server->room_count--;
        }
    }

    pthread_mutex_unlock(&server->rooms_mutex);
}

void broadcast_to_room(Server *server, const char *room_name, OpCode op, const char *data) {
    Room *room = find_room(server, room_name);
    if (!room) return;

    Client *p1 = find_client(server, room->player1);
    Client *p2 = (room->player2[0] != '\0') ? find_client(server, room->player2) : NULL;

    if (p1) send_message(p1->socket, op, data);
    if (p2) send_message(p2->socket, op, data);
}

void handle_login(Server *server, Client *client, const char *data) {
    pthread_mutex_lock(&server->clients_mutex);

    // Clean input
    char clean_id[MAX_PLAYER_NAME];
    strncpy(clean_id, data, MAX_PLAYER_NAME - 1);
    clean_id[MAX_PLAYER_NAME - 1] = '\0';
    clean_id[strcspn(clean_id, "\r\n")] = '\0';

    // Validate name length
    if (strlen(clean_id) == 0) {
        pthread_mutex_unlock(&server->clients_mutex);
        send_message(client->socket, OP_LOGIN_FAIL, "Name cannot be empty");
        printf("Login failed: empty name\n");
        return;
    }

    // Check if client_id already exists
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active &&
            server->clients[i].logged_in &&
            strcmp(server->clients[i].client_id, clean_id) == 0) {
            pthread_mutex_unlock(&server->clients_mutex);
            send_message(client->socket, OP_LOGIN_FAIL, "Client ID already in use");
            printf("Login failed: '%s' already in use\n", clean_id);
            return;
            }
    }

    // Save client_id
    strncpy(client->client_id, clean_id, MAX_PLAYER_NAME - 1);
    client->client_id[MAX_PLAYER_NAME - 1] = '\0';

    client->logged_in = true;
    client->active = true;

    pthread_mutex_unlock(&server->clients_mutex);

    send_message(client->socket, OP_LOGIN_OK, clean_id);
    log_client(client);
    printf("Client logged in: '%s' (socket %d)\n", client->client_id, client->socket);
}

void handle_create_room(Server *server, Client *client, const char *data) {
    if (!client->logged_in) {
        send_message(client->socket, OP_ROOM_FAIL, "Not logged in");
        return;
    }

    // Parse: player_name,room_name
    char player_name[MAX_PLAYER_NAME];
    char room_name[MAX_ROOM_NAME];

    if (sscanf(data, "%[^,],%s", player_name, room_name) != 2) {
        send_message(client->socket, OP_ROOM_FAIL, "Invalid format");
        return;
    }

    Room *room = create_room(server, room_name, player_name);
    if (!room) {
        send_message(client->socket, OP_ROOM_FAIL, "Room already exists or server full");
        return;
    }

    // strncpy(client->current_room, room_name, MAX_ROOM_NAME - 1);

    // char response[256];
    // snprintf(response, sizeof(response), "%s,1", room_name);
    // send_message(client->socket, OP_ROOM_JOINED, response);

    send_message(client->socket, OP_ROOM_CREATED, room_name);
    printf("Room created: %s by %s. Players count=%d\n", room_name, player_name, room->players_count);
    log_client(client);
}

void handle_join_room(Server *server, Client *client, const char *data) {

    if (!client->logged_in) {
        send_message(client->socket, OP_ROOM_FAIL, "Not logged in");
        return;
    }

    char player_name[MAX_PLAYER_NAME];
    char room_name[MAX_ROOM_NAME];

    if (sscanf(data, "%[^,],%s", player_name, room_name) != 2) {
        send_message(client->socket, OP_ROOM_FAIL, "Invalid format");
        return;
    }

    printf("before function");
    int result = join_room(server, room_name, player_name);

    if (result == -1) {
        send_message(client->socket, OP_ROOM_FAIL, "Room not found");
        return;
    } else if (result == -2) {
        send_message(client->socket, OP_ROOM_FAIL, "Room is full");
        return;
    } else if (result == -3) {
        send_message(client->socket, OP_ROOM_FAIL, "You are already in this room");
        return;
    } else if (result == -4) {
        send_message(client->socket, OP_ROOM_FAIL, "Already in another room. Leave first.");
        return;
    }  else if (result == -5) {
        send_message(client->socket, OP_ROOM_FAIL, "Client not found");
        return;
    }

    strncpy(client->current_room, room_name, MAX_ROOM_NAME - 1);
    client->current_room[MAX_ROOM_NAME - 1] = '\0';

    Room *room = find_room(server, room_name);
    if (!room) {
        send_message(client->socket, OP_ROOM_FAIL, "Room disappeared");
        return;
    }

    char response[256];
    snprintf(response, sizeof(response), "%s,%d", room_name, room->players_count);
    send_message(client->socket, OP_ROOM_JOINED, response);

    // Start game only if 2 players joined
    if (room->game_started) {
        char game_start_msg[512];
        snprintf(game_start_msg, sizeof(game_start_msg), "%s,%s,%s,%s",
                room_name, room->player1, room->player2, room->game.current_turn);
        broadcast_to_room(server, room_name, OP_GAME_START, game_start_msg);

        // Send initial board state
        char *board_json = game_board_to_json(&room->game);
        broadcast_to_room(server, room_name, OP_GAME_STATE, board_json);
    }

    printf("Player %s joined room %s (players: %d/2)\n", player_name, room_name, room->players_count);
}


void handle_move(Server *server, Client *client, const char *data) {
    if (!client->logged_in || client->current_room[0] == '\0') {
        send_message(client->socket, OP_ERROR, "Not in a game");
        return;
    }

    // Parse: room_name,player_name,from_row,from_col,to_row,to_col
    char room_name[MAX_ROOM_NAME];
    char player_name[MAX_PLAYER_NAME];
    int from_row, from_col, to_row, to_col;

    if (sscanf(data, "%[^,],%[^,],%d,%d,%d,%d",
               room_name, player_name, &from_row, &from_col, &to_row, &to_col) != 6) {
        send_message(client->socket, OP_INVALID_MOVE, "Invalid move format");
        return;
    }

    Room *room = find_room(server, room_name);
    if (!room || !room->game_started) {
        send_message(client->socket, OP_ERROR, "Game not found");
        return;
    }

    // Validate move
    if (!validate_move(&room->game, from_row, from_col, to_row, to_col, player_name)) {
        send_message(client->socket, OP_INVALID_MOVE, "Invalid move");
        return;
    }

    // Apply move
    apply_move(&room->game, from_row, from_col, to_row, to_col);
    change_turn(&room->game);

    // Send updated board to both players
    char *board_json = game_board_to_json(&room->game);
    broadcast_to_room(server, room_name, OP_GAME_STATE, board_json);

    // Check for game over
    char winner[MAX_PLAYER_NAME];
    if (check_game_over(&room->game, winner)) {
        char end_msg[256];
        snprintf(end_msg, sizeof(end_msg), "%s,no_pieces", winner);
        broadcast_to_room(server, room_name, OP_GAME_END, end_msg);
        room->game_started = false;
    }
}

void handle_leave_room(Server *server, Client *client, const char *data) {
    char room_name[MAX_ROOM_NAME];
    char player_name[MAX_PLAYER_NAME];

    if (sscanf(data, "%[^,],%s", room_name, player_name) != 2) {
        send_message(client->socket, OP_ERROR, "Invalid format");
        return;
    }

    leave_room(server, room_name, player_name);
    client->current_room[0] = '\0';

    char response[256];
    snprintf(response, sizeof(response), "%s", room_name);
    send_message(client->socket, OP_ROOM_LEFT, response);
    log_client(client);
}

void handle_ping(Server *server, Client *client) {
    send_message(client->socket, OP_PONG, "");
    printf("PONG TO SOCKET %d\n", client->socket);
}

void handle_list_rooms(Server *server, Client *client) {
    pthread_mutex_lock(&server->rooms_mutex);

    // Format: [{"id":1,"name":"Room1","players":1},{"id":2,"name":"Room2","players":2}]

    char json[4096] = "[";
    int first = 1;

    for (int i = 0; i < MAX_ROOMS; i++) {
        if (server->rooms[i].players_count > 0 || strlen(server->rooms[i].owner) > 0) {
            if (!first) {
                strcat(json, ",");
            }
            first = 0;

            char room_json[256];
            snprintf(room_json, sizeof(room_json),
                    "{\"id\":%d,\"name\":\"%s\",\"players\":%d}",
                    i,
                    server->rooms[i].name,
                    server->rooms[i].players_count);

            strcat(json, room_json);
        }
    }

    strcat(json, "]");

    pthread_mutex_unlock(&server->rooms_mutex);

    send_message(client->socket, OP_ROOMS_LIST, json);
    printf("Sent rooms list to client: %s\n", json);
}

void* client_handler(void *arg) {
    ClientThreadArgs *args = (ClientThreadArgs*)arg;
    Server *server = args->server;
    int client_socket = args->client_socket;
    int client_idx = args->client_idx;

    printf("Handling client connection from client socket %d", client_socket);

    free(args);

    char buffer[BUFFER_SIZE];
    Client *client = NULL;

    // Find client structure
    pthread_mutex_lock(&server->clients_mutex);
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active && server->clients[i].socket == client_socket) {
            client = &server->clients[i];
            break;
        }
    }
    pthread_mutex_unlock(&server->clients_mutex);

    if (!client) {
        close(client_socket);
        return NULL;
    }

    while (server->running && client->active) {
        memset(buffer, 0, BUFFER_SIZE);
        int bytes = recv(client_socket, buffer, BUFFER_SIZE - 1, 0);

        if (bytes <= 0) {
            break;
        }

        buffer[bytes] = '\0';

        // Remove newline
        char *newline = strchr(buffer, '\n');
        if (newline) *newline = '\0';

        Message msg;
        if (parse_message(buffer, &msg) == 0) {
            log_message("RECV", &msg);

            switch (msg.op) {
                case OP_LOGIN:
                    handle_login(server, client, msg.data);
                    break;
                case OP_CREATE_ROOM:
                    handle_create_room(server, client, msg.data);
                    break;
                case OP_JOIN_ROOM:
                    handle_join_room(server, client, msg.data);
                    break;
                case OP_MOVE:
                    handle_move(server, client, msg.data);
                    break;
                case OP_LEAVE_ROOM:
                    handle_leave_room(server, client, msg.data);
                    break;
                case OP_PING:
                    handle_ping(server, client);
                    break;
                case OP_LIST_ROOMS:
                    handle_list_rooms(server, client);
                    break;
                default:
                    send_message(client_socket, OP_ERROR, "Unknown operation");
                    break;
            }
        }
    }

    if (client->logged_in) {
        remove_client(server, client->client_id);
    } else {
        close(client_socket);
        client->active = false;
        pthread_mutex_lock(&server->clients_mutex);
        server->client_count--;
        pthread_mutex_unlock(&server->clients_mutex);
    }

    return NULL;
}

void server_start(Server *server) {
    server->running = true;
    printf("Server started. Waiting for connections...\n");

    while (server->running) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);

        int client_socket = accept(server->server_socket,
                                   (struct sockaddr*)&client_addr, &client_len);

        if (client_socket < 0) {
            if (server->running) {
                perror("Accept failed");
            }
            continue;
        }

        printf("New connection from %s:%d\n",
               inet_ntoa(client_addr.sin_addr), ntohs(client_addr.sin_port));

        int client_idx = add_client(server, client_socket);
        if (client_idx < 0) {
            send_message(client_socket, OP_ERROR, "Server full");
            close(client_socket);
            continue;
        }

        // Create thread for client

        ClientThreadArgs *args = malloc(sizeof(ClientThreadArgs));
        if (!args) {
            close(client_socket);
            server->clients[client_idx].active = false;
            continue;
        }

        args->server = server;
        args->client_socket = client_socket;
        args->client_idx = client_idx;

        int result = pthread_create(&server->clients[client_idx].thread, NULL,
                                    client_handler, args);

        if (result != 0) {
            printf("Failed to create thread: %d\n", result);
            free(args);
            close(client_socket);
            server->clients[client_idx].active = false;
        } else {
            printf("New client thread created successfully\n");
            pthread_detach(server->clients[client_idx].thread);
        }
    }
}

void server_stop(Server *server) {
    server->running = false;

    pthread_mutex_lock(&server->clients_mutex);
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active) {
            close(server->clients[i].socket);
        }
    }
    pthread_mutex_unlock(&server->clients_mutex);

    close(server->server_socket);
    pthread_mutex_destroy(&server->clients_mutex);
    pthread_mutex_destroy(&server->rooms_mutex);

    printf("Server stopped\n");
}


void log_client(const Client *client) {
    printf("[%d] CLIENT_ID=%s THREAD=%d ACTIVE=%d LOGGED=%d ROOM=%s \n", client->socket, client->client_id, client->thread, client->active, client->logged_in, client->current_room);
}