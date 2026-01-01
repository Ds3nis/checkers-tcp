package com.checkerstcp.checkerstcp;

/**
 * Простий парсер JSON для стану гри
 * Використовується якщо немає залежності org.json
 */
public class GameStateParser {

    /**
     * Парсинг дошки з JSON формату сервера
     * Формат: {"board":[[3,0,3,...],[0,3,0,...],...], "current_turn":"player1", ...}
     */
    public static int[][] parseBoardFromJson(String json) {
        int[][] board = new int[8][8];

        try {
            // Знайти масив board
            int boardStart = json.indexOf("\"board\":");
            if (boardStart == -1) return board;

            // Знайти початок масиву
            int arrayStart = json.indexOf("[", boardStart);
            int arrayEnd = findMatchingBracket(json, arrayStart);

            String boardStr = json.substring(arrayStart + 1, arrayEnd);

            // Розбити на рядки
            String[] rows = boardStr.split("\\],\\[");

            for (int i = 0; i < Math.min(rows.length, 8); i++) {
                String row = rows[i].replace("[", "").replace("]", "").trim();
                String[] cells = row.split(",");

                for (int j = 0; j < Math.min(cells.length, 8); j++) {
                    board[i][j] = Integer.parseInt(cells[j].trim());
                }
            }

        } catch (Exception e) {
            System.err.println("Error parsing board from JSON: " + e.getMessage());
        }

        return board;
    }

    /**
     * Отримати значення поля з JSON
     */
    public static String getJsonValue(String json, String key) {
        try {
            String searchKey = "\"" + key + "\":";
            int keyIndex = json.indexOf(searchKey);
            if (keyIndex == -1) return null;

            int valueStart = keyIndex + searchKey.length();

            // Пропустити пробіли
            while (valueStart < json.length() && json.charAt(valueStart) == ' ') {
                valueStart++;
            }

            // Якщо значення в лапках
            if (json.charAt(valueStart) == '"') {
                valueStart++;
                int valueEnd = json.indexOf('"', valueStart);
                return json.substring(valueStart, valueEnd);
            }
            // Якщо число або boolean
            else {
                int valueEnd = valueStart;
                while (valueEnd < json.length() &&
                        json.charAt(valueEnd) != ',' &&
                        json.charAt(valueEnd) != '}') {
                    valueEnd++;
                }
                return json.substring(valueStart, valueEnd).trim();
            }

        } catch (Exception e) {
            System.err.println("Error getting JSON value: " + e.getMessage());
            return null;
        }
    }

    /**
     * Знайти парну дужку
     */
    private static int findMatchingBracket(String str, int startIndex) {
        int count = 1;
        for (int i = startIndex + 1; i < str.length(); i++) {
            if (str.charAt(i) == '[') count++;
            if (str.charAt(i) == ']') count--;
            if (count == 0) return i;
        }
        return -1;
    }

    /**
     * Конвертувати дошку в JSON формат
     */
    public static String boardToJson(int[][] board) {
        StringBuilder json = new StringBuilder("{\"board\":[");

        for (int i = 0; i < 8; i++) {
            json.append("[");
            for (int j = 0; j < 8; j++) {
                json.append(board[i][j]);
                if (j < 7) json.append(",");
            }
            json.append("]");
            if (i < 7) json.append(",");
        }

        json.append("]}");
        return json.toString();
    }
}