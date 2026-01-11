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

// Piece types
typedef enum {
    EMPTY = 0,
    WHITE_PIECE = 1,
    WHITE_KING = 2,
    BLACK_PIECE = 3,
    BLACK_KING = 4
} PieceType;

typedef enum {
    ROOM_STATE_WAITING,
    ROOM_STATE_ACTIVE,
    ROOM_STATE_PAUSED,
    ROOM_STATE_FINISHED
} RoomState;

// Player color
typedef enum {
    COLOR_WHITE = 1,
    COLOR_BLACK = 3
} PlayerColor;

// Game state
typedef struct {
    int board[BOARD_SIZE][BOARD_SIZE];
    char player1[MAX_PLAYER_NAME];
    char player2[MAX_PLAYER_NAME];
    char current_turn[MAX_PLAYER_NAME];
    PlayerColor player1_color;
    PlayerColor player2_color;
    bool game_active;
} Game;

// Room structure
typedef struct {
    char name[MAX_ROOM_NAME];
    char owner[MAX_ROOM_NAME];
    char player1[MAX_PLAYER_NAME];
    char player2[MAX_PLAYER_NAME];
    int players_count;
    Game game;
    bool game_started;

    RoomState state;
    time_t pause_start_time;
    char disconnected_player[MAX_PLAYER_NAME];
    bool waiting_for_reconnect;
    pthread_mutex_t room_mutex;
} Room;

// Function prototypes
void init_game(Game *game, const char *player1, const char *player2);
void reset_game(Game *game);
char* game_board_to_json(const Game *game);
bool validate_move(const Game *game, int from_row, int from_col, int to_row, int to_col, const char *player);
void apply_move(Game *game, int from_row, int from_col, int to_row, int to_col);
void change_turn(Game *game);
bool check_game_over(const Game *game, char *winner);
void rotate_board(const Game *game, int rotated[BOARD_SIZE][BOARD_SIZE]);
void print_board(const Game *game);

#endif //SERVER_GAME_H