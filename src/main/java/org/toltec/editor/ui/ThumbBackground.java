package org.toltec.editor.ui;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;

/**
 * One shared setting, applied live to every thumbnail in the app (the asset
 * palette and the 8 direction cells) — sprites are usually authored with a
 * transparent background, which is invisible against the editor's own dark
 * panels, so the person can pick something with actual contrast.
 */
public class ThumbBackground {

    public enum Mode { DARK, LIGHT, CHECKER }

    private final ObjectProperty<Mode> mode = new SimpleObjectProperty<>(Mode.CHECKER);
    private static WritableImage checker;

    public ObjectProperty<Mode> modeProperty() { return mode; }
    public Mode getMode() { return mode.get(); }
    public void setMode(Mode m) { mode.set(m); }

    /** Applies the current mode to {@code region} now, and keeps it in sync as the mode changes later. */
    public void bind(Region region) {
        apply(region, mode.get());
        mode.addListener((o, a, b) -> apply(region, b));
    }

    private static void apply(Region region, Mode m) {
        region.setBackground(switch (m) {
            case DARK -> solid(Color.web("#20232c"));
            case LIGHT -> solid(Color.web("#e9e9ec"));
            case CHECKER -> new Background(new BackgroundFill(
                    new ImagePattern(checkerImage(), 0, 0, 16, 16, false), CornerRadii.EMPTY, Insets.EMPTY));
        });
    }

    private static Background solid(Color c) {
        return new Background(new BackgroundFill(c, CornerRadii.EMPTY, Insets.EMPTY));
    }

    private static WritableImage checkerImage() {
        if (checker != null) return checker;
        int size = 16, half = 8;
        WritableImage img = new WritableImage(size, size);
        PixelWriter pw = img.getPixelWriter();
        Color a = Color.web("#bdbdbd"), b = Color.web("#8f8f8f");
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean isA = ((x / half) + (y / half)) % 2 == 0;
                pw.setColor(x, y, isA ? a : b);
            }
        }
        checker = img;
        return checker;
    }
}
