#include "game.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

/**
 * Initializes a new checkers game with starting board configuration.
 * Sets up the standard 8x8 checkers board with pieces in starting positions:
 * - White pieces (1) on rows 5-7
 * - Black pieces (3) on rows 0-2
 *
 * @param game Pointer to game structure to initialize
 * @param player1 Name of player 1 (white pieces)
 * @param player2 Name of player 2 (black pieces)
 */
void init_game(Game *game, const char *player1, const char *player2) {
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

/**
 * Resets game to initial state with same players.
 *
 * @param game Pointer to game to reset
 */
void reset_game(Game *game) {
    init_game(game, game->player1, game->player2);
}

/**
 * Converts game board state to JSON format for client transmission.
 * Returns a static buffer containing the JSON representation.
 *
 * Format: {"board":[[...]],"current_turn":"name","player1":"name","player2":"name"}
 *
 * @param game Pointer to game state
 * @return Pointer to static JSON string buffer
 */
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

/**
 * Rotates board 180 degrees and swaps piece colors.
 * Used for perspective conversion in networked games.
 *
 * @param game Source game state
 * @param rotated Output rotated board
 */
void rotate_board(const Game *game, int rotated[BOARD_SIZE][BOARD_SIZE]) {
    // Rotate 180 degrees and swap colors
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

/**
 * Checks if a piece is a king.
 *
 * @param piece Piece type to check
 * @return true if piece is a king
 */
bool is_king(int piece) {
    return piece == WHITE_KING || piece == BLACK_KING;
}

/**
 * Checks if a piece belongs to the specified color.
 *
 * @param piece Piece type to check
 * @param color Player color
 * @return true if piece belongs to the color
 */
bool piece_belongs_to_color(int piece, PlayerColor color) {
    if (color == COLOR_WHITE) {
        return piece == WHITE_PIECE || piece == WHITE_KING;
    } else {
        return piece == BLACK_PIECE || piece == BLACK_KING;
    }
}


/**
 * Validates a single move step according to checkers rules.
 *
 * Rules enforced:
 * - Regular pieces: Move 1 square diagonally forward, or jump 2 squares to capture
 * - Kings: Move any distance diagonally, can capture with one enemy in path
 * - Captures allowed in both directions (forward and backward)
 * - Destination must be empty
 * - Piece must belong to current player
 *
 * @param game Current game state
 * @param from_row Source row
 * @param from_col Source column
 * @param to_row Destination row
 * @param to_col Destination column
 * @param player Player making the move
 * @return true if move is valid
 */
bool validate_single_step(const Game *game, int from_row, int from_col,
                         int to_row, int to_col, const char *player) {
    printf(" Validating step: (%d,%d) -> (%d,%d)\n", from_row, from_col, to_row, to_col);

    // Bounds check
    if (from_row < 0 || from_row >= BOARD_SIZE || from_col < 0 || from_col >= BOARD_SIZE ||
        to_row < 0 || to_row >= BOARD_SIZE || to_col < 0 || to_col >= BOARD_SIZE) {
        printf("Out of bounds\n");
        return false;
    }

    // Destination must be empty
    if (game->board[to_row][to_col] != EMPTY) {
        printf("Destination not empty\n");
        return false;
    }

    // Source must have a piece
    int piece = game->board[from_row][from_col];
    if (piece == EMPTY) {
        printf("Source empty\n");
        return false;
    }

    // Determine player's color
    PlayerColor player_color = (strcmp(player, game->player1) == 0) ?
                               game->player1_color : game->player2_color;

    // Piece must belong to player
    if (!piece_belongs_to_color(piece, player_color)) {
        printf("Wrong color (piece: %d, player: %s)\n", piece, player);
        return false;
    }

    int row_diff = to_row - from_row;
    int col_diff = abs(to_col - from_col);
    int abs_row_diff = abs(row_diff);

    // Must move diagonally
    if (abs_row_diff != col_diff) {
        printf("Not diagonal (row_diff: %d, col_diff: %d)\n", abs_row_diff, col_diff);
        return false;
    }

    // ========== KING PIECE ==========
    if (is_king(piece)) {
        int dRow = (row_diff > 0) ? 1 : -1;
        int dCol = (to_col > from_col) ? 1 : -1;
        int enemies = 0;
        int last_enemy_row = -1;
        int last_enemy_col = -1;

        // Check entire path for obstacles
        for (int step = 1; step < abs_row_diff; step++) {
            int check_row = from_row + dRow * step;
            int check_col = from_col + dCol * step;
            int check_piece = game->board[check_row][check_col];

            if (check_piece != EMPTY) {
                if (piece_belongs_to_color(check_piece, player_color)) {
                    printf("Own piece blocks at (%d,%d)\n", check_row, check_col);
                    return false;
                }

                enemies++;
                last_enemy_row = check_row;
                last_enemy_col = check_col;

                if (enemies > 1) {
                    printf("Multiple enemies in path\n");
                    return false;
                }
            }
        }

        // King can move freely (0 enemies) or capture (1 enemy)
        if (enemies == 0) {
            printf("Valid king move (distance: %d)\n", abs_row_diff);
            return true;
        } else if (enemies == 1) {
            printf("Valid king capture at (%d,%d)\n", last_enemy_row, last_enemy_col);
            return true;
        }

        return false;
    }

    // ========== REGULAR PIECE ==========

    // Single step move (non-capturing)
    if (abs_row_diff == 1 && col_diff == 1) {
        // Check direction (regular pieces can only move forward)
        if (piece == WHITE_PIECE && row_diff == -1) {
            printf("Valid WHITE move forward\n");
            return true;
        } else if (piece == BLACK_PIECE && row_diff == 1) {
            printf("Valid BLACK move forward\n");
            return true;
        } else {
            printf("Regular piece cannot move backward\n");
            return false;
        }
    }

    // Capture move (jump over enemy)
    if (abs_row_diff == 2 && col_diff == 2) {
        // Captures allowed in both directions (forward and backward)
        int mid_row = (from_row + to_row) / 2;
        int mid_col = (from_col + to_col) / 2;
        int mid_piece = game->board[mid_row][mid_col];

        if (mid_piece == EMPTY) {
            printf("No piece to capture at (%d,%d)\n", mid_row, mid_col);
            return false;
        }

        // Verify it's an enemy piece
        bool is_enemy = false;
        if (piece == WHITE_PIECE || piece == WHITE_KING) {
            is_enemy = (mid_piece == BLACK_PIECE || mid_piece == BLACK_KING);
        } else if (piece == BLACK_PIECE || piece == BLACK_KING) {
            is_enemy = (mid_piece == WHITE_PIECE || mid_piece == WHITE_KING);
        }

        if (is_enemy) {
            printf("Valid capture (can jump backward!): captured piece at (%d,%d)\n",
                   mid_row, mid_col);
            return true;
        } else {
            printf("Cannot capture own piece at (%d,%d)\n", mid_row, mid_col);
            return false;
        }
    }

    // Invalid move distance
    printf("Invalid move distance (%d) for regular piece\n", abs_row_diff);
    return false;
}

/**
 * Validates a complete move including turn verification.
 *
 * @param game Current game state
 * @param from_row Source row
 * @param from_col Source column
 * @param to_row Destination row
 * @param to_col Destination column
 * @param player Player making the move
 * @return true if move is valid
 */
bool validate_move(const Game *game, int from_row, int from_col,
                  int to_row, int to_col, const char *player) {
    printf("\n=== VALIDATE MOVE ===\n");

    if (strcmp(game->current_turn, player) != 0) {
        printf("Not player's turn\n");
        return false;
    }

    return validate_single_step(game, from_row, from_col, to_row, to_col, player);
}

/**
 * Applies a single move step to the board.
 * Handles piece movement, captures, and king promotion.
 *
 * @param game Game state to modify
 * @param from_row Source row
 * @param from_col Source column
 * @param to_row Destination row
 * @param to_col Destination column
 */
void apply_single_step(Game *game, int from_row, int from_col, int to_row, int to_col) {
    int piece = game->board[from_row][from_col];
    printf("Applying: (%d,%d)->(%d,%d)\n", from_row, from_col, to_row, to_col);
    // Move piece
    game->board[to_row][to_col] = piece;
    game->board[from_row][from_col] = EMPTY;

    int row_diff = abs(to_row - from_row);

    // Remove captured pieces (if jump move)
    if (row_diff >= 2) {
        int dRow = (to_row > from_row) ? 1 : -1;
        int dCol = (to_col > from_col) ? 1 : -1;

        for (int step = 1; step < row_diff; step++) {
            int mid_row = from_row + dRow * step;
            int mid_col = from_col + dCol * step;

            if (game->board[mid_row][mid_col] != EMPTY) {
                printf("  Removing (%d,%d)\n", mid_row, mid_col);
                game->board[mid_row][mid_col] = EMPTY;
            }
        }
    }

    // King promotion when reaching opposite end
    if (piece == WHITE_PIECE && to_row == 0) {
        game->board[to_row][to_col] = WHITE_KING;
        printf("  WHITE -> KING\n");
    } else if (piece == BLACK_PIECE && to_row == BOARD_SIZE - 1) {
        game->board[to_row][to_col] = BLACK_KING;
        printf("  BLACK -> KING\n");
    }
}

/**
 * Applies a move to the game board.
 *
 * @param game Game state to modify
 * @param from_row Source row
 * @param from_col Source column
 * @param to_row Destination row
 * @param to_col Destination column
 */
void apply_move(Game *game, int from_row, int from_col, int to_row, int to_col) {
    apply_single_step(game, from_row, from_col, to_row, to_col);
}

/**
 * Prints current board state to console for debugging.
 * Legend: w=white, W=white king, b=black, B=black king, .=empty
 *
 * @param game Game state to print
 */
void print_board(const Game *game) {
    printf("\n=== CURRENT BOARD ===\n");
    printf("   ");
    for (int j = 0; j < BOARD_SIZE; j++) {
        printf(" %d ", j);
    }
    printf("\n");

    for (int i = 0; i < BOARD_SIZE; i++) {
        printf("%d: ", i);
        for (int j = 0; j < BOARD_SIZE; j++) {
            int piece = game->board[i][j];
            char symbol;

            switch(piece) {
                case EMPTY: symbol = '.'; break;
                case WHITE_PIECE: symbol = 'w'; break;
                case WHITE_KING: symbol = 'W'; break;
                case BLACK_PIECE: symbol = 'b'; break;
                case BLACK_KING: symbol = 'B'; break;
                default: symbol = '?'; break;
            }

            printf(" %c ", symbol);
        }
        printf("\n");
    }
    printf("====================\n\n");
}

/**
 * Switches turn to the other player.
 *
 * @param game Game state to modify
 */
void change_turn(Game *game) {
    if (strcmp(game->current_turn, game->player1) == 0) {
        strncpy(game->current_turn, game->player2, MAX_PLAYER_NAME - 1);
        game->current_turn[MAX_PLAYER_NAME - 1] = '\0';
    } else {
        strncpy(game->current_turn, game->player1, MAX_PLAYER_NAME - 1);
        game->current_turn[MAX_PLAYER_NAME - 1] = '\0';
    }
}

/**
 * Checks if game is over (one player has no pieces remaining).
 *
 * @param game Current game state
 * @param winner Output buffer for winner's name
 * @return true if game is over
 */
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