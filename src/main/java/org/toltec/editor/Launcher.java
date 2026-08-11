package org.toltec.editor;

import javafx.application.Application;
import org.toltec.engine.GpuAcceleration;

/**
 * The JDK's own launcher refuses to start a class that <em>directly</em>
 * extends {@link Application} when JavaFX isn't on the module path (the
 * "JavaFX runtime components are missing" error) — even if the JavaFX jars
 * are sitting right there on the classpath. Routing through a separate
 * class that doesn't itself extend {@code Application} sidesteps that check
 * entirely, so the editor runs the same way whether it's launched via
 * {@code mvn javafx:run}, an IDE run configuration, or a plain
 * {@code java -cp ...} command.
 */
public final class Launcher {

    public static void main(String[] args) {
        // Must run before any AWT/Swing/JavaFX toolkit class is touched.
        GpuAcceleration.enable();
        Application.launch(EditorApp.class, args);
    }
}
