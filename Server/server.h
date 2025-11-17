//
// Created by Denis on 17.11.2025.
//

#ifndef SERVER_SERVER_H
#define SERVER_SERVER_H

#ifndef SERVER_H
#define SERVER_H

#include <pthread.h>
#include <stdbool.h>
#include "game.h"

#define MAX_CLIENTS 100
#define MAX_ROOMS 50
#define BUFFER_SIZE 8192

// Client structure
typedef struct {
    int socket;
    char client_id[MAX_PLAYER_NAME];
    pthread_t thread;
    bool active;
    bool logged_in;
    char current_room[MAX_ROOM_NAME];
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
} Server;

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

// Message handling
void handle_login(Server *server, Client *client, const char *data);
void handle_create_room(Server *server, Client *client, const char *data);
void handle_join_room(Server *server, Client *client, const char *data);
void handle_move(Server *server, Client *client, const char *data);
void handle_leave_room(Server *server, Client *client, const char *data);
void handle_ping(Server *server, Client *client);

// Utility functions
void send_message(int socket, OpCode op, const char *data);
void broadcast_to_room(Server *server, const char *room_name, OpCode op, const char *data);

#endif // SERVER_H

#endif //SERVER_SERVER_H