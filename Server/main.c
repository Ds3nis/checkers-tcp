//
// Created by Denis on 16.11.2025.
//

#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <unistd.h>
#include "server.h"

static Server server;

/**
 * Signal handler for graceful shutdown.
 * Catches SIGINT (Ctrl+C) and SIGTERM to properly stop the server.
 *
 * @param signum Signal number received
 */
void signal_handler(int signum) {
    printf("\nReceived signal %d, shutting down...\n", signum);
    server_stop(&server);
    exit(0);
}

/**
 * Prints usage information for the server program.
 *
 * @param program_name Name of the executable
 */
void print_usage(const char *program_name) {
    printf("Usage: %s [port] [bind_address]\n", program_name);
    printf("  port         - Port number (default: 12345)\n");
    printf("  bind_address - IP address to bind to (default: 0.0.0.0 - all interfaces)\n");
    printf("\nExamples:\n");
    printf("  %s 8080                  # Port 8080, all interfaces\n", program_name);
    printf("  %s 8080 127.0.0.1        # Port 8080, localhost only\n", program_name);
    printf("  %s 12345 192.168.1.100   # Port 12345, specific IP\n", program_name);
}

/**
 * Main entry point for the checkers server.
 * Parses command line arguments, initializes server, and starts accepting connections.
 *
 * @param argc Argument count
 * @param argv Argument vector
 * @return 0 on success, 1 on failure
 */
int main(int argc, char *argv[]) {
    int port = 12345; // Default port
    const char *bind_address = NULL; // NULL means INADDR_ANY (0.0.0.0)

    if (argc > 1) {
        if (strcmp(argv[1], "-h") == 0 || strcmp(argv[1], "--help") == 0) {
            print_usage(argv[0]);
            return 0;
        }

        port = atoi(argv[1]);
        if (port <= 0 || port > 65535) {
            fprintf(stderr, "Invalid port number. Using default: 12345\n");
            port = 12345;
        }
    }


    if (argc > 2) {
        bind_address = argv[2];
    }

    // Setup signal handlers
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    printf("=== Checkers Server ===\n");
    printf("Initializing server on port %d...\n", port);

    if (server_init(&server, port, bind_address) < 0) {
        fprintf(stderr, "Failed to initialize server\n");
        return 1;
    }

    printf("Server ready!\n");
    printf("Press Ctrl+C to stop the server\n\n");

    // Start server (blocking)
    server_start(&server);

    return 0;
}