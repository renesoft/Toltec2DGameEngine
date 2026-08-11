package org.toltec.editor.model;

/** The three kinds of thing the editor manages, each with its own resources subfolder and ini shape. */
public enum ObjectCategory {
    FLOOR("floors", "Полы", "Пол"),
    UNIT("units", "Юниты", "Юнит"),
    OBJECT("objects", "Объекты", "Объект");

    private final String folderName;
    private final String pluralRu;
    private final String singularRu;

    ObjectCategory(String folderName, String pluralRu, String singularRu) {
        this.folderName = folderName;
        this.pluralRu = pluralRu;
        this.singularRu = singularRu;
    }

    /** Subfolder of the resources root this category lives under, e.g. {@code resources/units/}. */
    public String folderName() { return folderName; }

    /** Group label for the left-hand list, e.g. "Юниты". */
    public String pluralRu() { return pluralRu; }

    /** Label for a single instance, e.g. "Юнит". */
    public String singularRu() { return singularRu; }
}
