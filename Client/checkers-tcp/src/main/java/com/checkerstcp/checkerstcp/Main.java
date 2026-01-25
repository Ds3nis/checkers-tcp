package com.checkerstcp.checkerstcp;

import com.checkerstcp.checkerstcp.network.ClientManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

/**
 * Main application entry point for Checkers-TCP client.
 * Initializes JavaFX stage, loads lobby view, and manages application lifecycle.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Application initialization and startup</li>
 *   <li>Window configuration and icon setup</li>
 *   <li>ClientManager singleton initialization</li>
 *   <li>Graceful shutdown and resource cleanup</li>
 *   <li>Server disconnection on application close</li>
 * </ul>
 */
public class Main extends Application {
    private ClientManager clientManager;

    /**
     * Starts the JavaFX application.
     * Loads lobby view as initial scene and configures main window.
     *
     * @param stage Primary stage for this application
     * @throws IOException if FXML loading fails
     */
    @Override
    public void start(Stage stage) throws IOException {
        clientManager = ClientManager.getInstance();

        // Load lobby FXML
        URL fxmlUrl = Main.class.getResource("lobby-view.fxml");
        FXMLLoader fxmlLoader = new FXMLLoader(Objects.requireNonNull(
                fxmlUrl,
                "FXML soubor 'lobby-view.fxml' nebyl nalezen. Ujistěte se, že je v " +
                        "src/main/resources/com/checkerstcp/checkerstcp/"
        ));

        // Load application icon
        Image appIcon = new Image(
                Objects.requireNonNull(
                        Main.class.getResourceAsStream("images/logo-icon.png"),
                        "Resource 'images/logo-icon.png' not found relative to Main package"
                )
        );
        stage.getIcons().add(appIcon);

        Scene scene = new Scene(fxmlLoader.load());
        stage.setTitle("Checkers-TCP");
        stage.setMinHeight(400);
        stage.getIcons().add(appIcon);
        stage.setMinWidth(400);
        stage.setScene(scene);

        // Handle application close
        stage.setOnCloseRequest(event -> {
            System.out.println("Application closing...");
            cleanup();
        });

        stage.show();
    }

    /**
     * Initializes the application.
     * Performs cleanup before starting.
     *
     * @throws Exception if initialization fails
     */
    @Override
    public void init() throws Exception {
        cleanup();
        super.stop();
    }

    /**
     * Called when application is stopping.
     * Override required but cleanup handled in cleanup() method.
     *
     * @throws Exception if stop fails
     */
    @Override
    public void stop() throws Exception {}

    /**
     * Performs cleanup operations.
     * Disconnects from server if connected and releases resources.
     */
    private void cleanup() {
        if (clientManager != null && clientManager.isConnected()) {
            System.out.println("Disconnecting from server...");
            clientManager.disconnect();
        }
        System.out.println("Application stopped");
    }

    /**
     * Application entry point.
     *
     * @param args Command line arguments (unused)
     */
    public static void main(String[] args) {
        launch();
    }
}