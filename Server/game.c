#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "game.h"

void init_game(Game *game, const char *player1, const char *player2) {
    // Initialize board (like in Java example)
    int initial_board[BOARD_SIZE][BOARD_SIZE] = {
        {3, 0, 3, 0, 3, 0, 3, 0},
        {0, 3, 0, 3, 0, 3, 0, 3},
        {3, 0, 3, 0, 3, 0, 3, 0},
        {0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0},
        {0, 1, 0, 1, 0, 1, 0, 1},
        {1, 0, 1, 0, 1, 0, 1, 0},
        {0, 1, 0, 1, 0, 1, 0, 1}
    };
    
    memcpy(game->board, initial_board, sizeof(initial_board));
    strncpy(game->player1, player1, MAX_PLAYER_NAME - 1);
    strncpy(game->player2, player2, MAX_PLAYER_NAME - 1);
    strncpy(game->current_turn, player1, MAX_PLAYER_NAME - 1);
    game->player1_color = COLOR_WHITE;
    game->player2_color = COLOR_BLACK;
    game->game_active = true;
}

void reset_game(Game *game) {
    init_game(game, game->player1, game->player2);
}

char* game_board_to_json(const Game *game) {
    static char json[4096];
    char *ptr = json;
    
    ptr += sprintf(ptr, "{\"board\":[");
    
    for (int i = 0; i < BOARD_SIZE; i++) {
        ptr += sprintf(ptr, "[");
        for (int j = 0; j < BOARD_SIZE; j++) {
            ptr += sprintf(ptr, "%d", game->board[i][j]);
            if (j < BOARD_SIZE - 1) ptr += sprintf(ptr, ",");
        }
        ptr += sprintf(ptr, "]");
        if (i < BOARD_SIZE - 1) ptr += sprintf(ptr, ",");
    }
    
    ptr += sprintf(ptr, "],\"current_turn\":\"%s\",\"player1\":\"%s\",\"player2\":\"%s\"}",
                  game->current_turn, game->player1, game->player2);
    
    return json;
}

void rotate_board(const Game *game, int rotated[BOARD_SIZE][BOARD_SIZE]) {
    // Rotate 180 degrees and swap colors (like in Java getRotatedBoard)
    for (int i = 0; i < BOARD_SIZE; i++) {
        for (int j = 0; j < BOARD_SIZE; j++) {
            int piece = game->board[BOARD_SIZE - 1 - i][BOARD_SIZE - 1 - j];
            // Swap white and black pieces
            if (piece == WHITE_PIECE) rotated[i][j] = BLACK_PIECE;
            else if (piece == BLACK_PIECE) rotated[i][j] = WHITE_PIECE;
            else if (piece == WHITE_KING) rotated[i][j] = BLACK_KING;
            else if (piece == BLACK_KING) rotated[i][j] = WHITE_KING;
            else rotated[i][j] = EMPTY;
        }
    }
}

bool validate_move(const Game *game, int from_row, int from_col, int to_row, int to_col, const char *player) {
    // Check if it's player's turn
    if (strcmp(game->current_turn, player) != 0) {
        return false;
    }
    
    // Check bounds
    if (from_row < 0 || from_row >= BOARD_SIZE || from_col < 0 || from_col >= BOARD_SIZE ||
        to_row < 0 || to_row >= BOARD_SIZE || to_col < 0 || to_col >= BOARD_SIZE) {
        return false;
    }
    
    // Check if destination is empty
    if (game->board[to_row][to_col] != EMPTY) {
        return false;
    }
    
    // Get piece
    int piece = game->board[from_row][from_col];
    if (piece == EMPTY) {
        return false;
    }
    
    // Determine player's color
    PlayerColor player_color = (strcmp(player, game->player1) == 0) ? 
                               game->player1_color : game->player2_color;
    
    // Check if piece belongs to player
    if ((player_color == COLOR_WHITE && piece != WHITE_PIECE && piece != WHITE_KING) ||
        (player_color == COLOR_BLACK && piece != BLACK_PIECE && piece != BLACK_KING)) {
        return false;
    }
    
    // Basic move validation (simplified - full rules would be more complex)
    int row_diff = to_row - from_row;
    int col_diff = abs(to_col - from_col);
    
    // Regular pieces move diagonally forward
    if (piece == WHITE_PIECE) {
        if (row_diff == -1 && col_diff == 1) return true; // Normal move
        if (row_diff == -2 && col_diff == 2) return true; // Jump
    } else if (piece == BLACK_PIECE) {
        if (row_diff == 1 && col_diff == 1) return true; // Normal move
        if (row_diff == 2 && col_diff == 2) return true; // Jump
    } else { // Kings can move both directions
        if (abs(row_diff) == 1 && col_diff == 1) return true;
        if (abs(row_diff) == 2 && col_diff == 2) return true;
    }
    
    return false;
}

void apply_move(Game *game, int from_row, int from_col, int to_row, int to_col) {
    int piece = game->board[from_row][from_col];
    game->board[to_row][to_col] = piece;
    game->board[from_row][from_col] = EMPTY;
    
    // Check for jump (capture)
    if (abs(to_row - from_row) == 2) {
        int mid_row = (from_row + to_row) / 2;
        int mid_col = (from_col + to_col) / 2;
        game->board[mid_row][mid_col] = EMPTY;
    }
    
    // Check for king promotion
    if (piece == WHITE_PIECE && to_row == 0) {
        game->board[to_row][to_col] = WHITE_KING;
    } else if (piece == BLACK_PIECE && to_row == BOARD_SIZE - 1) {
        game->board[to_row][to_col] = BLACK_KING;
    }
}

void change_turn(Game *game) {
    if (strcmp(game->current_turn, game->player1) == 0) {
        strncpy(game->current_turn, game->player2, MAX_PLAYER_NAME - 1);
    } else {
        strncpy(game->current_turn, game->player1, MAX_PLAYER_NAME - 1);
    }
}

bool check_game_over(const Game *game, char *winner) {
    int white_pieces = 0, black_pieces = 0;
    
    for (int i = 0; i < BOARD_SIZE; i++) {
        for (int j = 0; j < BOARD_SIZE; j++) {
            int piece = game->board[i][j];
            if (piece == WHITE_PIECE || piece == WHITE_KING) white_pieces++;
            if (piece == BLACK_PIECE || piece == BLACK_KING) black_pieces++;
        }
    }
    
    if (white_pieces == 0) {
        strcpy(winner, game->player2);
        return true;
    }
    if (black_pieces == 0) {
        strcpy(winner, game->player1);
        return true;
    }
    
    return false;
}