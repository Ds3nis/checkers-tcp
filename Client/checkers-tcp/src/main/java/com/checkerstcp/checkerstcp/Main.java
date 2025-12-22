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

public class Main extends Application {
    private ClientManager clientManager;

    @Override
    public void start(Stage stage) throws IOException {
        clientManager = ClientManager.getInstance();
        URL fxmlUrl = Main.class.getResource("lobby-view.fxml");
        FXMLLoader fxmlLoader = new FXMLLoader(Objects.requireNonNull(
                fxmlUrl,
                "FXML soubor 'lobby-view.fxml' nebyl nalezen. Ujistěte se, že je v " +
                        "src/main/resources/com/checkerstcp/checkerstcp/"
        ));

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

        stage.setOnCloseRequest(event -> {
            System.out.println("Application closing...");
            cleanup();
        });

        stage.show();
    }

    @Override
    public void init() throws Exception {
        cleanup();
        super.stop();
    }


    @Override
    public void stop() throws Exception {}

    private void cleanup() {
        if (clientManager != null && clientManager.isConnected()) {
            System.out.println("Disconnecting from server...");
            clientManager.disconnect();
        }
        System.out.println("Application stopped");
    }


    public static void main(String[] args) {
        launch();
    }
}