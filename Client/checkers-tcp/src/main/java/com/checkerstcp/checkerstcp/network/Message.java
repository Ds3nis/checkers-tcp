package com.checkerstcp.checkerstcp.network;

public class Message {
    private static final String PREFIX = "DENTCP";

    private OpCode opCode;
    private int length;
    private String data;

    public Message(OpCode opCode, String data) {
        this.opCode = opCode;
        this.data = data != null ? data : "";
        this.length = this.data.length();
    }


    public String encode() {
        return String.format("%s|%02d|%04d|%s", PREFIX, opCode.getCode(), length, data);
    }

    public static Message parse(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }

        String[] parts = encoded.split("\\|", 4);
        if (parts.length < 4) {
            System.err.println("Invalid message format: " + encoded);
            return null;
        }

        if (!PREFIX.equals(parts[0])) {
            System.err.println("Invalid prefix: " + parts[0]);
            return null;
        }

        try {
            int opCodeValue = Integer.parseInt(parts[1]);
            int length = Integer.parseInt(parts[2]);
            String data = parts[3];

            OpCode opCode = OpCode.fromCode(opCodeValue);
            if (opCode == null) {
                System.err.println("Unknown opcode: " + opCodeValue);
                return null;
            }

            Message message = new Message(opCode, data);
            return message;

        } catch (NumberFormatException e) {
            System.err.println("Invalid number format in message: " + encoded);
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
}
