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
    printf("\n=== VALIDATE MOVE ===\n");
    printf("Player: '%s'\n", player);
    printf("From: (%d, %d) -> To: (%d, %d)\n", from_row, from_col, to_row, to_col);
    printf("Current turn: '%s'\n", game->current_turn);

    // Check if it's player's turn
    if (strcmp(game->current_turn, player) != 0) {
        printf("❌ FAIL: Not player's turn (current: '%s', trying: '%s')\n",
               game->current_turn, player);
        return false;
    }
    printf("✅ Turn check passed\n");

    // Check bounds
    if (from_row < 0 || from_row >= BOARD_SIZE || from_col < 0 || from_col >= BOARD_SIZE ||
        to_row < 0 || to_row >= BOARD_SIZE || to_col < 0 || to_col >= BOARD_SIZE) {
        printf("❌ FAIL: Out of bounds\n");
        printf("   from_row=%d (valid: 0-%d)\n", from_row, BOARD_SIZE-1);
        printf("   from_col=%d (valid: 0-%d)\n", from_col, BOARD_SIZE-1);
        printf("   to_row=%d (valid: 0-%d)\n", to_row, BOARD_SIZE-1);
        printf("   to_col=%d (valid: 0-%d)\n", to_col, BOARD_SIZE-1);
        return false;
    }
    printf("✅ Bounds check passed\n");

    // Check if destination is empty
    int dest_piece = game->board[to_row][to_col];
    if (dest_piece != EMPTY) {
        printf("❌ FAIL: Destination not empty (contains: %d)\n", dest_piece);
        return false;
    }
    printf("✅ Destination empty\n");

    // Get piece
    int piece = game->board[from_row][from_col];
    if (piece == EMPTY) {
        printf("❌ FAIL: Source position is empty\n");
        return false;
    }
    printf("✅ Source has piece: %d ", piece);

    // Print piece type
    if (piece == WHITE_PIECE) printf("(WHITE_PIECE)\n");
    else if (piece == WHITE_KING) printf("(WHITE_KING)\n");
    else if (piece == BLACK_PIECE) printf("(BLACK_PIECE)\n");
    else if (piece == BLACK_KING) printf("(BLACK_KING)\n");
    else printf("(UNKNOWN: %d)\n", piece);

    // Determine player's color
    PlayerColor player_color = (strcmp(player, game->player1) == 0) ?
                               game->player1_color : game->player2_color;

    printf("Player '%s' color: %s\n", player,
           player_color == COLOR_WHITE ? "WHITE" : "BLACK");
    printf("Player1: '%s' (color: %s)\n", game->player1,
           game->player1_color == COLOR_WHITE ? "WHITE" : "BLACK");
    printf("Player2: '%s' (color: %s)\n", game->player2,
           game->player2_color == COLOR_WHITE ? "WHITE" : "BLACK");

    // Check if piece belongs to player
    bool piece_belongs_to_player = false;
    if (player_color == COLOR_WHITE) {
        piece_belongs_to_player = (piece == WHITE_PIECE || piece == WHITE_KING);
    } else if (player_color == COLOR_BLACK) {
        piece_belongs_to_player = (piece == BLACK_PIECE || piece == BLACK_KING);
    }

    if (!piece_belongs_to_player) {
        printf("❌ FAIL: Piece doesn't belong to player\n");
        printf("   Expected color: %s\n", player_color == COLOR_WHITE ? "WHITE" : "BLACK");
        printf("   Piece type: %d\n", piece);
        return false;
    }
    printf("✅ Piece belongs to player\n");

    // Basic move validation
    int row_diff = to_row - from_row;
    int col_diff = abs(to_col - from_col);

    printf("Move: row_diff=%d, col_diff=%d\n", row_diff, col_diff);

    bool valid_move = false;

    // Regular pieces move diagonally forward
    if (piece == WHITE_PIECE) {
        printf("Checking WHITE_PIECE rules:\n");
        if (row_diff == -1 && col_diff == 1) {
            printf("  ✅ Normal move (forward)\n");
            valid_move = true;
        } else if (row_diff == -2 && col_diff == 2) {
            printf("  ✅ Jump move\n");
            // TODO: Validate jump (there should be opponent piece in between)
            int mid_row = (from_row + to_row) / 2;
            int mid_col = (from_col + to_col) / 2;
            int mid_piece = game->board[mid_row][mid_col];
            printf("  Jump over (%d, %d): piece=%d\n", mid_row, mid_col, mid_piece);

            if (mid_piece == BLACK_PIECE || mid_piece == BLACK_KING) {
                printf("  ✅ Valid jump (over opponent piece)\n");
                valid_move = true;
            } else {
                printf("  ❌ Invalid jump (no opponent piece)\n");
            }
        } else {
            printf("  ❌ Invalid move for WHITE_PIECE\n");
        }
    } else if (piece == BLACK_PIECE) {
        printf("Checking BLACK_PIECE rules:\n");
        if (row_diff == 1 && col_diff == 1) {
            printf("  ✅ Normal move (forward)\n");
            valid_move = true;
        } else if (row_diff == 2 && col_diff == 2) {
            printf("  ✅ Jump move\n");
            int mid_row = (from_row + to_row) / 2;
            int mid_col = (from_col + to_col) / 2;
            int mid_piece = game->board[mid_row][mid_col];
            printf("  Jump over (%d, %d): piece=%d\n", mid_row, mid_col, mid_piece);

            if (mid_piece == WHITE_PIECE || mid_piece == WHITE_KING) {
                printf("  ✅ Valid jump (over opponent piece)\n");
                valid_move = true;
            } else {
                printf("  ❌ Invalid jump (no opponent piece)\n");
            }
        } else {
            printf("  ❌ Invalid move for BLACK_PIECE\n");
        }
    } else { // Kings can move both directions
        printf("Checking KING rules:\n");
        if (abs(row_diff) == 1 && col_diff == 1) {
            printf("  ✅ Normal move (any direction)\n");
            valid_move = true;
        } else if (abs(row_diff) == 2 && col_diff == 2) {
            printf("  ✅ Jump move\n");
            int mid_row = (from_row + to_row) / 2;
            int mid_col = (from_col + to_col) / 2;
            int mid_piece = game->board[mid_row][mid_col];
            printf("  Jump over (%d, %d): piece=%d\n", mid_row, mid_col, mid_piece);

            // King can jump over opponent pieces
            bool is_opponent = false;
            if (player_color == COLOR_WHITE) {
                is_opponent = (mid_piece == BLACK_PIECE || mid_piece == BLACK_KING);
            } else {
                is_opponent = (mid_piece == WHITE_PIECE || mid_piece == WHITE_KING);
            }

            if (is_opponent) {
                printf("  ✅ Valid jump (over opponent piece)\n");
                valid_move = true;
            } else {
                printf("  ❌ Invalid jump (no opponent piece)\n");
            }
        } else {
            printf("  ❌ Invalid move for KING\n");
        }
    }

    printf("=== RESULT: %s ===\n\n", valid_move ? "✅ VALID" : "❌ INVALID");
    return valid_move;
}

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