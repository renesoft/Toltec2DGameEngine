package org.toltec.mapeditor;

import javafx.application.Application;
import org.toltec.engine.GpuAcceleration;

/**
 * See {@code org.toltec.editor.Launcher}'s javadoc — same reasoning, applied
 * to the map editor: routes through a class that doesn't itself extend
 * {@link Application} so the JDK launcher doesn't refuse to start it when
 * JavaFX isn't on the module path.
 */
public final class Launcher {

    public static void main(String[] args) {
        GpuAcceleration.enable(); // must run before any AWT/Swing/JavaFX toolkit class is touched
        Application.launch(MapEditorApp.class, args);
    }
}
