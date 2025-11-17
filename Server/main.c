//
// Created by Denis on 16.11.2025.
//

#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <unistd.h>
#include "server.h"

static Server server;

void signal_handler(int signum) {
    printf("\nReceived signal %d, shutting down...\n", signum);
    server_stop(&server);
    exit(0);
}

int main(int argc, char *argv[]) {
    int port = 12345; // Default port

    if (argc > 1) {
        port = atoi(argv[1]);
        if (port <= 0 || port > 65535) {
            fprintf(stderr, "Invalid port number. Using default: 12345\n");
            port = 12345;
        }
    }

    // Setup signal handlers
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    printf("=== Checkers Server ===\n");
    printf("Initializing server on port %d...\n", port);

    if (server_init(&server, port) < 0) {
        fprintf(stderr, "Failed to initialize server\n");
        return 1;
    }

    printf("Server ready!\n");
    printf("Press Ctrl+C to stop the server\n\n");

    // Start server (blocking)
    server_start(&server);

    return 0;
}