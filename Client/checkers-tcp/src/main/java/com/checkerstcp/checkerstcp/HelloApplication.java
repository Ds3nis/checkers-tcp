package com.checkerstcp.checkerstcp;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

public class HelloApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        // Očekáváme: src/main/resources/com/checkerstcp/checkerstcp/hello-view.fxml
        URL fxmlUrl = HelloApplication.class.getResource("hello-view.fxml");
        FXMLLoader fxmlLoader = new FXMLLoader(Objects.requireNonNull(
                fxmlUrl,
                "FXML soubor 'hello-view.fxml' nebyl nalezen. Ujistěte se, že je v src/main/resources/com/checkerstcp/checkerstcp/"
        ));

        Scene scene = new Scene(fxmlLoader.load());
        stage.setTitle("Hello!");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}