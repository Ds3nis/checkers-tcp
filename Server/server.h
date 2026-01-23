//
// Created by Denis on 17.11.2025.
//

#ifndef SERVER_SERVER_H
#define SERVER_SERVER_H

#include <pthread.h>
#include <stdbool.h>
#include <time.h>
#include "game.h"
#include "protocol.h"
#include "client_state_machine.h"

#define MAX_CLIENTS 100
#define MAX_ROOMS 50
#define BUFFER_SIZE 8192

/**
 * Client connection states for heartbeat monitoring and reconnection.
 */
typedef enum {
    CLIENT_STATE_CONNECTED,      // Active connection with valid heartbeat
    CLIENT_STATE_DISCONNECTED,   // Connection lost, preserved for reconnection
    CLIENT_STATE_RECONNECTING,   // Actively reconnecting (intermediate state)
    CLIENT_STATE_TIMEOUT,        // Exceeded long disconnect threshold
    CLIENT_STATE_REMOVED         // Permanently removed from server
} ClientState;

/**
 * Client connection structure.
 * Represents a single client connection with state tracking,
 * heartbeat monitoring, and security violation tracking.
 */
typedef struct Client {
    int socket;                          // Client socket descriptor
    char client_id[MAX_PLAYER_NAME];     // Unique client identifier
    pthread_t thread;                    // Handler thread
    bool active;                         // Connection is active
    bool logged_in;                      // Client has completed login
    char current_room[MAX_ROOM_NAME];    // Currently joined room (empty if in lobby)

    // Heartbeat and reconnection state
    ClientState state;                   // Connection state
    ClientGameState game_state;          // Game logic state (lobby, room, in-game)
    time_t last_pong_time;              // Timestamp of last PONG received
    time_t disconnect_time;             // When disconnection was detected
    int missed_pongs;                   // Count of missed PONG responses
    bool waiting_for_pong;              // Waiting for PONG response to PING
    pthread_mutex_t state_mutex;        // Thread-safe state access

    // Security tracking
    ClientViolations violations;         // Protocol violation tracking
} Client;

/**
 * Main server structure.
 * Manages all clients, rooms, and threading infrastructure.
 */
typedef struct {
    int server_socket;                   // Listening socket
    int port;                            // Server port
    bool running;                        // Server is running
    Client clients[MAX_CLIENTS];         // All client connections
    Room rooms[MAX_ROOMS];               // All game rooms
    int client_count;                    // Number of active clients
    int room_count;                      // Number of active rooms
    pthread_mutex_t clients_mutex;       // Client list protection
    pthread_mutex_t rooms_mutex;         // Room list protection

    pthread_t heartbeat_thread;          // Heartbeat monitoring thread
} Server;

/**
 * Arguments passed to client handler threads.
 */
typedef struct {
    Server *server;
    int client_socket;
    int client_idx;
} ClientThreadArgs;

// ========== SERVER LIFECYCLE ==========

/**
 * Initializes server on specified port and address.
 * @return 0 on success, -1 on failure
 */
int server_init(Server *server, int port, const char *bind_address);

/**
 * Starts server and begins accepting connections.
 */
void server_start(Server *server);

/**
 * Stops server and cleans up all resources.
 */
void server_stop(Server *server);

/**
 * Main client connection handler (runs in separate thread per client).
 */
void* client_handler(void *arg);

// ========== CLIENT MANAGEMENT ==========

/**
 * Adds new client connection to server.
 * @return Client index or -1 if server full
 */
int add_client(Server *server, int socket);

/**
 * Finds client by ID.
 * @return Pointer to client or NULL if not found
 */
Client* find_client(Server *server, const char *client_id);

// ========== ROOM MANAGEMENT ==========

/**
 * Creates new game room.
 * @return Pointer to room or NULL if name taken/server full
 */
Room* create_room(Server *server, const char *room_name, const char *creator);

/**
 * Finds room by name.
 * @return Pointer to room or NULL if not found
 */
Room* find_room(Server *server, const char *room_name);

/**
 * Adds player to room.
 * @return 0 on success, negative error code on failure
 */
int join_room(Server *server, const char *room_name, const char *player_name);

/**
 * Removes player from room (explicit leave - destroys room).
 */
void leave_room(Server *server, const char *room_name, const char *player_name);

/**
 * Removes room from server.
 */
void remove_room(Server *server, const char *room_name);

/**
 * Handles player disconnect (preserves room for reconnection).
 */
void leave_room_on_disconnect(Server *server, const char *room_name, const char *player_name);

// ========== MESSAGE HANDLERS ==========

void handle_login(Server *server, Client *client, const char *data);
void handle_create_room(Server *server, Client *client, const char *data);
void handle_join_room(Server *server, Client *client, const char *data);
void handle_multi_move(Server *server, Client *client, const char *data);
void handle_move(Server *server, Client *client, const char *data);
void handle_leave_room(Server *server, Client *client, const char *data);
void handle_ping(Server *server, Client *client);
void handle_list_rooms(Server *server, Client *client);
void handle_reconnect_request(Server *server, Client *client, const char *data);

// ========== UTILITY FUNCTIONS ==========

/**
 * Cleans up finished game and returns players to lobby.
 */
void cleanup_finished_game(Server *server, Room *room);

/**
 * Sends protocol message to client socket.
 */
void send_message(int socket, OpCode op, const char *data);

/**
 * Broadcasts message to all players in a room.
 */
void broadcast_to_room(Server *server, const char *room_name, OpCode op, const char *data);

// ========== HEARTBEAT MONITORING ==========

/**
 * Main heartbeat monitoring thread.
 */
void* heartbeat_thread(void *arg);

/**
 * Initializes heartbeat system for client.
 */
void client_init_heartbeat(Client *client);

/**
 * Updates client state on PONG receipt.
 */
void client_update_pong(Client *client);

/**
 * Checks if client exceeded timeout thresholds.
 * @return true if client should be removed
 */
bool client_check_timeout(Client *client);

/**
 * Marks client as disconnected.
 */
void client_mark_disconnected(Client *client);

/**
 * Marks client as reconnecting.
 */
void client_mark_reconnecting(Client *client);

/**
 * Marks client as reconnected.
 */
void client_mark_reconnected(Client *client);

/**
 * Marks client as timed out.
 */
void client_mark_timeout(Client *client);

/**
 * Gets how long client has been disconnected.
 * @return Duration in seconds
 */
long client_get_disconnect_duration(const Client *client);

/**
 * Checks if disconnect qualifies as "short" (quick reconnect eligible).
 */
bool client_is_short_disconnect(const Client *client);

/**
 * Converts client state to string.
 */
const char* client_get_state_string(ClientState state);

// ========== ROOM STATE MANAGEMENT ==========

/**
 * Initializes room state tracking.
 */
void room_init_state(Room *room);

/**
 * Pauses game due to player disconnect.
 */
void room_pause_game(Room *room, const char *player_name);

/**
 * Resumes paused game after reconnection.
 */
void room_resume_game(Room *room);

/**
 * Marks game as finished.
 */
void room_finish_game(Room *room, const char *reason);

/**
 * Checks if paused room exceeded timeout.
 */
bool room_should_timeout(const Room *room, int timeout_seconds);

/**
 * Gets how long room has been paused.
 * @return Duration in seconds
 */
long room_get_pause_duration(const Room *room);

/**
 * Converts room state to string.
 */
const char* room_get_state_string(RoomState state);

// ========== DISCONNECT HANDLING ==========

/**
 * Handles player short-term disconnect (pauses game).
 */
void handle_player_disconnect(Server *server, Client *client);

/**
 * Handles player long-term disconnect (ends game, awards opponent).
 */
void handle_player_long_disconnect(Server *server, Client *client);

/**
 * Checks all paused rooms for timeout.
 */
void check_room_pause_timeouts(Server *server);

/**
 * Checks if client is eligible for reconnection.
 */
bool can_client_reconnect(Server *server, const char *player_name);

// ========== SECURITY & VALIDATION ==========

/**
 * Validates operation is allowed in client's current state.
 */
bool validate_operation(Server *server, Client *client, OpCode op);

/**
 * Logs client information for debugging.
 */
void log_client(const Client *client);

/**
 * Logs invalid operation attempt for security monitoring.
 */
void log_invalid_operation_attempt(Client *client, OpCode attempted_op);

/**
 * Disconnects client flagged as malicious.
 */
void disconnect_malicious_client(Server *server, Client *client,
                                DisconnectReason reason, const char *raw_message);

/**
 * Handles client disconnection detected by handler thread.
 */
void handle_client_disconnect(Server *server, Client *client, int socket);

/**
 * Removes client after timeout threshold exceeded.
 */
void remove_client_after_timeout(Server *server, const char *client_id);

#endif //SERVER_SERVER_H