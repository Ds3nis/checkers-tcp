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
#include "client_state_machine.h"

#define PING_INTERVAL_SEC 5              // Ping interval
#define PONG_TIMEOUT_SEC 3               // Pong wait timeout
#define SHORT_DISCONNECT_THRESHOLD_SEC 40 // Short-term disconnection threshold
#define LONG_DISCONNECT_THRESHOLD_SEC 80  // Long shutdown threshold
#define MAX_MISSED_PONGS 3               // Maximum number of missed pongs


/**
 * Initializes the heartbeat monitoring system for a client.
 * Sets up initial state, timestamps, and mutex for thread-safe operations.
 *
 * @param client Pointer to the client structure to initialize
 */
void client_init_heartbeat(Client *client) {
    client->state = CLIENT_STATE_CONNECTED;
    client->last_pong_time = time(NULL);
    client->disconnect_time = 0;
    client->missed_pongs = 0;
    client->waiting_for_pong = false;
    pthread_mutex_init(&client->state_mutex, NULL);
}

/**
 * Updates client state when a PONG response is received.
 * Resets missed pong counter and marks client as reconnected if previously disconnected.
 * Thread-safe operation using client state mutex.
 *
 * @param client Pointer to the client that sent the PONG
 */
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

/**
 * Checks if a client has exceeded timeout thresholds.
 * Monitors PONG responses and disconnect duration to determine if client should be removed.
 *
 * @param client Pointer to the client to check
 * @return true if client has timed out and should be removed, false otherwise
 */
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

        printf("Client %s missed PONG (total: %d/%d)\n",
               client->client_id, client->missed_pongs, MAX_MISSED_PONGS);

        if (client->missed_pongs >= MAX_MISSED_PONGS && client->state != CLIENT_STATE_DISCONNECTED) {
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


/**
 * Marks a client as disconnected and closes their socket.
 * Only transitions from CONNECTED state to prevent state conflicts.
 * Closing the socket will wake up blocking recv() calls in the client handler thread.
 *
 * @param client Pointer to the client to mark as disconnected
 */
void client_mark_disconnected(Client *client) {
    if (client->state == CLIENT_STATE_CONNECTED) {
        client->state = CLIENT_STATE_DISCONNECTED;
        client->disconnect_time = time(NULL);

        if (client->socket > 0) {
            printf("Closing socket %d to wake recv()\n", client->socket);
            close(client->socket);
            client->socket = -1;
        }

        printf("Client %s marked as DISCONNECTED\n", client->client_id);
    }
}

/**
 * Transitions client from DISCONNECTED to RECONNECTING state.
 * This intermediate state helps track ongoing reconnection attempts.
 *
 * @param client Pointer to the client attempting to reconnect
 */
void client_mark_reconnecting(Client *client) {
    if (client->state == CLIENT_STATE_DISCONNECTED) {
        client->state = CLIENT_STATE_RECONNECTING;
        printf("Client %s is RECONNECTING\n", client->client_id);
    }
}

/**
 * Marks client as successfully reconnected.
 * Resets disconnect tracking and restores client to CONNECTED state.
 *
 * @param client Pointer to the client that reconnected
 */
void client_mark_reconnected(Client *client) {
    long disconnect_duration = 0;

    if (client->disconnect_time > 0) {
        disconnect_duration = time(NULL) - client->disconnect_time;
    }


    client->state = CLIENT_STATE_CONNECTED;
    client->disconnect_time = 0;
    client->missed_pongs = 0;

    printf("Client %s RECONNECTED (was offline for %ld sec)\n",
           client->client_id, disconnect_duration);
}

/**
 * Marks client as timed out (exceeded long disconnect threshold).
 * This is the final state before removal.
 *
 * @param client Pointer to the client to mark as timed out
 */
void client_mark_timeout(Client *client) {
    client->state = CLIENT_STATE_TIMEOUT;
    printf("Client %s marked as TIMEOUT\n", client->client_id);
}

/**
 * Calculates how long a client has been disconnected.
 *
 * @param client Pointer to the client
 * @return Duration in seconds, or 0 if client is not disconnected
 */
long client_get_disconnect_duration(const Client *client) {
    if (client->disconnect_time == 0) {
        return 0;
    }
    return time(NULL) - client->disconnect_time;
}

/**
 * Checks if client disconnect qualifies as "short" (eligible for quick reconnect).
 *
 * @param client Pointer to the client
 * @return true if disconnect duration is within short threshold
 */
bool client_is_short_disconnect(const Client *client) {
    long duration = client_get_disconnect_duration(client);
    return duration > 0 && duration <= SHORT_DISCONNECT_THRESHOLD_SEC;
}

/**
 * Converts client state enum to human-readable string.
 *
 * @param state Client state to convert
 * @return String representation of the state
 */
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

/**
 * Initializes room state management system.
 * Sets up initial state, pause tracking, and mutex for thread-safe operations.
 *
 * @param room Pointer to the room structure to initialize
 */
void room_init_state(Room *room) {
    room->state = ROOM_STATE_WAITING;
    room->pause_start_time = 0;
    room->disconnected_player[0] = '\0';
    room->waiting_for_reconnect = false;
    pthread_mutex_init(&room->room_mutex, NULL);
}

/**
 * Pauses an active game when a player disconnects.
 * Records which player disconnected and when the pause started.
 * Only pauses games that are currently active.
 *
 * @param room Pointer to the room containing the game
 * @param player_name Name of the player who disconnected
 */
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

/**
 * Resumes a paused game after player reconnection.
 * Calculates and logs how long the game was paused.
 * Only resumes games that are currently paused.
 *
 * @param room Pointer to the room containing the paused game
 */
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

/**
 * Marks a game as finished and stops waiting for reconnection.
 *
 * @param room Pointer to the room containing the game
 * @param reason String describing why the game ended
 */
void room_finish_game(Room *room, const char *reason) {
    pthread_mutex_lock(&room->room_mutex);

    room->state = ROOM_STATE_FINISHED;
    room->waiting_for_reconnect = false;

    pthread_mutex_unlock(&room->room_mutex);

    printf("Game FINISHED in room %s (reason: %s)\n", room->name, reason);
}

/**
 * Checks if a paused room has exceeded the timeout threshold.
 *
 * @param room Pointer to the room to check
 * @param timeout_seconds Maximum allowed pause duration in seconds
 * @return true if pause duration exceeds timeout, false otherwise
 */
bool room_should_timeout(const Room *room, int timeout_seconds) {
    if (room->state != ROOM_STATE_PAUSED) {
        return false;
    }

    long pause_duration = room_get_pause_duration(room);
    return pause_duration >= timeout_seconds;
}

/**
 * Calculates how long a room has been paused.
 *
 * @param room Pointer to the room
 * @return Pause duration in seconds, or 0 if room is not paused
 */
long room_get_pause_duration(const Room *room) {
    if (room->pause_start_time == 0) {
        return 0;
    }
    return time(NULL) - room->pause_start_time;
}

/**
 * Converts room state enum to human-readable string.
 *
 * @param state Room state to convert
 * @return String representation of the state
 */
const char* room_get_state_string(RoomState state) {
    switch(state) {
        case ROOM_STATE_WAITING: return "WAITING";
        case ROOM_STATE_ACTIVE: return "ACTIVE";
        case ROOM_STATE_PAUSED: return "PAUSED";
        case ROOM_STATE_FINISHED: return "FINISHED";
        default: return "UNKNOWN";
    }
}

/**
 * Main heartbeat monitoring thread.
 * Periodically sends PING messages to all connected clients and monitors their responses.
 * Handles timeouts, disconnections, and room pause timeouts.
 *
 * This thread runs continuously while the server is active and performs:
 * 1. Sends PING to all active clients at regular intervals
 * 2. Checks for clients that haven't responded with PONG
 * 3. Removes clients that exceed timeout thresholds
 * 4. Checks for games paused too long due to disconnection
 *
 * @param arg Pointer to the Server structure
 * @return NULL when thread exits
 */
void* heartbeat_thread(void *arg) {
    Server *server = (Server*)arg;

    printf("💓 Heartbeat thread started\n");

    while (server->running) {
        sleep(PING_INTERVAL_SEC);

        typedef struct {
            char client_id[MAX_PLAYER_NAME];
            int socket;
            bool should_remove;
            bool should_handle_disconnect;
            char current_room[MAX_ROOM_NAME];
        } ClientAction;

        ClientAction actions[MAX_CLIENTS];
        int action_count = 0;

        pthread_mutex_lock(&server->clients_mutex);

        for (int i = 0; i < MAX_CLIENTS; i++) {
            Client *client = &server->clients[i];

            if (!client->active || !client->logged_in) {
                continue;
            }

            pthread_mutex_lock(&client->state_mutex);
            ClientState state = client->state;
            pthread_mutex_unlock(&client->state_mutex);

            if (state == CLIENT_STATE_RECONNECTING) {
                printf("Skipping %s (reconnecting)\n", client->client_id);
                continue;
            }

            if (state == CLIENT_STATE_REMOVED) {
                continue;
            }

            if (client->socket > 0 && !client->waiting_for_pong) {
                send_message(client->socket, OP_PING, "");
                client->waiting_for_pong = true;
                printf("💓 PING sent to %s (socket %d)\n",
                       client->client_id, client->socket);
            }

            bool should_remove = client_check_timeout(client);

            if (should_remove || state == CLIENT_STATE_DISCONNECTED) {
                if (action_count < MAX_CLIENTS) {
                    strncpy(actions[action_count].client_id,
                           client->client_id, MAX_PLAYER_NAME - 1);
                    actions[action_count].client_id[MAX_PLAYER_NAME - 1] = '\0';
                    actions[action_count].socket = client->socket;
                    actions[action_count].should_remove = should_remove;
                    actions[action_count].should_handle_disconnect =
                        (state == CLIENT_STATE_DISCONNECTED && !should_remove);

                    strncpy(actions[action_count].current_room,
                           client->current_room, MAX_ROOM_NAME - 1);
                    actions[action_count].current_room[MAX_ROOM_NAME - 1] = '\0';

                    action_count++;
                }
            }
        }

        pthread_mutex_unlock(&server->clients_mutex);

        for (int i = 0; i < action_count; i++) {
            ClientAction *action = &actions[i];

            if (action->should_remove) {
                printf("Client %s timed out (80s), removing\n",
                       action->client_id);

                pthread_mutex_lock(&server->clients_mutex);
                Client *client = find_client(server, action->client_id);

                if (!client) {
                    pthread_mutex_unlock(&server->clients_mutex);
                    printf("Client %s already removed\n", action->client_id);
                    continue;
                }

                pthread_mutex_lock(&client->state_mutex);
                ClientState current_state = client->state;
                pthread_mutex_unlock(&client->state_mutex);

                if (current_state == CLIENT_STATE_RECONNECTING) {
                    pthread_mutex_unlock(&server->clients_mutex);
                    printf("Client %s started reconnecting, skipping removal\n",
                           action->client_id);
                    continue;
                }

                pthread_mutex_unlock(&server->clients_mutex);

                if (action->current_room[0] != '\0') {
                    pthread_mutex_lock(&server->clients_mutex);
                    client = find_client(server, action->client_id);
                    if (client) {
                        pthread_mutex_unlock(&server->clients_mutex);
                        handle_player_long_disconnect(server, client);
                    } else {
                        pthread_mutex_unlock(&server->clients_mutex);
                    }
                } else {
                    remove_client_after_timeout(server, action->client_id);
                }
            }
            else if (action->should_handle_disconnect) {
                if (action->current_room[0] != '\0') {
                    pthread_mutex_lock(&server->clients_mutex);
                    Client *client = find_client(server, action->client_id);
                    if (client) {
                        pthread_mutex_unlock(&server->clients_mutex);
                        handle_player_disconnect(server, client);
                    } else {
                        pthread_mutex_unlock(&server->clients_mutex);
                    }
                }
            }
        }

        check_room_pause_timeouts(server);
    }

    printf("💓 Heartbeat thread stopped\n");
    return NULL;
}

/**
 * Removes a client after they have exceeded the timeout threshold.
 * Closes socket, marks client as removed, and decrements client count.
 * Thread-safe operation.
 *
 * @param server Pointer to the server
 * @param client_id ID of the client to remove
 */
void remove_client_after_timeout(Server *server, const char *client_id) {
    pthread_mutex_lock(&server->clients_mutex);

    Client *client = find_client(server, client_id);
    if (!client) {
        pthread_mutex_unlock(&server->clients_mutex);
        return;
    }

    printf("Removing timed-out client '%s'\n", client_id);

    if (client->socket > 0) {
        close(client->socket);
    }


    pthread_mutex_lock(&client->state_mutex);
    client->state = CLIENT_STATE_REMOVED;
    client->active = false;
    pthread_mutex_unlock(&client->state_mutex);

    pthread_mutex_destroy(&client->state_mutex);

    server->client_count--;

    printf("Client '%s' removed (total: %d)\n",
           client_id, server->client_count);

    pthread_mutex_unlock(&server->clients_mutex);
}

/**
 * Handles player disconnection from a game.
 * Behavior depends on room state:
 * - WAITING: Notifies other player
 * - ACTIVE: Pauses game and notifies opponent
 *
 * @param server Pointer to the server
 * @param client Pointer to the disconnected client
 */
void handle_player_disconnect(Server *server, Client *client) {
    pthread_mutex_lock(&server->rooms_mutex);

    Room *room = find_room(server, client->current_room);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return;
    }

    if (room->state == ROOM_STATE_WAITING) {
        printf("Player %s disconnected from waiting room %s\n",
               client->client_id, room->name);

        Client *other = NULL;
        if (strcmp(room->player1, client->client_id) != 0 && room->player1[0] != '\0') {
            other = find_client(server, room->player1);
        } else if (room->player2[0] != '\0') {
            other = find_client(server, room->player2);
        }

        if (other) {
            send_message(other->socket, OP_PLAYER_DISCONNECTED,
                        client->client_id);
        }
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

/**
 * Handles long-term player disconnection (exceeded 80 second threshold).
 * Awards victory to opponent by timeout and cleans up the room.
 *
 * @param server Pointer to the server
 * @param client Pointer to the client who timed out
 */
void handle_player_long_disconnect(Server *server, Client *client) {
    pthread_mutex_lock(&server->rooms_mutex);

    Room *room = find_room(server, client->current_room);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return;
    }

    printf("Player %s long disconnect in room %s\n",
           client->client_id, room->name);

    char *winner = NULL;
    if (strcmp(room->player1, client->client_id) == 0) {
        winner = room->player2;
    } else {
        winner = room->player1;
    }

    room_finish_game(room, "opponent_timeout");

    if (winner[0] != '\0') {
        Client *winner_client = find_client(server, winner);
        if (winner_client && winner_client->state == CLIENT_STATE_CONNECTED) {
            char end_msg[256];
            snprintf(end_msg, sizeof(end_msg), "%s,opponent_timeout", winner);
            send_message(winner_client->socket, OP_GAME_END, end_msg);

            winner_client->current_room[0] = '\0';

            printf("%s wins by opponent timeout\n", winner);
        }
    }

    pthread_mutex_destroy(&room->room_mutex);
    memset(room, 0, sizeof(Room));
    server->room_count--;

    pthread_mutex_unlock(&server->rooms_mutex);

    client->state = CLIENT_STATE_REMOVED;
}


/**
 * Checks all paused rooms for timeout and handles expired pauses.
 * Called periodically by the heartbeat thread.
 *
 * @param server Pointer to the server
 */
void check_room_pause_timeouts(Server *server) {
    pthread_mutex_lock(&server->rooms_mutex);

    for (int i = 0; i < MAX_ROOMS; i++) {
        Room *room = &server->rooms[i];

        if (room->state != ROOM_STATE_PAUSED) {
            continue;
        }

        // Check if pause exceeded long disconnect threshold
        if (room_should_timeout(room, LONG_DISCONNECT_THRESHOLD_SEC)) {
            printf("Room %s pause timeout exceeded\n", room->name);

            Client *disconnected = find_client(server, room->disconnected_player);

            if (disconnected) {
                handle_player_long_disconnect(server, disconnected);
            }
        }
    }

    pthread_mutex_unlock(&server->rooms_mutex);
}


/**
 * Handles client reconnection requests.
 * Validates reconnection eligibility, transfers socket to existing client structure,
 * and restores client to their previous game state (lobby, waiting room, or active game).
 *
 * Protocol format: "room_name,player_name" or just "player_name" for lobby reconnect
 *
 * @param server Pointer to the server
 * @param temp_client Temporary client structure for the new connection
 * @param data Reconnection request data containing room and player name
 */
void handle_reconnect_request(Server *server, Client *temp_client, const char *data) {
    char room_name[MAX_ROOM_NAME];
    char player_name[MAX_PLAYER_NAME];

    // Parse reconnection data (room_name,player_name or just player_name)
    int parsed = sscanf(data, "%[^,],%s", room_name, player_name);

    if (parsed == 1) {
        // Only player name provided (lobby reconnect)
        strncpy(player_name, room_name, MAX_PLAYER_NAME - 1);
        player_name[MAX_PLAYER_NAME - 1] = '\0';
        room_name[0] = '\0';
    } else if (parsed != 2) {
        send_message(temp_client->socket, OP_RECONNECT_FAIL, "Invalid format");
        return;
    }

    // Clean up input strings
    player_name[strcspn(player_name, "\r\n")] = '\0';
    room_name[strcspn(room_name, "\r\n")] = '\0';

    printf("Reconnect request from '%s' (room: %s)\n",
           player_name, room_name[0] ? room_name : "lobby");

    pthread_mutex_lock(&server->clients_mutex);
    Client *old_client = find_client(server, player_name);

    if (!old_client) {
        pthread_mutex_unlock(&server->clients_mutex);
        send_message(temp_client->socket, OP_RECONNECT_FAIL, "Client not found");
        printf("Client '%s' not found\n", player_name);
        return;
    }

    pthread_mutex_lock(&old_client->state_mutex);

    ClientState old_state = old_client->state;

    // Validate client is in a reconnectable state
    if (old_state == CLIENT_STATE_REMOVED) {
        pthread_mutex_unlock(&old_client->state_mutex);
        pthread_mutex_unlock(&server->clients_mutex);
        send_message(temp_client->socket, OP_RECONNECT_FAIL, "Client was removed");
        printf("Client was removed\n");
        return;
    }

    if (old_state != CLIENT_STATE_DISCONNECTED &&
        old_state != CLIENT_STATE_TIMEOUT) {
        pthread_mutex_unlock(&old_client->state_mutex);
        pthread_mutex_unlock(&server->clients_mutex);

        char msg[128];
        snprintf(msg, sizeof(msg), "Cannot reconnect from state: %s",
                 client_get_state_string(old_state));
        send_message(temp_client->socket, OP_RECONNECT_FAIL, msg);
        printf("Wrong state: %s\n", client_get_state_string(old_state));
        return;
    }

    printf("Found disconnected client '%s'\n", player_name);
    printf("   Old socket: %d (closed), New socket: %d\n",
           old_client->socket, temp_client->socket);

    // Mark as reconnecting to prevent removal by heartbeat thread
    old_client->state = CLIENT_STATE_RECONNECTING;
    old_client->disconnect_time = 0;

    // Close old socket if still open (safety measure)
    int old_socket = old_client->socket;
    if (old_socket > 0) {
        close(old_socket);
    }

    // Transfer new socket and thread to existing client structure
    old_client->socket = temp_client->socket;
    old_client->thread = temp_client->thread;
    old_client->active = true;
    old_client->logged_in = true;

    client_mark_reconnected(old_client);

    pthread_mutex_unlock(&old_client->state_mutex);

    // Invalidate temporary client structure
    temp_client->active = false;
    temp_client->logged_in = false;
    temp_client->client_id[0] = '\0';
    temp_client->socket = -1;

    printf("Socket %d transferred to '%s'\n",
           old_client->socket, player_name);

    pthread_mutex_unlock(&server->clients_mutex);

    // Restore client to their previous game state
    ClientGameState game_state = old_client->game_state;
    printf("Restoring state: %s\n",
           client_game_state_to_string(game_state));

    switch (game_state) {
        case CLIENT_GAME_STATE_IN_LOBBY:
            // Reconnect to lobby
            send_message(old_client->socket, OP_RECONNECT_OK, "lobby");
            send_message(old_client->socket, OP_LOGIN_OK, player_name);
            printf("%s reconnected to lobby\n", player_name);
            break;

        case CLIENT_GAME_STATE_IN_ROOM_WAITING:
            // Reconnect to waiting room
            if (room_name[0] == '\0') {
                send_message(old_client->socket, OP_RECONNECT_FAIL,
                            "Room name required");
                break;
            }

            pthread_mutex_lock(&server->rooms_mutex);
            Room *waiting_room = find_room(server, room_name);

            if (!waiting_room) {
                // Room was closed, return to lobby
                pthread_mutex_unlock(&server->rooms_mutex);
                old_client->current_room[0] = '\0';
                transition_client_state(old_client, CLIENT_GAME_STATE_IN_LOBBY);
                send_message(old_client->socket, OP_RECONNECT_FAIL,
                            "Room was closed");
                send_message(old_client->socket, OP_LOGIN_OK, player_name);
                printf("Room closed, returning to lobby\n");
                break;
            }

            send_message(old_client->socket, OP_RECONNECT_OK, room_name);
            char room_info[256];
            snprintf(room_info, sizeof(room_info), "%s,%d",
                    room_name, waiting_room->players_count);
            send_message(old_client->socket, OP_ROOM_JOINED, room_info);
            pthread_mutex_unlock(&server->rooms_mutex);

            printf("%s reconnected to waiting room %s\n",
                   player_name, room_name);
            break;

        case CLIENT_GAME_STATE_IN_GAME:
            // Reconnect to active game
            if (room_name[0] == '\0') {
                send_message(old_client->socket, OP_RECONNECT_FAIL,
                            "Room name required");
                break;
            }

            pthread_mutex_lock(&server->rooms_mutex);
            Room *game_room = find_room(server, room_name);

            if (!game_room) {
                // Game ended, return to lobby
                pthread_mutex_unlock(&server->rooms_mutex);
                old_client->current_room[0] = '\0';
                transition_client_state(old_client, CLIENT_GAME_STATE_IN_LOBBY);
                send_message(old_client->socket, OP_RECONNECT_FAIL, "Game ended");
                send_message(old_client->socket, OP_LOGIN_OK, player_name);
                printf("Game ended, returning to lobby\n");
                break;
            }

            // Verify player is a member of this game
            bool is_player1 = (strcmp(game_room->player1, player_name) == 0);
            bool is_player2 = (strcmp(game_room->player2, player_name) == 0);

            if (!is_player1 && !is_player2) {
                pthread_mutex_unlock(&server->rooms_mutex);
                send_message(old_client->socket, OP_RECONNECT_FAIL,
                            "Not a member");
                break;
            }

            if (game_room->state == ROOM_STATE_PAUSED) {
                // Resume paused game
                room_resume_game(game_room);
                send_message(old_client->socket, OP_RECONNECT_OK, room_name);
                send_message(old_client->socket, OP_GAME_RESUMED, room_name);
                char *board_json = game_board_to_json(&game_room->game);
                send_message(old_client->socket, OP_GAME_STATE, board_json);

                // Notify opponent about reconnection and resume
                char *other_player = is_player1 ? game_room->player2 :
                                                   game_room->player1;
                if (other_player[0] != '\0') {
                    Client *other = find_client(server, other_player);
                    if (other && other->state == CLIENT_STATE_CONNECTED) {
                        char msg[256];
                        snprintf(msg, sizeof(msg), "%s,%s",
                                room_name, player_name);
                        send_message(other->socket, OP_PLAYER_RECONNECTED, msg);
                        send_message(other->socket, OP_GAME_RESUMED, room_name);
                    }
                }
                printf("%s reconnected, game resumed\n", player_name);

            } else if (game_room->state == ROOM_STATE_ACTIVE) {
                // Reconnect to already active game
                send_message(old_client->socket, OP_RECONNECT_OK, room_name);
                char *board_json = game_board_to_json(&game_room->game);
                send_message(old_client->socket, OP_GAME_STATE, board_json);
                printf("%s reconnected to active game\n", player_name);

            } else {
                // Game not in valid state, return to lobby
                send_message(old_client->socket, OP_RECONNECT_FAIL,
                            "Game not active");
                old_client->current_room[0] = '\0';
                transition_client_state(old_client, CLIENT_GAME_STATE_IN_LOBBY);
                send_message(old_client->socket, OP_LOGIN_OK, player_name);
                printf("Game not active, returning to lobby\n");
            }

            pthread_mutex_unlock(&server->rooms_mutex);
            break;

        default:
            send_message(old_client->socket, OP_RECONNECT_FAIL, "Unknown state");
            break;
    }
}



/**
 * Checks if a client is eligible for reconnection.
 *
 * @param server Pointer to the server
 * @param player_name Name of the player attempting to reconnect
 * @return true if client can reconnect, false otherwise
 */
bool can_client_reconnect(Server *server, const char *player_name) {
    pthread_mutex_lock(&server->clients_mutex);

    Client *client = find_client(server, player_name);

    if (!client) {
        pthread_mutex_unlock(&server->clients_mutex);
        return false;
    }

    bool can_reconnect = (client->state == CLIENT_STATE_DISCONNECTED ||
                         client->state == CLIENT_STATE_TIMEOUT) &&
                         client->logged_in;

    pthread_mutex_unlock(&server->clients_mutex);

    return can_reconnect;
}


/**
 * Initializes the server with the specified port.
 * Creates and configures the server socket, sets socket options,
 * binds to the port, and begins listening for connections.
 *
 * @param server Pointer to the server structure to initialize
 * @param port Port number to bind to
 * @param bind_address Adress to bind to
 * @return 0 on success, -1 on failure
 */
int server_init(Server *server, int port, const char *bind_address) {
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
    server_addr.sin_port = htons(port);

    if (bind_address == NULL) {
        // Default: listen on all interfaces
        server_addr.sin_addr.s_addr = INADDR_ANY;
        printf("Initializing server on 0.0.0.0:%d (all interfaces)...\n", port);
    } else {
        // Try to parse the provided IP address
        if (inet_pton(AF_INET, bind_address, &server_addr.sin_addr) <= 0) {
            fprintf(stderr, "Invalid bind address: %s\n", bind_address);
            close(server->server_socket);
            return -1;
        }
        printf("Initializing server on %s:%d...\n", bind_address, port);
    }

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


/**
 * Sends a protocol message to a client.
 * Creates a formatted message using the protocol and sends it over the socket.
 *
 * @param socket Client socket to send to
 * @param op Operation code for the message
 * @param data Message payload data
 */
void send_message(int socket, OpCode op, const char *data) {
    char buffer[MAX_MESSAGE_LEN];
    int len = create_message(buffer, op, data);
    if (len > 0) {
        printf("Sending message: '%.*s'\n", len, buffer);
        send(socket, buffer, len, 0);
    }
}

/**
 * Adds a new client connection to the server.
 * Finds an available client slot, initializes the client structure,
 * and sets up heartbeat monitoring.
 *
 * @param server Pointer to the server
 * @param socket Socket for the new client connection
 * @return Index of the added client, or -1 if server is full
 */
int add_client(Server *server, int socket) {
    pthread_mutex_lock(&server->clients_mutex);

    if (server->client_count >= MAX_CLIENTS) {
        pthread_mutex_unlock(&server->clients_mutex);
        return -1;
    }

    // Find first available client slot
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

/**
 * Disconnects a client that has been flagged as malicious.
 * Removes client from their room, closes connection, and cleans up resources.
 *
 * @param server Pointer to the server
 * @param client Pointer to the malicious client
 * @param reason Reason code for the disconnection
 * @param raw_message The message that triggered the disconnection (unused)
 */
void disconnect_malicious_client(Server *server, Client *client,
                                DisconnectReason reason, const char *raw_message) {
    (void)raw_message;
    (void)reason;// Unused parameter

    printf("[DISCONNECT] Disconnecting malicious client: %s (socket %d)\n",
           client->client_id, client->socket);


    // Remove from room if present
    if (client->current_room[0] != '\0') {
        leave_room(server, client->current_room, client->client_id);
    }

    // Close connection
    close(client->socket);

    // Mark as inactive and removed
    pthread_mutex_lock(&client->state_mutex);
    client->active = false;
    client->state = CLIENT_STATE_REMOVED;
    pthread_mutex_unlock(&client->state_mutex);

    //  Remove client from client list
    pthread_mutex_lock(&server->clients_mutex);
    for (int i = 0; i < server->client_count; i++) {
        if (strcmp(server->clients[i].client_id, client->client_id) == 0) {
            // Зсуваємо елементи
            for (int j = i; j < server->client_count - 1; j++) {
                server->clients[j] = server->clients[j + 1];
            }
            server->client_count--;
            break;
        }
    }
    pthread_mutex_unlock(&server->clients_mutex);
}

/**
 * Finds a client by their ID.
 * Thread-safe operation - caller should hold clients_mutex if needed.
 *
 * @param server Pointer to the server
 * @param client_id ID of the client to find
 * @return Pointer to the client, or NULL if not found
 */
Client* find_client(Server *server, const char *client_id) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active &&
            strcmp(server->clients[i].client_id, client_id) == 0) {
            return &server->clients[i];
        }
    }
    return NULL;
}

/**
 * Creates a new game room.
 *
 * @param server Pointer to the server
 * @param room_name Name for the new room
 * @param creator Name of the player creating the room
 * @return Pointer to the created room, or NULL if room exists or server is full
 */
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

/**
 * Finds a room by name.
 * Thread-safe operation - caller should hold rooms_mutex if needed.
 *
 * @param server Pointer to the server
 * @param room_name Name of the room to find
 * @return Pointer to the room, or NULL if not found
 */
Room* find_room(Server *server, const char *room_name) {
    for (int i = 0; i < MAX_ROOMS; i++) {
        if ((server->rooms[i].players_count > 0 || strlen(server->rooms[i].owner) > 0)&&
            strcmp(server->rooms[i].name, room_name) == 0) {
            return &server->rooms[i];
        }
    }
    return NULL;
}

/**
 * Adds a player to a game room.
 * Validates room availability, player eligibility, and starts the game when both players join.
 * Uses two-phase locking to prevent race conditions.
 *
 * @param server Pointer to the server
 * @param room_name Name of the room to join
 * @param player_name Name of the player joining
 * @return 0 on success, negative error codes on failure:
 *         -1: Room not found
 *         -2: Room is full
 *         -3: Player already in this room
 *         -4: Player already in another room
 *         -5: Client not found
 */
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

    // Check if player already in this room
    if (strcmp(room->player1, player_name) == 0 ||
        strcmp(room->player2, player_name) == 0) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return -3;
    }

    pthread_mutex_unlock(&server->rooms_mutex);
    // Verify client exists and is not in another room
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
    // Re-acquire room lock and add player
    pthread_mutex_lock(&server->rooms_mutex);


    room = find_room(server, room_name);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return -1;
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
    }

    // Initialize game when both players have joined
    if (room->players_count == 2 && !room->game_started) {
        init_game(&room->game, room->player1, room->player2);
        room->game_started = true;
        room->state = ROOM_STATE_ACTIVE;

        printf("Game initialized in room %s: %s vs %s\n",
               room_name, room->player1, room->player2);
    }

    pthread_mutex_unlock(&server->rooms_mutex);
    return 0;
}

/**
 * Handles player disconnection from a room (preserves room for reconnection).
 * Called when a player disconnects unexpectedly rather than explicitly leaving.
 * Room and game state are preserved to allow reconnection.
 *
 * @param server Pointer to the server
 * @param room_name Name of the room
 * @param player_name Name of the disconnected player
 */
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

/**
 * Removes a player from a room (explicit leave).
 * Cleans up the room completely and notifies remaining players.
 * If the last player leaves or if one player explicitly leaves, the room is destroyed.
 *
 * @param server Pointer to the server
 * @param room_name Name of the room to leave
 * @param player_name Name of the player leaving
 */
void leave_room(Server *server, const char *room_name, const char *player_name) {
    pthread_mutex_lock(&server->rooms_mutex);

    Room *room = find_room(server, room_name);
    if (!room) {
        pthread_mutex_unlock(&server->rooms_mutex);
        return;
    }

    printf("Player %s explicitly left room %s\n", player_name, room_name);

    room->players_count--;

    if (room->players_count == 0) {
        // Last player left - destroy room
        pthread_mutex_destroy(&room->room_mutex);
        memset(room, 0, sizeof(Room));
        server->room_count--;
        printf("Room %s removed (no players left)\n", room_name);
    } else {
        // Find and notify the remaining player
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
            transition_client_state(other, CLIENT_GAME_STATE_IN_LOBBY);
        }
        // Destroy room after notifying
        pthread_mutex_destroy(&room->room_mutex);
        memset(room, 0, sizeof(Room));
        server->room_count--;
        printf("Room %s removed (player left)\n", room_name);
    }

    pthread_mutex_unlock(&server->rooms_mutex);
}

/**
 * Broadcasts a message to all players in a room.
 *
 * @param server Pointer to the server
 * @param room_name Name of the room to broadcast to
 * @param op Operation code for the message
 * @param data Message payload data
 */
void broadcast_to_room(Server *server, const char *room_name, OpCode op, const char *data) {
    Room *room = find_room(server, room_name);
    if (!room) return;

    Client *p1 = find_client(server, room->player1);
    Client *p2 = (room->player2[0] != '\0') ? find_client(server, room->player2) : NULL;

    if (p1) send_message(p1->socket, op, data);
    if (p2) send_message(p2->socket, op, data);
}

/**
 * Handles client login request.
 * Validates username, checks for duplicates, and initializes client state.
 *
 * Protocol format: "player_name"
 *
 * @param server Pointer to the server
 * @param client Pointer to the client attempting to login
 * @param data Login data containing player name
 */
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



/**
 * Handles room creation request.
 * Creates a new game room if name is available.
 *
 * Protocol format: "player_name,room_name"
 *
 * @param server Pointer to the server
 * @param client Pointer to the client creating the room
 * @param data Room creation data
 */
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

    send_message(client->socket, OP_ROOM_CREATED, room_name);
    printf("Room created: %s by %s. Players count=%d\n", room_name, player_name, room->players_count);
    log_client(client);
}

/**
 * Handles player request to join a room.
 * Validates room availability and player eligibility, then adds player to room.
 * Starts game automatically when second player joins.
 *
 * Protocol format: "player_name,room_name"
 *
 * @param server Pointer to the server
 * @param client Pointer to the client joining
 * @param data Join request data
 */
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

    // Handle various error conditions
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

    // Update client's current room
    strncpy(client->current_room, room_name, MAX_ROOM_NAME - 1);
    client->current_room[MAX_ROOM_NAME - 1] = '\0';

    Room *room = find_room(server, room_name);
    if (!room) {
        send_message(client->socket, OP_ROOM_FAIL, "Room disappeared");
        return;
    }

    // Notify client of successful join
    char response[256];
    snprintf(response, sizeof(response), "%s,%d", room_name, room->players_count);
    send_message(client->socket, OP_ROOM_JOINED, response);

    // Start game only if 2 players joined
    if (room->game_started) {
        pthread_mutex_lock(&server->clients_mutex);
        Client *client1 = find_client(server, room->player1);
        Client *client2 = find_client(server, room->player2);
        pthread_mutex_unlock(&server->clients_mutex);
        transition_client_state(client1, CLIENT_GAME_STATE_IN_GAME);
        transition_client_state(client2, CLIENT_GAME_STATE_IN_GAME);
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


/**
 * Handles a single move in the checkers game.
 * Validates the move, applies it to the game state, and checks for game over.
 *
 * Protocol format: "room_name,player_name,from_row,from_col,to_row,to_col"
 *
 * @param server Pointer to the server
 * @param client Pointer to the client making the move
 * @param data Move data
 */
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
    // Validate move according to game rules
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
        printf("Game over! Winner: %s\n", winner);
    }
}

/**
 * Handles a multi-jump move sequence in checkers.
 * Validates and applies a chain of jumps as a single move.
 * Used when a player must make multiple consecutive captures.
 *
 * Protocol format: "room_name,player_name,path_length,r1,c1,r2,c2,r3,c3,..."
 *
 * @param server Pointer to the server
 * @param client Pointer to the client making the multi-move
 * @param data Multi-move data containing the complete path
 */
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

    // Parse first three fields
    int parsed = sscanf(data, "%[^,],%[^,],%d", room_name, player_name, &path_length);

    if (parsed != 3 || path_length < 2 || path_length > 20) {
        send_message(client->socket, OP_INVALID_MOVE, "Invalid multi-move format");
        printf("Parse error: parsed=%d, path_length=%d\n", parsed, path_length);
        return;
    }

    printf("Room: %s, Player: %s, Path length: %d\n", room_name, player_name, path_length);

    Room *room = find_room(server, room_name);
    if (!room || !room->game_started) {
        send_message(client->socket, OP_ERROR, "Game not found");
        return;
    }

    int path[20][2];  // Maximum 20 positions
    const char *ptr = data;

    // Skip first three fields
    for (int i = 0; i < 3; i++) {
        ptr = strchr(ptr, ',');
        if (!ptr) {
            send_message(client->socket, OP_INVALID_MOVE, "Invalid path data");
            return;
        }
        ptr++; // // Skip comma
    }

    // // Parse coordinate pairs
    for (int i = 0; i < path_length; i++) {
        if (sscanf(ptr, "%d,%d", &path[i][0], &path[i][1]) != 2) {
            send_message(client->socket, OP_INVALID_MOVE, "Invalid coordinates");
            printf("Failed to parse position %d\n", i);
            return;
        }

        printf("Position %d: (%d, %d)\n", i, path[i][0], path[i][1]);
        ptr = strchr(ptr, ',');
        if (ptr) ptr++;
        ptr = strchr(ptr, ',');
        if (ptr && i < path_length - 1) ptr++;
    }

    // // Validate and apply the chain of moves
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
            printf("Step %d failed validation\n", i + 1);
            return;
        }

        // Apply move
        apply_move(&room->game, from_row, from_col, to_row, to_col);
        printf("Step %d applied\n", i + 1);
    }

    printf("=== MULTI-MOVE CHAIN COMPLETED ===\n\n");

    // Change turn
    change_turn(&room->game);

    // Send updated board
    char *board_json = game_board_to_json(&room->game);
    broadcast_to_room(server, room_name, OP_GAME_STATE, board_json);

    // Check for game over
    char winner[MAX_PLAYER_NAME];
    if (check_game_over(&room->game, winner)) {
        char end_msg[256];
        snprintf(end_msg, sizeof(end_msg), "%s,no_pieces", winner);
        broadcast_to_room(server, room_name, OP_GAME_END, end_msg);
        cleanup_finished_game(server, room);
        printf("Game over! Winner: %s\n", winner);
    }
}

/**
 * Cleans up a finished game.
 * Removes all players from the room, transitions them to lobby,
 * and destroys the room structure.
 *
 * @param server Pointer to the server
 * @param room Pointer to the room to clean up
 */
void cleanup_finished_game(Server *server, Room *room) {
    printf("Cleaning up finished game in room: %s\n", room->name);

    pthread_mutex_lock(&server->clients_mutex);
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (server->clients[i].active &&
            strcmp(server->clients[i].current_room, room->name) == 0) {

            Client *client = &server->clients[i];
            printf("Removing player %s from room\n", client->client_id);
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

    printf("Room %s cleaned up\n", room->name);
}


/**
 * Handles player request to leave a room.
 *
 * Protocol format: "room_name,player_name"
 *
 * @param server Pointer to the server
 * @param client Pointer to the client leaving
 * @param data Leave request data
 */
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

/**
 * Handles PING message from client.
 * Responds with PONG to maintain connection.
 *
 * @param server Pointer to the server
 * @param client Pointer to the client that sent PING
 */
void handle_ping(Server *server, Client *client) {
    (void)server;
    send_message(client->socket, OP_PONG, "");
    printf("PONG TO SOCKET %d\n", client->socket);
}

/**
 * Handles request for list of available rooms.
 * Returns JSON array of room information.
 *
 * Response format: [{"id":1,"name":"Room1","players":1},...]
 *
 * @param server Pointer to the server
 * @param client Pointer to the requesting client
 */
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

/**
 * Main client handler thread.
 * Processes incoming messages from a client connection.
 * Implements TCP stream parsing with message buffering to handle partial receives.
 * Handles client disconnection detection and reconnection logic.
 *
 * Message format: Messages are delimited by newline characters (\n)
 *
 * @param arg Pointer to ClientThreadArgs structure
 * @return NULL when thread exits
 */
void* client_handler(void *arg) {
    ClientThreadArgs *args = (ClientThreadArgs*)arg;
    Server *server = args->server;
    int my_socket = args->client_socket;

    printf("Thread started for socket %d\n", my_socket);
    free(args);

    char recv_buffer[BUFFER_SIZE];
    char message_buffer[BUFFER_SIZE * 2];
    int message_pos = 0;

    while (server->running) {
        pthread_mutex_lock(&server->clients_mutex);
        // Find client structure for this socket
        Client *client = NULL;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (server->clients[i].active &&
                server->clients[i].socket == my_socket) {
                client = &server->clients[i];
                break;
            }
        }
        pthread_mutex_unlock(&server->clients_mutex);

        if (!client) {
            printf("No client for socket %d, closing\n", my_socket);
            close(my_socket);
            return NULL;
        }

        pthread_mutex_lock(&client->state_mutex);
        // Check client state
        bool is_active = client->active;
        ClientState state = client->state;
        pthread_mutex_unlock(&client->state_mutex);

        if (!is_active) {
            printf("Client inactive for socket %d\n", my_socket);

            // Check if socket was transferred to another client
            pthread_mutex_lock(&server->clients_mutex);
            Client *check = NULL;
            for (int i = 0; i < MAX_CLIENTS; i++) {
                if (server->clients[i].socket == my_socket) {
                    check = &server->clients[i];
                    break;
                }
            }
            pthread_mutex_unlock(&server->clients_mutex);

            if (!check) {
                printf("Socket %d transferred, exiting\n", my_socket);
                return NULL; // Don't close socket (transferred to another thread)
            }

            close(my_socket);
            return NULL;
        }

        if (state == CLIENT_STATE_REMOVED) {
            printf("Client removed, closing socket %d\n", my_socket);
            close(my_socket);
            return NULL;
        }

        // ========== READ DATA ==========
        memset(recv_buffer, 0, BUFFER_SIZE);
        int bytes = recv(my_socket, recv_buffer, BUFFER_SIZE - 1, 0);

        if (bytes <= 0) {
            // Connection closed or error
            printf("📡 Connection closed on socket %d (bytes=%d)\n",
                   my_socket, bytes);

            pthread_mutex_lock(&server->clients_mutex);
            Client *disconnect_client = NULL;
            for (int i = 0; i < MAX_CLIENTS; i++) {
                if (server->clients[i].socket == my_socket &&
                    server->clients[i].active) {
                    disconnect_client = &server->clients[i];
                    break;
                }
            }
            pthread_mutex_unlock(&server->clients_mutex);

            if (!disconnect_client) {
                printf("Socket was transferred during recv\n");
                return NULL;
            }

            // Handle disconnection
            handle_client_disconnect(server, disconnect_client, my_socket);
            return NULL;
        }

        // ========== PROCESS MESSAGES ==========
        // TCP stream may contain partial messages or multiple messages
        // Buffer until we find complete messages (delimited by \n)
        for (int i = 0; i < bytes; i++) {
            char current_char = recv_buffer[i];

            // Prevent buffer overflow
            if ((size_t)message_pos >= sizeof(message_buffer) - 1) {
                fprintf(stderr, "SECURITY: Buffer overflow from socket %d\n",
                        my_socket);

                pthread_mutex_lock(&server->clients_mutex);
                Client *overflow_client = NULL;
                for (int j = 0; j < MAX_CLIENTS; j++) {
                    if (server->clients[j].socket == my_socket) {
                        overflow_client = &server->clients[j];
                        break;
                    }
                }
                pthread_mutex_unlock(&server->clients_mutex);

                if (overflow_client) {
                    disconnect_malicious_client(server, overflow_client,
                                               DISCONNECT_REASON_BUFFER_OVERFLOW,
                                               message_buffer);
                }
                return NULL;
            }

            message_buffer[message_pos++] = current_char;

            // Complete message received (newline delimiter)
            if (current_char == '\n') {
                message_buffer[message_pos - 1] = '\0';
                // Find client again (may have changed after reconnect)
                pthread_mutex_lock(&server->clients_mutex);
                Client *msg_client = NULL;
                for (int j = 0; j < MAX_CLIENTS; j++) {
                    if (server->clients[j].socket == my_socket &&
                        server->clients[j].active) {
                        msg_client = &server->clients[j];
                        break;
                    }
                }
                pthread_mutex_unlock(&server->clients_mutex);

                if (!msg_client) {
                    printf("Client disappeared during message processing\n");
                    message_pos = 0;
                    memset(message_buffer, 0, sizeof(message_buffer));
                    continue;
                }

                Message msg;
                DisconnectReason disconnect_reason;
                // Parse message according to protocol
                int parse_result = parse_message(message_buffer, &msg,
                                                 &disconnect_reason);

                if (parse_result == 0) {
                    log_message("RECV", &msg);
                    // Validate operation is allowed in current state
                    if (!validate_operation(server, msg_client, msg.op)) {
                        message_pos = 0;
                        memset(message_buffer, 0, sizeof(message_buffer));
                        continue;
                    }
                    // Dispatch to appropriate handler
                    switch (msg.op) {
                        case OP_LOGIN:
                            handle_login(server, msg_client, msg.data);
                            break;
                        case OP_CREATE_ROOM:
                            handle_create_room(server, msg_client, msg.data);
                            break;
                        case OP_JOIN_ROOM:
                            handle_join_room(server, msg_client, msg.data);
                            break;
                        case OP_MOVE:
                            handle_move(server, msg_client, msg.data);
                            break;
                        case OP_MULTI_MOVE:
                            handle_multi_move(server, msg_client, msg.data);
                            break;
                        case OP_LEAVE_ROOM:
                            handle_leave_room(server, msg_client, msg.data);
                            break;
                        case OP_PING:
                            handle_ping(server, msg_client);
                            break;
                        case OP_PONG:
                            client_update_pong(msg_client);
                            break;
                        case OP_LIST_ROOMS:
                            handle_list_rooms(server, msg_client);
                            break;
                        case OP_RECONNECT_REQUEST:
                            handle_reconnect_request(server, msg_client, msg.data);
                            break;
                        default:
                            fprintf(stderr, "Unknown OpCode %d\n", msg.op);
                            send_message(my_socket, OP_ERROR, "Unknown operation");
                            break;
                    }
                } else {
                    // Message parsing failed
                    fprintf(stderr, "Failed to parse message from socket %d\n",
                            my_socket);

                    if (should_disconnect_client(&msg_client->violations)) {
                        disconnect_malicious_client(server, msg_client,
                                                   disconnect_reason,
                                                   message_buffer);
                        return NULL;
                    }
                }
                // Reset buffer for next message
                message_pos = 0;
                memset(message_buffer, 0, sizeof(message_buffer));
            }
        }
    }

    return NULL;
}

/**
 * Handles client disconnection detected by the client handler thread.
 * Differentiates between anonymous and logged-in clients:
 * - Anonymous clients are removed immediately
 * - Logged-in clients are marked as disconnected and preserved for reconnection
 *
 * @param server Pointer to the server
 * @param client Pointer to the disconnected client
 * @param socket Socket that was disconnected
 */
void handle_client_disconnect(Server *server, Client *client, int socket) {
    printf("handle_client_disconnect for %s (socket %d)\n",
           client->client_id[0] ? client->client_id : "anonymous", socket);

    pthread_mutex_lock(&client->state_mutex);

    if (!client->logged_in || client->client_id[0] == '\0') {
        pthread_mutex_unlock(&client->state_mutex);
        printf("Anonymous client, removing immediately\n");
        close(socket);
        pthread_mutex_lock(&server->clients_mutex);
        client->active = false;
        server->client_count--;
        pthread_mutex_unlock(&server->clients_mutex);
        return;
    }

    printf("Logged-in client '%s', preserving for reconnect\n",
           client->client_id);

    client->state = CLIENT_STATE_DISCONNECTED;
    client->disconnect_time = time(NULL);
    client->missed_pongs = 0;

    close(socket);
    client->socket = -1;

    pthread_mutex_unlock(&client->state_mutex);

    printf("Client '%s' marked as DISCONNECTED (preserved for %ld sec)\n",
           client->client_id, LONG_DISCONNECT_THRESHOLD_SEC);
}

/**
 * Validates if an operation is allowed in the client's current game state.
 * Implements state machine validation to prevent protocol violations.
 * Tracks violations and disconnects clients that repeatedly attempt invalid operations.
 *
 * @param server Pointer to the server
 * @param client Pointer to the client attempting the operation
 * @param op Operation code to validate
 * @return true if operation is allowed, false otherwise
 */
bool validate_operation(Server *server, Client *client, OpCode op) {
    if (!is_operation_allowed(client->game_state, op)) {
        log_invalid_operation_attempt(client, op);

        client->violations.unknown_opcode_count++;

        if (client->violations.unknown_opcode_count >= MAX_VIOLATIONS) {
            fprintf(stderr, "Client exceeded invalid operation attempts (1/1)\n");

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

/**
 * Starts the server and begins accepting client connections.
 * Spawns the heartbeat monitoring thread and enters the main accept loop.
 * For each new connection, creates a client structure and spawns a handler thread.
 *
 * @param server Pointer to the server to start
 */
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

/**
 * Stops the server and cleans up all resources.
 * Cancels the heartbeat thread, closes all client connections,
 * destroys all mutexes, and closes the server socket.
 *
 * @param server Pointer to the server to stop
 */
void server_stop(Server *server) {
    server->running = false;

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

/**
 * Logs client information for debugging purposes.
 *
 * @param client Pointer to the client to log
 */
void log_client(const Client *client) {
    printf("[%d] CLIENT_ID=%s THREAD=%d ACTIVE=%d LOGGED=%d ROOM=%s \n", client->socket, client->client_id, client->thread, client->active, client->logged_in, client->current_room);
}

/**
 * Logs an invalid operation attempt for security monitoring.
 * Prints detailed information about the violation including:
 * - Client identity
 * - Current state
 * - Attempted operation
 * - List of allowed operations in current state
 *
 * @param client Pointer to the client that attempted the invalid operation
 * @param attempted_op Operation code that was attempted
 */
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
