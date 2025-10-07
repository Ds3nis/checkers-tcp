package com.checkerstcp.checkerstcp;

import javafx.scene.image.Image;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Class for loading application resources from configuration files.
 * The resources includes configuration settings and strings for GUI.
 * @author Denys Khuda
 * @version 1.0 (2025-10-07)
 */
public class AppResources {
    /** Name of the file with (GUI) texts */
    public static final String TEXTS_FILE_NAME = "fxGuiTexts";
    /** Name of the file with configuration */
    public static final String CONFIG_FILE_NAME = "fxConfig";
    /** Name of the JAR file */
    public static final String JAR_FILE_NAME = "com.checkerstcp.checkerstcp.jar";
    /** Czech locale ID string */
    public static final String CZECH_LOCALE = "cs";
    /** English locale ID string */
    public static final String ENGLISH_LOCALE = "en";
    /** Current locale of the application */
    public static String currentLocale = CZECH_LOCALE;
    /** Czech GUI texts */
    private ResourceBundle textsCS;
    /** English GUI texts */
    private ResourceBundle textsEN;
    /** Current GUI text (English or Czech depending on currentLocale) */
    private ResourceBundle texts;
    /** Configuration */
    private ResourceBundle config;
    /** Singleton reference to instance of this class */
    private static AppResources resources = null;

    /**
     * Constructor initializing resource bundle.
     */
    private AppResources() {
        initBundles();
    }

    /**
     * Loads the resources from files using <code>ResourceBundle</code>
     */
    private void initBundles() {
        try {
            config = new PropertyResourceBundle(Files.newInputStream(
                    Paths.get(CONFIG_FILE_NAME + "_cs.properties")));
        }
        catch (Exception ex) {
            System.out.print("Cannot find configuration file outside the JAR " + "file. Trying inside the JAR file...");
            config = ResourceBundle.getBundle("config/" + CONFIG_FILE_NAME, new Locale(CZECH_LOCALE));
            System.out.println(" Succeeded!");

            try {
                Path jarFile = Paths.get(JAR_FILE_NAME); //For manual running
                if (!Files.exists(jarFile)) {
                    jarFile = Paths.get("../lib/"+ JAR_FILE_NAME); //For distZip BAT
                }
                if (Files.exists(jarFile)) {
                    System.out.print("Trying to copy config file from the JAR...");
                    copyFileFromJAR(jarFile.toString(), CONFIG_FILE_NAME + "_" + CZECH_LOCALE + ".properties");
                    System.out.println(" Succeeded!");
                }
                else {
                    System.out.println("Cannot locate JAR file for extraction of " + "configuration file.");
                }
            }
            catch (IOException ioEx) {
                ioEx.printStackTrace();
                System.out.println(" Failed!");
            }
        }
        textsCS = ResourceBundle.getBundle("lang/" + TEXTS_FILE_NAME, new Locale(CZECH_LOCALE));
        textsEN = ResourceBundle.getBundle("lang/" + TEXTS_FILE_NAME, new Locale(ENGLISH_LOCALE));

        currentLocale = config.getString("application.language");
        if (currentLocale.equals(CZECH_LOCALE)) {
            texts = textsCS;
        }
        else if (currentLocale.equals(ENGLISH_LOCALE)) {
            texts = textsEN;
        }
        else {
            texts = textsCS;
        }
    }

    /**
     * Factory method for getting an instance
     * @return an instance of this class
     */
    public static AppResources getInstance() {
        if (resources == null) {
            resources = new AppResources();
        }
        return resources;
    }

    /**
     * Gets a resource from the resource bundle for the specified key
     * @param key key of the required resource
     * @param configString indicates whether a config string is wanted
     * @return required resource or the key if such key does not exist
     */
    public String getResourceString(String key, boolean configString) {
        String resource = key;
        try {
            if (configString) {
                resource = config.getString(key);
            }
            else {
                resource = texts.getString(key);
            }
        }
        catch (MissingResourceException e) {
            e.printStackTrace();
        }
        catch (NullPointerException e) {
            e.printStackTrace();
        }

        return resource;
    }

    /**
     * Returns a string for the specified key from the texts resources
     * @param key of the required string
     * @return string for the specified key from the texts resources
     */
    public String getTextsString(String key) {
        return getResourceString(key, false);
    }

    /**
     * Returns a string for the specified key from the config resources
     * @param key of the required string
     * @return string for the specified key from the config resources
     */
    public String getConfigString(String key) {
        return getResourceString(key, true);
    }

    /**
     * Returns an integer for the specified key from the config resources
     * @param key of the required string
     * @return integer for the specified key from the config resources
     */
    public int getConfigInt(String key) {
        int resourceInt = 0;

        try {
            resourceInt = Integer.parseInt(getResourceString(key, true));
        }
        catch (NumberFormatException ex) {
            ex.printStackTrace();
        }

        return resourceInt;
    }

    /**
     * Copies a specified file from the specified JAR file
     * @param nameOfJAR name (with path) of the JAR file
     * @param nameOfFile name of the file
     * @throws FileNotFoundException if JAR does not exist
     * @throws IOException if a problem occurs during copying from JAR
     */
    public static void copyFileFromJAR(String nameOfJAR, String nameOfFile)
            throws FileNotFoundException, IOException {
        try (
                JarInputStream fr = new JarInputStream(new BufferedInputStream(
                        Files.newInputStream(Paths.get(nameOfJAR))));
                BufferedOutputStream fw = new BufferedOutputStream(
                        Files.newOutputStream(Paths.get(nameOfFile)));) {
            JarEntry je = null;
            String name = "";
            int i = 0;

            while ((je = fr.getNextJarEntry()) != null) {
                name = je.getName();
                if (name.endsWith(nameOfFile)) {
                    while ((i = fr.read()) != -1) {
                        fw.write(i);
                    }
                    break;
                }
            }
        }
    }

    /**
     * Returns icon represented by the specified name, if such icon exists
     * @param iconFileName the file name of the wanted icon
     * @return icon represented by the specified name, if such icon exists
     */
    public static Image findIcon(String iconFileName) {
        URL iconURL = AppResources.class.getResource("/images/icons/"
                + iconFileName);
        if (iconURL != null) {
            return new Image(iconURL.toString());
        }
        else {
            return null;
        }
    }
}
