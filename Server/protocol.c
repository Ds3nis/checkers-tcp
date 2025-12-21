//
// Created by denkhuda on 11/17/25.
//

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "protocol.h"

#include "server.h"

// Parse incoming message: DENTCP|OP|LEN|DATA
int parse_message(const char *buffer, Message *msg) {
    if (strncmp(buffer, PREFIX, PREFIX_LEN) != 0) {
        fprintf(stderr, "Invalid message prefix\n");
        return -1;
    }

    char temp[16];
    const char *ptr = buffer + PREFIX_LEN + 1; // Skip "DENTCP|"

    // Extract OP
    const char *next_pipe = strchr(ptr, '|');
    if (!next_pipe) return -1;

    int op_len = next_pipe - ptr;
    if (op_len >= sizeof(temp)) return -1;
    strncpy(temp, ptr, op_len);
    temp[op_len] = '\0';
    msg->op = atoi(temp);

    // Extract LEN
    ptr = next_pipe + 1;
    next_pipe = strchr(ptr, '|');
    if (!next_pipe) return -1;

    int len_len = next_pipe - ptr;
    if (len_len >= sizeof(temp)) return -1;
    strncpy(temp, ptr, len_len);
    temp[len_len] = '\0';
    msg->len = atoi(temp);

    // Extract DATA
    ptr = next_pipe + 1;
    int data_len = strlen(ptr);
    if (data_len > MAX_DATA_LEN - 1) {
        fprintf(stderr, "Data too long\n");
        return -1;
    }

    strncpy(msg->data, ptr, MAX_DATA_LEN - 1);
    msg->data[MAX_DATA_LEN - 1] = '\0';


    // printf("Message data %s",msg->data);
    // printf("\n");
    // printf("Message len %d",msg->len);
    // printf("Message operation code %d", msg->op);

    return 0;
}

// Create message: DENTCP|OP|LEN|DATA
int create_message(char *buffer, OpCode op, const char *data) {
    int data_len = data ? strlen(data) : 0;
    int written = snprintf(buffer, MAX_MESSAGE_LEN, "%s|%02d|%04d|%s",
                          PREFIX, op, data_len, data ? data : "");

    if (written >= MAX_MESSAGE_LEN) {
        fprintf(stderr, "Message too long\n");
        return -1;
    }

    return written;
}

void log_message(const char *prefix, const Message *msg) {
    printf("[%s] OP=%02d LEN=%d DATA=%s\n", prefix, msg->op, msg->len, msg->data);
}
