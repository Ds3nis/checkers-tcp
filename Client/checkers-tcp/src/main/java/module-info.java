module com.checkerstcp.checkerstcp {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires eu.hansolo.tilesfx;
    requires com.almasb.fxgl.all;
    requires java.desktop;

    opens com.checkerstcp.checkerstcp to javafx.fxml;
    opens com.checkerstcp.checkerstcp.controller to javafx.fxml;
    exports com.checkerstcp.checkerstcp;
    exports com.checkerstcp.checkerstcp.controller;
}