package com.checkerstcp.checkerstcp;

/**
 * Types of moves in checkers.
 */
public enum MoveType {
    NORMAL,          // Simple diagonal move
    CAPTURE,         // Single jump capture
    MULTI_CAPTURE    // Multiple consecutive captures
}