package com.checkerstcp.checkerstcp.network;

/**
 * Protocol message structure for client-server communication.
 * Implements the DENTCP protocol format: DENTCP|OP|LEN|DATA\n
 *
 * <p>Protocol specification:
 * <ul>
 *   <li>PREFIX: "DENTCP" - Protocol identifier</li>
 *   <li>OP: Two-digit operation code (01-99, or 500 for errors)</li>
 *   <li>LEN: Four-digit data length (0000-9999)</li>
 *   <li>DATA: Variable-length payload (max 4096 bytes)</li>
 *   <li>Delimiter: Newline character (\n)</li>
 * </ul>
 *
 * <p>Example: DENTCP|01|0004|john\n (LOGIN with username "john")
 *
 * <p>Security features:
 * <ul>
 *   <li>Maximum data length enforcement</li>
 *   <li>Prefix validation</li>
 *   <li>Length field verification</li>
 *   <li>OpCode range checking</li>
 *   <li>Format validation</li>
 * </ul>
 */
public class Message {
    private static final String PREFIX = "DENTCP";
    private static final int MAX_DATA_LENGTH = 8192; // Maximum payload size in bytes

    private OpCode opCode;
    private int length;
    private String data;

    /**
     * Constructs a new message with operation code and data.
     * Automatically calculates and validates data length.
     *
     * @param opCode Operation code for this message
     * @param data Message payload (null treated as empty string)
     * @throws IllegalArgumentException if data exceeds maximum length
     */
    public Message(OpCode opCode, String data) {
        this.opCode = opCode;
        this.data = data != null ? data : "";
        this.length = this.data.length();

        // Validate data length
        if (this.length > MAX_DATA_LENGTH) {
            throw new IllegalArgumentException("Data too long: " + this.length + " > " + MAX_DATA_LENGTH);
        }
    }

    /**
     * Encodes this message into protocol format string.
     * Format: DENTCP|OP|LEN|DATA (without newline - added by sender)
     *
     * @return Encoded message string ready for transmission
     */
    public String encode() {
        return String.format("%s|%02d|%04d|%s", PREFIX, opCode.getCode(), length, data);
    }

    /**
     * Parses a received message string into Message object.
     * Performs extensive validation to detect malformed or malicious messages.
     *
     * <p>Validation checks:
     * <ul>
     *   <li>Message not null/empty</li>
     *   <li>Total length within bounds</li>
     *   <li>Correct number of pipe-delimited fields (4)</li>
     *   <li>Valid protocol prefix</li>
     *   <li>Numeric operation code in valid range</li>
     *   <li>Data length matches declared length</li>
     *   <li>Data length not negative or excessive</li>
     * </ul>
     *
     * @param encoded Raw message string received from server
     * @return Parsed Message object, or null if parsing fails
     */
    public static Message parse(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }

        // Prevent buffer overflow attacks
        if (encoded.length() > MAX_DATA_LENGTH) {
            System.err.println("Message too long: " + encoded.length());
            return null;
        }

        // Split into 4 parts: PREFIX|OP|LEN|DATA
        String[] parts = encoded.split("\\|", 4);
        if (parts.length < 4) {
            System.err.println("Invalid message format (expected 4 parts, got " + parts.length + "): " + encoded);
            return null;
        }

        // Validate protocol prefix
        if (!PREFIX.equals(parts[0])) {
            System.err.println("Invalid prefix (expected '" + PREFIX + "', got '" + parts[0] + "')");
            return null;
        }

        try {
            // Parse operation code
            int opCodeValue = Integer.parseInt(parts[1]);
            int declaredLength = Integer.parseInt(parts[2]);
            String data = parts[3];

            // Verify data length matches declared length
            if (declaredLength != data.length()) {
                System.err.println("Length mismatch: declared=" + declaredLength + ", actual=" + data.length());
                return null;
            }

            // Validate length is not negative (security check)
            if (declaredLength < 0) {
                System.err.println("SECURITY: Negative length: " + declaredLength);
                return null;
            }

            // Validate length does not exceed maximum
            if (declaredLength > MAX_DATA_LENGTH) {
                System.err.println("Data length exceeds maximum: " + declaredLength + " > " + MAX_DATA_LENGTH);
                return null;
            }

            // Validate operation code is known
            OpCode opCode = OpCode.fromCode(opCodeValue);
            if (opCode == null) {
                System.err.println("Unknown opcode: " + opCodeValue);
                return null;
            }

            return new Message(opCode, data);

        } catch (NumberFormatException e) {
            System.err.println("Invalid number format in message: " + encoded);
            return null;
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid message: " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets the operation code of this message.
     *
     * @return OpCode enum value
     */
    public OpCode getOpCode() {
        return opCode;
    }

    /**
     * Gets the message payload data.
     *
     * @return Data string (never null, may be empty)
     */
    public String getData() {
        return data;
    }

    /**
     * Gets the length of the message data.
     *
     * @return Data length in bytes
     */
    public int getLength() {
        return length;
    }

    /**
     * Returns string representation of this message.
     * Format: "Message[op=OPCODE, len=LENGTH, data=DATA]"
     *
     * @return Formatted message description
     */
    @Override
    public String toString() {
        return String.format("Message[op=%s, len=%d, data=%s]", opCode, length, data);
    }

    /**
     * Checks equality with another object.
     * Two messages are equal if they have the same operation code,
     * length, and data content.
     *
     * @param o Object to compare with
     * @return true if messages are equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Message message = (Message) o;
        return length == message.length &&
                opCode == message.opCode &&
                data.equals(message.data);
    }

    /**
     * Computes hash code for this message.
     * Based on operation code, length, and data.
     *
     * @return Hash code value
     */
    @Override
    public int hashCode() {
        int result = opCode.hashCode();
        result = 31 * result + length;
        result = 31 * result + data.hashCode();
        return result;
    }
}