package org.toltec.mapeditor.model;

/** What the eraser tool removes when brushed over a cell. */
public enum EraseMode {
    UNITS("Только юниты"),
    OBJECTS("Только объекты"),
    UNITS_AND_OBJECTS("Юниты и объекты"),
    ALL("Всё, включая пол");

    private final String label;

    EraseMode(String label) { this.label = label; }

    public String label() { return label; }
}
