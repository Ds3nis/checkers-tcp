//
// Created by denkhuda on 11/17/25.
//

#ifndef SERVER_GAME_H
#define SERVER_GAME_H

#include <stdbool.h>
#include <pthread.h>
#include <time.h>

#define BOARD_SIZE 8
#define MAX_ROOM_NAME 64
#define MAX_PLAYER_NAME 64

/**
 * Checkers piece types.
 */
typedef enum {
    EMPTY = 0,
    WHITE_PIECE = 1,
    WHITE_KING = 2,
    BLACK_PIECE = 3,
    BLACK_KING = 4
} PieceType;

/**
 * Room states for game lifecycle management.
 */
typedef enum {
    ROOM_STATE_WAITING,   // Waiting for second player
   ROOM_STATE_ACTIVE,    // Game in progress
   ROOM_STATE_PAUSED,    // Game paused due to disconnection
   ROOM_STATE_FINISHED   // Game completed
} RoomState;

/**
 * Player colors (matches piece color values).
 */
typedef enum {
    COLOR_WHITE = 1,
    COLOR_BLACK = 3
} PlayerColor;

/**
 * Game state structure.
 * Contains the board and all game metadata.
 */
typedef struct {
    int board[BOARD_SIZE][BOARD_SIZE];  // 8x8 board grid
    char player1[MAX_PLAYER_NAME];      // Player 1 name
    char player2[MAX_PLAYER_NAME];      // Player 2 name
    char current_turn[MAX_PLAYER_NAME]; // Who's turn it is
    PlayerColor player1_color;          // Player 1's piece color
    PlayerColor player2_color;          // Player 2's piece color
    bool game_active;                   // Game is ongoing
} Game;

/**
 * Room structure.
 * Represents a game room that can hold up to 2 players.
 */
typedef struct {
    char name[MAX_ROOM_NAME];           // Room name
    char owner[MAX_ROOM_NAME];          // Room creator
    char player1[MAX_PLAYER_NAME];      // First player
    char player2[MAX_PLAYER_NAME];      // Second player
    int players_count;                  // Current player count (0-2)
    Game game;                          // Game state
    bool game_started;                  // Game has begun

    // Disconnect handling
    RoomState state;                    // Current room state
    time_t pause_start_time;           // When game was paused
    char disconnected_player[MAX_PLAYER_NAME]; // Who disconnected
    bool waiting_for_reconnect;        // Waiting for player return
    pthread_mutex_t room_mutex;        // Thread-safe room access
} Room;

// ========== GAME FUNCTIONS ==========

/**
 * Initializes new game with starting board.
 */
void init_game(Game *game, const char *player1, const char *player2);

/**
 * Resets game to initial state.
 */
void reset_game(Game *game);

/**
 * Converts board to JSON format for transmission.
 */
char* game_board_to_json(const Game *game);

/**
 * Validates move according to checkers rules.
 */
bool validate_move(const Game *game, int from_row, int from_col, int to_row, int to_col, const char *player);

/**
 * Applies validated move to board.
 */
void apply_move(Game *game, int from_row, int from_col, int to_row, int to_col);

/**
 * Switches to other player's turn.
 */
void change_turn(Game *game);

/**
 * Checks if game is over (no pieces remaining).
 */
bool check_game_over(const Game *game, char *winner);

/**
 * Rotates board 180 degrees (for perspective conversion).
 */
void rotate_board(const Game *game, int rotated[BOARD_SIZE][BOARD_SIZE]);

/**
 * Prints board to console for debugging.
 */
void print_board(const Game *game);

#endif //SERVER_GAME_H