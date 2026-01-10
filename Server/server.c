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

#define PING_INTERVAL_SEC 5              // Інтервал пінгів
#define PONG_TIMEOUT_SEC 3               // Таймаут очікування понгу
#define SHORT_DISCONNECT_THRESHOLD_SEC 30 // Поріг короткочасного відключення
#define LONG_DISCONNECT_THRESHOLD_SEC 60  // Поріг довгого відключення
#define MAX_MISSED_PONGS 3               // Максимум пропущених понгів


void client_init_heartbeat(Client *client) {
    client->state = CLIENT_STATE_CONNECTED;
    client->last_pong_time = time(NULL);
    client->disconnect_time = 0;
    client->missed_pongs = 0;
    client->waiting_for_pong = false;
    pthread_mutex_init(&client->state_mutex, NULL);
}

void client_update_pong(Client *client) {
    pthread_mutex_lock(&client->state_mutex);

    client->last_pong_time = time(NULL);
    client->missed_pongs = 0;
    client->waiting_for_pong = false;

    if (client->state == CLIENT_STATE_RECONNECTING ||
        client->state == CLIENT_STATE_DISCONNECTED) {
        client_mark_reconnected(client);
        }

    pthread_mutex_unlock(&client->state_mutex);
    printf("PONG received from %s\n", client->client_id);
}

bool client_check_timeout(Client *client) {
    pthread_mutex_lock(&client->state_mutex);

    if (client->state == CLIENT_STATE_REMOVED ||
        client->state == CLIENT_STATE_TIMEOUT) {
        pthread_mutex_unlock(&client->state_mutex);
        return true;
        }

    time_t now = time(NULL);
    long time_since_pong = now - client->last_pong_time;


    if (client->waiting_for_pong && time_since_pong > PONG_TIMEOUT_SEC) {
        client->missed_pongs++;
        client->waiting_for_pong = false;

        printf(" Client %s missed PONG (total: %d/%d)\n",
               client->client_id, client->missed_pongs, MAX_MISSED_PONGS);

        if (client->missed_pongs >= MAX_MISSED_PONGS) {
            printf("Client %s exceeded max missed PONGs\n", client->client_id);
            client_mark_disconnected(client);
        }
    }

    if (client->state == CLIENT_STATE_DISCONNECTED) {
        long disconnect_duration = client_get_disconnect_duration(client);

        if (disconnect_duration > LONG_DISCONNECT_THRESHOLD_SEC) {
            printf("Client %s long disconnect timeout (%ld sec)\n",
                   client->client_id, disconnect_duration);
            client_mark_timeout(client);
            pthread_mutex_unlock(&client->state_mutex);
            return true;
        }
    }

    pthread_mutex_unlock(&client->state_mutex);
    return false;
}

void client_mark_disconnected(Client *client) {
    if (client->state == CLIENT_STATE_CONNECTED) {
        client->state = CLIENT_STATE_DISCONNECTED;
        client->disconnect_time = time(NULL);
        printf("Client %s marked as DISCONNECTED\n", client->client_id);
    }
}

void client_mark_reconnecting(Client *client) {
    if (client->state == CLIENT_STATE_DISCONNECTED) {
        client->state = CLIENT_STATE_RECONNECTING;
        printf("🔄 Client %s is RECONNECTING\n", client->client_id);
    }
}

void client_mark_reconnected(Client *client) {
    long disconnect_duration = 0;

    if (client->disconnect_time > 0) {
        disconnect_duration = time(NULL) - client->disconnect_time;
    }

    client->state = CLIENT_STATE_CONNECTED;
    client->disconnect_time = 0;
    client->missed_pongs = 0;

    printf("✅ Client %s RECONNECTED (was offline for %ld sec)\n",
           client->client_id, disconnect_duration);
}

void client_mark_timeout(Client *client) {
    client->state = CLIENT_STATE_TIMEOUT;
    printf("⏱️ Client %s marked as TIMEOUT\n", client->client_id);
}

long client_get_disconnect_duration(const Client *client) {
    if (client->disconnect_time == 0) {
        return 0;
    }
    return time(NULL) - client->disconnect_time;
}

bool client_is_short_disconnect(const Client *client) {
    long duration = client_get_disconnect_duration(client);
    return duration > 0 && duration <= SHORT_DISCONNECT_THRESHOLD_SEC;
}

const char* client_get_state_string(ClientState state) {
    switch(state) {
        case CLIENT_STATE_CONNECTED: return "CONNECTED";
        case CLIENT_STATE_DISCONNECTED: return "DISCONNECTED";
        case CLIENT_STATE_RECONNECTING: return "RECONNECTING";
        case CLIENT_STATE_TIMEOUT: return "TIMEOUT";
        case CLIENT_STATE_REMOVED: return "REMOVED";
        default: return "UNKNOWN";
    }
}

void room_init_state(Room *room) {
    room->state = ROOM_STATE_WAITING;
    room->pause_start_time = 0;
    room->disconnected_player[0] = '\0';
    room->waiting_for_reconnect = false;
    pthread_mutex_init(&room->room_mutex, NULL);
}

void room_pause_game(Room *room, const char *player_name) {
    pthread_mutex_lock(&room->room_mutex);

    if (room->state != ROOM_STATE_ACTIVE) {
        pthread_mutex_unlock(&room->room_mutex);
        return;
    }

    room->state = ROOM_STATE_PAUSED;
    room->pause_start_time = time(NULL);
    strncpy(room->disconnected_player, player_name, MAX_PLAYER_NAME - 1);
    room->disconnected_player[MAX_PLAYER_NAME - 1] = '\0';
    room->waiting_for_reconnect = true;

    pthread_mutex_unlock(&room->room_mutex);

    printf("Game PAUSED in room %s (player %s disconnected)\n",
           room->name, player_name);
}

void room_resume_game(Room *room) {
    pthread_mutex_lock(&room->room_mutex);

    if (room->state != ROOM_STATE_PAUSED) {
        pthread_mutex_unlock(&room->room_mutex);
        return;
    }

    long pause_duration = room_get_pause_duration(room);

    room->state = ROOM_STATE_ACTIVE;
    room->pause_start_time = 0;
    room->disconnected_player[0] = '\0';
    room->waiting_for_reconnect = false;

    pthread_mutex_unlock(&room->room_mutex);

    printf("Game RESUMED in room %s (paused for %ld sec)\n",
           room->name, pause_duration);
}

void room_finish_game(Room *room, const char *reason) {
    pthread_mutex_lock(&room->room_mutex);

    room->state = ROOM_STATE_FINISHED;
    room->waiting_for_reconnect = false;

    pthread_mutex_unlock(&room->room_mutex);

    printf("Game FINISHED in room %s (reason: %s)\n", room->name, reason);
}

bool room_should_timeout(const Room *room, int timeout_seconds) {
    if (room->state != ROOM_STATE_PAUSED) {
        return false;
    }

    long pause_duration = room_get_pause_duration(room);
    return pause_duration >= timeout_seconds;
}

long room_get_pause_duration(const Room *room) {
    if (room->pause_start_time == 0) {
        return 0;
    }
    return time(NULL) - room->pause_start_time;
}

const char* room_get_state_string(RoomState state) {
    switch(state) {
        case ROOM_STATE_WAITING: return "WAITING";
        case ROOM_STATE_ACTIVE: return "ACTIVE";
        case ROOM_STATE_PAUSED: return "PAUSED";
        case ROOM_STATE_FINISHED: return "FINISHED";
        default: return "UNKNOWN";
    }
}

void* heartbeat_thread(void *arg) {
    Server *server = (Server*)arg;

    printf("Heartbeat thread started\n");

    while (server->running) {
        sleep(PING_INTERVAL_SEC);

        pthread_mutex_lock(&server->clients_mutex);

        for (int i = 0; i < MAX_CLIENTS; i++) {
            Client *client = &server->clients[i];

            if (!client->active || !client->logged_in) {
                continue;
            }

            if (!client->waiting_for_pong) {
                send_message(client->socket, OP_PING, "");
                client->waiting_for_pong = true;
                printf("PING sent to %s (socket %d)\n",
                       client->client_id, client->socket);
            }

            bool should_remove = client_check_timeout(client);

            if (should_remove) {
                printf("Client %s timed out, removing\n", client->client_id);

                if (client->current_room[0] != '\0') {
                    handle_player_long_disconnect(server, client);
                }
            }
            else if (client->state == CLIENT_STATE_DISCONNECTED) {
                if (client->current_room[0] != '\0') {
                    handle_player_disconnect(server, client);
                }
            }
        }

        pthread_mutex_unlock(&server->clients_mutex);

        check_room_pause_timeouts(server);
    }

    printf("💓 Heartbeat thread stopped\n");
    return NULL;
}


void handle_player_disconnect(Server *server, Client *client) {
    pthread_mutex_lock(&server->rooms_mutex);

    Room *room = find_room(server, client->current_room);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return;
    }

    if (room->state == ROOM_STATE_ACTIVE) {
        room_pause_game(room, client->client_id);

        char *other_player = NULL;
        if (strcmp(room->player1, client->client_id) == 0) {
            other_player = room->player2;
        } else {
            other_player = room->player1;
        }

        if (other_player[0] != '\0') {
            Client *other_client = find_client(server, other_player);
            if (other_client && other_client->state == CLIENT_STATE_CONNECTED) {
                char msg[256];
                snprintf(msg, sizeof(msg), "%s,%s", room->name, client->client_id);
                send_message(other_client->socket, OP_PLAYER_DISCONNECTED, msg);
                send_message(other_client->socket, OP_GAME_PAUSED, room->name);

                printf("Notified %s about %s disconnect\n",
                       other_player, client->client_id);
            }
        }
    }

    pthread_mutex_unlock(&server->rooms_mutex);
}

void handle_player_long_disconnect(Server *server, Client *client) {
    pthread_mutex_lock(&server->rooms_mutex);

    Room *room = find_room(server, client->current_room);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return;
    }

    printf("⏱️ Player %s long disconnect in room %s\n",
           client->client_id, room->name);

    // Визначити переможця
    char *winner = NULL;
    if (strcmp(room->player1, client->client_id) == 0) {
        winner = room->player2;
    } else {
        winner = room->player1;
    }

    // Завершити гру
    room_finish_game(room, "opponent_timeout");

    // Повідомити переможця
    if (winner[0] != '\0') {
        Client *winner_client = find_client(server, winner);
        if (winner_client && winner_client->state == CLIENT_STATE_CONNECTED) {
            char end_msg[256];
            snprintf(end_msg, sizeof(end_msg), "%s,opponent_timeout", winner);
            send_message(winner_client->socket, OP_GAME_END, end_msg);

            winner_client->current_room[0] = '\0';

            printf("🏆 %s wins by opponent timeout\n", winner);
        }
    }

    pthread_mutex_destroy(&room->room_mutex);
    memset(room, 0, sizeof(Room));
    server->room_count--;

    pthread_mutex_unlock(&server->rooms_mutex);

    client->state = CLIENT_STATE_REMOVED;
}

void check_room_pause_timeouts(Server *server) {
    pthread_mutex_lock(&server->rooms_mutex);

    for (int i = 0; i < MAX_ROOMS; i++) {
        Room *room = &server->rooms[i];

        if (room->state != ROOM_STATE_PAUSED) {
            continue;
        }

        // Перевірити чи пройшов таймаут (60 секунд)
        if (room_should_timeout(room, LONG_DISCONNECT_THRESHOLD_SEC)) {
            printf("⏱️ Room %s pause timeout exceeded\n", room->name);

            Client *disconnected = find_client(server, room->disconnected_player);

            if (disconnected) {
                handle_player_long_disconnect(server, disconnected);
            }
        }
    }

    pthread_mutex_unlock(&server->rooms_mutex);
}

void handle_reconnect_request(Server *server, Client *client, const char *data) {
    char room_name[MAX_ROOM_NAME];
    char player_name[MAX_PLAYER_NAME];

    if (sscanf(data, "%[^,],%s", room_name, player_name) != 2) {
        send_message(client->socket, OP_RECONNECT_FAIL, "Invalid format");
        return;
    }

    printf("🔄 Reconnect request: %s wants to rejoin room %s\n",
           player_name, room_name);

    pthread_mutex_lock(&server->rooms_mutex);

    Room *room = find_room(server, room_name);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        send_message(client->socket, OP_RECONNECT_FAIL, "Room not found");
        return;
    }

    bool is_player1 = (strcmp(room->player1, player_name) == 0);
    bool is_player2 = (strcmp(room->player2, player_name) == 0);

    if (!is_player1 && !is_player2) {
        pthread_mutex_unlock(&server->rooms_mutex);
        send_message(client->socket, OP_RECONNECT_FAIL, "Not a member");
        return;
    }

    pthread_mutex_lock(&server->clients_mutex);
    strncpy(client->current_room, room_name, MAX_ROOM_NAME - 1);
    client->current_room[MAX_ROOM_NAME - 1] = '\0';
    client_mark_reconnected(client);

    // ========== ВІДНОВИТИ СТАН ГРИ ==========
    if (room->game_started) {
        transition_client_state(client, CLIENT_GAME_STATE_IN_GAME);
    } else {
        transition_client_state(client, CLIENT_GAME_STATE_IN_ROOM_WAITING);
    }

    pthread_mutex_unlock(&server->clients_mutex);

    if (room->state == ROOM_STATE_PAUSED) {
        room_resume_game(room);

        send_message(client->socket, OP_RECONNECT_OK, room_name);
        send_message(client->socket, OP_GAME_RESUMED, room_name);

        char *board_json = game_board_to_json(&room->game);
        send_message(client->socket, OP_GAME_STATE, board_json);

        char *other_player = is_player1 ? room->player2 : room->player1;
        if (other_player[0] != '\0') {
            Client *other = find_client(server, other_player);
            if (other && other->state == CLIENT_STATE_CONNECTED) {
                char msg[256];
                snprintf(msg, sizeof(msg), "%s,%s", room_name, player_name);
                send_message(other->socket, OP_PLAYER_RECONNECTED, msg);
                send_message(other->socket, OP_GAME_RESUMED, room_name);
            }
        }

        printf("✅ %s reconnected to room %s (game resumed)\n",
               player_name, room_name);
    } else {
        send_message(client->socket, OP_RECONNECT_OK, room_name);

        char *board_json = game_board_to_json(&room->game);
        send_message(client->socket, OP_GAME_STATE, board_json);
    }

    pthread_mutex_unlock(&server->rooms_mutex);
}



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

            client_init_heartbeat(&server->clients[i]);
            server->clients[i].game_state = CLIENT_GAME_STATE_NOT_LOGGED_IN;
            server->clients[i].violations.invalid_message_count = 0;
            server->clients[i].violations.unknown_opcode_count = 0;
            server->clients[i].violations.last_violation_time = 0;
            printf("New client initialized in state: %s\n",
                              client_game_state_to_string(server->clients[i].game_state));
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

    Client *client = NULL;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active &&
            strcmp(server->clients[i].client_id, client_id) == 0) {
            client = &server->clients[i];
            break;
            }
    }

    if (!client) {
        pthread_mutex_unlock(&server->clients_mutex);
        return;
    }

    // ========== ВИПРАВЛЕНО: Розрізняти відключення та явний вихід ==========

    bool is_disconnect = (client->state == CLIENT_STATE_DISCONNECTED ||
                         client->state == CLIENT_STATE_TIMEOUT);
    bool is_in_game = (client->current_room[0] != '\0');

    if (is_in_game) {
        if (is_disconnect) {
            // При відключенні - НЕ видаляємо кімнату (чекаємо реконект)
            printf("💔 Client %s disconnected (room preserved)\n", client_id);
            leave_room_on_disconnect(server, client->current_room, client_id);
        } else {
            // При явному виході - видаляємо кімнату
            printf("🚪 Client %s explicitly leaving\n", client_id);
            leave_room(server, client->current_room, client_id);
        }
    }

    close(client->socket);
    client->active = false;
    pthread_mutex_destroy(&client->state_mutex);

    server->client_count--;
    printf("Client %s removed (total clients: %d)\n", client_id, server->client_count);

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

            room_init_state(&server->rooms[i]);
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
        return -1;
    }

    if (room->players_count >= 2) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return -2;
    }

    if (strcmp(room->player1, player_name) == 0 ||
        strcmp(room->player2, player_name) == 0) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return -3;
    }

    pthread_mutex_unlock(&server->rooms_mutex);

    pthread_mutex_lock(&server->clients_mutex);
    Client *client = find_client(server, player_name);
    if (!client) {
        pthread_mutex_unlock(&server->clients_mutex);
        return -5;
    }

    if (client->current_room[0] != '\0') {
        pthread_mutex_unlock(&server->clients_mutex);
        return -4;
    }
    pthread_mutex_unlock(&server->clients_mutex);

    pthread_mutex_lock(&server->rooms_mutex);


    room = find_room(server, room_name);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return -1;
    }

    if (room->players_count >= 2) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return -2;
    }

    if (room->player1[0] == '\0') {
        strncpy(room->player1, player_name, MAX_PLAYER_NAME - 1);
        room->player1[MAX_PLAYER_NAME - 1] = '\0';
        room->players_count = 1;
    } else if (room->player2[0] == '\0') {
        strncpy(room->player2, player_name, MAX_PLAYER_NAME - 1);
        room->player2[MAX_PLAYER_NAME - 1] = '\0';
        room->players_count = 2;
    }

    if (room->players_count == 2 && !room->game_started) {
        init_game(&room->game, room->player1, room->player2);
        room->game_started = true;
        room->state = ROOM_STATE_ACTIVE;

        printf("✅ Game initialized in room %s: %s vs %s\n",
               room_name, room->player1, room->player2);
    }

    pthread_mutex_unlock(&server->rooms_mutex);
    return 0;
}

void leave_room_on_disconnect(Server *server, const char *room_name, const char *player_name) {
    pthread_mutex_lock(&server->rooms_mutex);

    Room *room = find_room(server, room_name);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return;
    }

    printf("Player %s disconnected from room %s (room preserved for reconnect)\n",
           player_name, room_name);

    pthread_mutex_unlock(&server->rooms_mutex);
}


void leave_room(Server *server, const char *room_name, const char *player_name) {
    pthread_mutex_lock(&server->rooms_mutex);

    Room *room = find_room(server, room_name);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return;
    }

    printf("🚪 Player %s explicitly left room %s\n", player_name, room_name);

    room->players_count--;

    if (room->players_count == 0) {
        pthread_mutex_destroy(&room->room_mutex);
        memset(room, 0, sizeof(Room));
        server->room_count--;
        printf("🗑️ Room %s removed (no players left)\n", room_name);
    } else {
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

        pthread_mutex_destroy(&room->room_mutex);
        memset(room, 0, sizeof(Room));
        server->room_count--;
        printf("🗑️ Room %s removed (player left)\n", room_name);
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

    transition_client_state(client, CLIENT_GAME_STATE_IN_LOBBY);
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
        transition_client_state(client, CLIENT_GAME_STATE_IN_GAME);
        char game_start_msg[512];
        snprintf(game_start_msg, sizeof(game_start_msg), "%s,%s,%s,%s",
                room_name, room->player1, room->player2, room->game.current_turn);
        broadcast_to_room(server, room_name, OP_GAME_START, game_start_msg);

        // Send initial board state
        char *board_json = game_board_to_json(&room->game);
        broadcast_to_room(server, room_name, OP_GAME_STATE, board_json);
    }else {
        transition_client_state(client, CLIENT_GAME_STATE_IN_ROOM_WAITING);
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

    print_board(&room->game);
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
        cleanup_finished_game(server, room);
        printf("🏆 Game over! Winner: %s\n", winner);
    }
}

void handle_multi_move(Server *server, Client *client, const char *data) {
    if (!client->logged_in || client->current_room[0] == '\0') {
        send_message(client->socket, OP_ERROR, "Not in a game");
        return;
    }

    printf("\n=== HANDLE MULTI MOVE ===\n");
    printf("Data: %s\n", data);

    // Parse: room_name,player_name,path_length,r1,c1,r2,c2,...
    char room_name[MAX_ROOM_NAME];
    char player_name[MAX_PLAYER_NAME];
    int path_length;

    // Парсити перші 3 поля
    int parsed = sscanf(data, "%[^,],%[^,],%d", room_name, player_name, &path_length);

    if (parsed != 3 || path_length < 2 || path_length > 20) {
        send_message(client->socket, OP_INVALID_MOVE, "Invalid multi-move format");
        printf("❌ Parse error: parsed=%d, path_length=%d\n", parsed, path_length);
        return;
    }

    printf("Room: %s, Player: %s, Path length: %d\n", room_name, player_name, path_length);

    Room *room = find_room(server, room_name);
    if (!room || !room->game_started) {
        send_message(client->socket, OP_ERROR, "Game not found");
        return;
    }

    int path[20][2]; // Максимум 20 позицій
    const char *ptr = data;

    // Пропустити перші 3 поля
    for (int i = 0; i < 3; i++) {
        ptr = strchr(ptr, ',');
        if (!ptr) {
            send_message(client->socket, OP_INVALID_MOVE, "Invalid path data");
            return;
        }
        ptr++; // Пропустити кому
    }

    // Парсити координати
    for (int i = 0; i < path_length; i++) {
        if (sscanf(ptr, "%d,%d", &path[i][0], &path[i][1]) != 2) {
            send_message(client->socket, OP_INVALID_MOVE, "Invalid coordinates");
            printf("❌ Failed to parse position %d\n", i);
            return;
        }

        printf("  Position %d: (%d, %d)\n", i, path[i][0], path[i][1]);

        // Перейти до наступної пари
        ptr = strchr(ptr, ',');
        if (ptr) ptr++;
        ptr = strchr(ptr, ',');
        if (ptr && i < path_length - 1) ptr++;
    }

    // Валідувати та застосувати ланцюжок ходів
    printf("\n=== VALIDATING MULTI-MOVE CHAIN ===\n");

    for (int i = 0; i < path_length - 1; i++) {
        int from_row = path[i][0];
        int from_col = path[i][1];
        int to_row = path[i + 1][0];
        int to_col = path[i + 1][1];

        printf("Step %d: (%d,%d) -> (%d,%d)\n", i + 1, from_row, from_col, to_row, to_col);

        print_board(&room->game);
        if (!validate_move(&room->game, from_row, from_col, to_row, to_col, player_name)) {
            send_message(client->socket, OP_INVALID_MOVE, "Invalid move in chain");
            printf("❌ Step %d failed validation\n", i + 1);
            return;
        }

        // Застосувати крок
        apply_move(&room->game, from_row, from_col, to_row, to_col);
        printf("✅ Step %d applied\n", i + 1);
    }

    printf("=== MULTI-MOVE CHAIN COMPLETED ===\n\n");

    // Змінити хід
    change_turn(&room->game);

    // Відправити оновлену дошку
    char *board_json = game_board_to_json(&room->game);
    broadcast_to_room(server, room_name, OP_GAME_STATE, board_json);

    // Перевірити кінець гри
    char winner[MAX_PLAYER_NAME];
    if (check_game_over(&room->game, winner)) {
        char end_msg[256];
        snprintf(end_msg, sizeof(end_msg), "%s,no_pieces", winner);
        broadcast_to_room(server, room_name, OP_GAME_END, end_msg);
        cleanup_finished_game(server, room);
        printf("🏆 Game over! Winner: %s\n", winner);
    }
}

void cleanup_finished_game(Server *server, Room *room) {
    printf("🧹 Cleaning up finished game in room: %s\n", room->name);

    pthread_mutex_lock(&server->clients_mutex);
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active &&
            strcmp(server->clients[i].current_room, room->name) == 0) {

            Client *client = &server->clients[i];
            printf("  Removing player %s from room\n", client->client_id);
            transition_client_state(client, CLIENT_GAME_STATE_IN_LOBBY);
            client->current_room[0] = '\0';
            send_message(client->socket, OP_ROOM_LEFT, room->name);
            }
    }
    pthread_mutex_unlock(&server->clients_mutex);

    pthread_mutex_lock(&server->rooms_mutex);
    pthread_mutex_destroy(&room->room_mutex);
    memset(room, 0, sizeof(Room));
    server->room_count--;
    pthread_mutex_unlock(&server->rooms_mutex);

    printf("✅ Room %s cleaned up\n", room->name);
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
    transition_client_state(client, CLIENT_GAME_STATE_IN_LOBBY);
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

    printf("Handling client connection from client socket %d\n", client_socket);

    free(args);

    char recv_buffer[BUFFER_SIZE];
    char message_buffer[BUFFER_SIZE * 2];
    int message_pos = 0;

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

    ClientViolations violations = {0, 0, 0};
    memset(message_buffer, 0, sizeof(message_buffer));

    while (server->running && client->active) {
        memset(recv_buffer, 0, BUFFER_SIZE);
        int bytes = recv(client_socket, recv_buffer, BUFFER_SIZE - 1, 0);

        if (bytes <= 0) {
            break;
        }

        for (int i = 0; i < bytes; i++) {
            char current_char = recv_buffer[i];

            if (message_pos >= sizeof(message_buffer) - 1) {
                fprintf(stderr, "SECURITY: Message buffer overflow from socket %d\n",
                         client_socket);

                DisconnectReason reason = DISCONNECT_REASON_BUFFER_OVERFLOW;
                disconnect_malicious_client(server, client, reason, message_buffer);

                return NULL;
            }

            message_buffer[message_pos++] = current_char;

            if (current_char == '\n') {
                message_buffer[message_pos - 1] = '\0';

                Message msg;
                DisconnectReason disconnect_reason;

                int parse_result = parse_message(message_buffer, &msg, &disconnect_reason);

                if (parse_result == 0) {
                    log_message("RECV", &msg);

                    if (!validate_operation(server, client, msg.op)) {
                        if (!client->active) return NULL;
                        message_pos = 0;
                        memset(message_buffer, 0, sizeof(message_buffer));
                        continue;
                    }

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
                        case OP_MULTI_MOVE:
                            handle_multi_move(server, client, msg.data);
                            break;
                        case OP_LEAVE_ROOM:
                            handle_leave_room(server, client, msg.data);
                            break;
                        case OP_PING:
                            handle_ping(server, client);
                            break;
                        case OP_PONG:
                            client_update_pong(client);
                            break;
                        case OP_LIST_ROOMS:
                            handle_list_rooms(server, client);
                            break;
                        case OP_RECONNECT_REQUEST:
                            handle_reconnect_request(server, client, msg.data);
                            break;
                        default:
                            fprintf(stderr, "Unknown OpCode %d from client %s\n",
                                    msg.op, client->client_id[0] ? client->client_id : "anonymous");
                            send_message(client_socket, OP_ERROR, "Unknown operation");
                            break;
                    }
                } else {
                    fprintf(stderr, "Failed to parse message from socket %d: %s\n",
                           client_socket, message_buffer);

                    if (should_disconnect_client(&violations)) {
                        disconnect_malicious_client(server, client,
                                                   disconnect_reason, message_buffer);
                        return NULL;
                    } else {
                        fprintf(stderr, "Warning: Protocol violation %d/%d for socket %d\n",
                                violations.invalid_message_count, MAX_VIOLATIONS, client_socket);

                        char warning_msg[256];
                        snprintf(warning_msg, sizeof(warning_msg),
                                "Invalid message format. Warning %d/%d",
                                violations.invalid_message_count, MAX_VIOLATIONS);
                        send_message(client_socket, OP_ERROR, warning_msg);
                    }
                }

                message_pos = 0;
                memset(message_buffer, 0, sizeof(message_buffer));
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

bool validate_operation(Server *server, Client *client, OpCode op) {
    if (!is_operation_allowed(client->game_state, op)) {
        log_invalid_operation_attempt(client, op);

        client->violations.unknown_opcode_count++;

        if (client->violations.unknown_opcode_count >= MAX_VIOLATIONS) {
            fprintf(stderr, "🚨 Client exceeded invalid operation attempts (1/1)\n");

            char error_msg[256];
            snprintf(error_msg, sizeof(error_msg),
                     "Repeated attempts to use invalid operation. State: %s, Operation: %d",
                     client_game_state_to_string(client->game_state), op);

            disconnect_malicious_client(server, client,
                                       DISCONNECT_REASON_SUSPICIOUS_ACTIVITY,
                                       error_msg);
            return false;
        }

        char warning[256];
        snprintf(warning, sizeof(warning),
                 "Operation %d not allowed in state %s. Warning %d/3",
                 op, client_game_state_to_string(client->game_state),
                 client->violations.unknown_opcode_count);
        send_message(client->socket, OP_ERROR, warning);

        return false;
    }

    return true;
}

void server_start(Server *server) {
    server->running = true;

    if (pthread_create(&server->heartbeat_thread, NULL, heartbeat_thread, server) != 0) {
        perror("Failed to create heartbeat thread");
        return;
    }

    printf("💓 Heartbeat thread started\n");
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

    // Зупинити heartbeat потік
    pthread_cancel(server->heartbeat_thread);
    pthread_join(server->heartbeat_thread, NULL);

    pthread_mutex_lock(&server->clients_mutex);
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active) {
            close(server->clients[i].socket);
            pthread_mutex_destroy(&server->clients[i].state_mutex);
        }
    }
    pthread_mutex_unlock(&server->clients_mutex);

    pthread_mutex_lock(&server->rooms_mutex);
    for (int i = 0; i < MAX_ROOMS; i++) {
        if (server->rooms[i].players_count > 0) {
            pthread_mutex_destroy(&server->rooms[i].room_mutex);
        }
    }
    pthread_mutex_unlock(&server->rooms_mutex);

    close(server->server_socket);
    pthread_mutex_destroy(&server->clients_mutex);
    pthread_mutex_destroy(&server->rooms_mutex);

    printf("Server stopped\n");
}


void log_client(const Client *client) {
    printf("[%d] CLIENT_ID=%s THREAD=%d ACTIVE=%d LOGGED=%d ROOM=%s \n", client->socket, client->client_id, client->thread, client->active, client->logged_in, client->current_room);
}