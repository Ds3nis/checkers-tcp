//
// Created by Denis on 17.11.2025.
//

#ifndef SERVER_SERVER_H
#define SERVER_SERVER_H

#include <pthread.h>
#include <stdbool.h>
#include "game.h"
#include "protocol.h"

#define MAX_CLIENTS 100
#define MAX_ROOMS 50
#define BUFFER_SIZE 8192

typedef enum {
    CLIENT_STATE_CONNECTED,
    CLIENT_STATE_DISCONNECTED,
    CLIENT_STATE_RECONNECTING,
    CLIENT_STATE_TIMEOUT,
    CLIENT_STATE_REMOVED
} ClientState;


// Client structure
typedef struct {
    int socket;
    char client_id[MAX_PLAYER_NAME];
    pthread_t thread;
    bool active;
    bool logged_in;
    char current_room[MAX_ROOM_NAME];

    ClientState state;
    time_t last_pong_time;
    time_t disconnect_time;
    int missed_pongs;
    bool waiting_for_pong;
    pthread_mutex_t state_mutex;

    ClientViolations violations;
} Client;

// Server structure
typedef struct {
    int server_socket;
    int port;
    bool running;
    Client clients[MAX_CLIENTS];
    Room rooms[MAX_ROOMS];
    int client_count;
    int room_count;
    pthread_mutex_t clients_mutex;
    pthread_mutex_t rooms_mutex;

    pthread_t heartbeat_thread;
} Server;

typedef struct {
    Server *server;
    int client_socket;
    int client_idx;
} ClientThreadArgs;

// Function prototypes
int server_init(Server *server, int port);
void server_start(Server *server);
void server_stop(Server *server);
void* client_handler(void *arg);

// Client management
int add_client(Server *server, int socket);
void remove_client(Server *server, const char *client_id);
Client* find_client(Server *server, const char *client_id);

// Room management
Room* create_room(Server *server, const char *room_name, const char *creator);
Room* find_room(Server *server, const char *room_name);
int join_room(Server *server, const char *room_name, const char *player_name);
void leave_room(Server *server, const char *room_name, const char *player_name);
void remove_room(Server *server, const char *room_name);
void leave_room_on_disconnect(Server *server, const char *room_name, const char *player_name);

// Message handling
void handle_login(Server *server, Client *client, const char *data);
void handle_create_room(Server *server, Client *client, const char *data);
void handle_join_room(Server *server, Client *client, const char *data);
void handle_multi_move(Server *server, Client *client, const char *data);
void handle_move(Server *server, Client *client, const char *data);
void handle_leave_room(Server *server, Client *client, const char *data);
void handle_ping(Server *server, Client *client);
void handle_list_rooms(Server *server, Client *client);

// Utility functions
void cleanup_finished_game(Server *server, Room *room);
void send_message(int socket, OpCode op, const char *data);
void broadcast_to_room(Server *server, const char *room_name, OpCode op, const char *data);

void handle_reconnect_request(Server *server, Client *client, const char *data);

// Heartbeat functions
void* heartbeat_thread(void *arg);
void client_init_heartbeat(Client *client);
void client_update_pong(Client *client);
bool client_check_timeout(Client *client);
void client_mark_disconnected(Client *client);
void client_mark_reconnecting(Client *client);
void client_mark_reconnected(Client *client);
void client_mark_timeout(Client *client);
long client_get_disconnect_duration(const Client *client);
bool client_is_short_disconnect(const Client *client);
const char* client_get_state_string(ClientState state);

// Room state functions
void room_init_state(Room *room);
void room_pause_game(Room *room, const char *player_name);
void room_resume_game(Room *room);
void room_finish_game(Room *room, const char *reason);
bool room_should_timeout(const Room *room, int timeout_seconds);
long room_get_pause_duration(const Room *room);
const char* room_get_state_string(RoomState state);

// Disconnect handling
void handle_player_disconnect(Server *server, Client *client);
void handle_player_long_disconnect(Server *server, Client *client);
void check_room_pause_timeouts(Server *server);

void log_client (const Client *client);

#endif //SERVER_SERVER_H