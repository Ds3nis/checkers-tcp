package com.checkerstcp.checkerstcp.network;

public class Message {
    private static final String PREFIX = "DENTCP";
    private static final int MAX_DATA_LENGTH = 4096; // Максимальна довжина даних

    private OpCode opCode;
    private int length;
    private String data;

    public Message(OpCode opCode, String data) {
        this.opCode = opCode;
        this.data = data != null ? data : "";
        this.length = this.data.length();

        // Валідація довжини
        if (this.length > MAX_DATA_LENGTH) {
            throw new IllegalArgumentException("Data too long: " + this.length + " > " + MAX_DATA_LENGTH);
        }
    }

    public String encode() {
        return String.format("%s|%02d|%04d|%s", PREFIX, opCode.getCode(), length, data);
    }

    public static Message parse(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }

        if (encoded.length() > MAX_DATA_LENGTH + 100) {
            System.err.println("Message too long: " + encoded.length());
            return null;
        }

        String[] parts = encoded.split("\\|", 4);
        if (parts.length < 4) {
            System.err.println("Invalid message format (expected 4 parts, got " + parts.length + "): " + encoded);
            return null;
        }

        if (!PREFIX.equals(parts[0])) {
            System.err.println("Invalid prefix (expected '" + PREFIX + "', got '" + parts[0] + "')");
            return null;
        }

        try {
            int opCodeValue = Integer.parseInt(parts[1]);
            int declaredLength = Integer.parseInt(parts[2]);
            String data = parts[3];

            if (declaredLength != data.length()) {
                System.err.println("Length mismatch: declared=" + declaredLength + ", actual=" + data.length());
                return null;
            }

            if (declaredLength > MAX_DATA_LENGTH) {
                System.err.println("Data length exceeds maximum: " + declaredLength + " > " + MAX_DATA_LENGTH);
                return null;
            }

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

    public OpCode getOpCode() {
        return opCode;
    }

    public String getData() {
        return data;
    }

    public int getLength() {
        return length;
    }

    @Override
    public String toString() {
        return String.format("Message[op=%s, len=%d, data=%s]", opCode, length, data);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Message message = (Message) o;
        return length == message.length &&
                opCode == message.opCode &&
                data.equals(message.data);
    }

    @Override
    public int hashCode() {
        int result = opCode.hashCode();
        result = 31 * result + length;
        result = 31 * result + data.hashCode();
        return result;
    }
}