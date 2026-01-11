CC = gcc
CFLAGS = -Wall -Wextra -pthread -g -O2
LDFLAGS = -pthread

TARGET = checkers_server
OBJS = main.o server.o game.o protocol.o client_state_machine.o

.PHONY: all clean

all: $(TARGET)

$(TARGET): $(OBJS)
	$(CC) $(LDFLAGS) -o $@ $^
	@echo "Build complete! Run with: ./$(TARGET) [port]"

main.o: main.c server.h
	$(CC) $(CFLAGS) -c main.c

server.o: server.c server.h protocol.h game.h client_state_machine.h
	$(CC) $(CFLAGS) -c server.c

game.o: game.c game.h
	$(CC) $(CFLAGS) -c game.c

protocol.o: protocol.c protocol.h
	$(CC) $(CFLAGS) -c protocol.c

client_state_machine.o: client_state_machine.c client_state_machine.h server.h protocol.h
	$(CC) $(CFLAGS) -c client_state_machine.c

clean:
	rm -f $(OBJS) $(TARGET)
	@echo "Clean complete"

run: $(TARGET)
	./$(TARGET) 12345

debug: CFLAGS += -DDEBUG -O0
debug: clean all