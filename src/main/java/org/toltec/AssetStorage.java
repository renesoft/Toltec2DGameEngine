package org.toltec;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * Central repository for game images and animation frames.
 *
 * <h3>Single image</h3>
 * <pre>
 *   assets.loadImage("tree", "res/tree.png", Color.MAGENTA);
 *   // → stored as "tree", background trimmed
 * </pre>
 *
 * <h3>Animation sheet</h3>
 * The sheet must contain frames arranged in a rectangular grid,
 * separated by solid rows/columns of the background colour.
 * <pre>
 *   assets.loadAnimation("walk", "res/walk_sheet.png", Color.MAGENTA);
 *   // → stored as "walk[0]", "walk[1]", …
 *   int n = assets.getFrameCount("walk"); // total frames
 * </pre>
 */
public class AssetStorage {

    private final Map<String, BufferedImage> assets = new HashMap<>();

    // =========================================================================
    // Public API – loading
    // =========================================================================

    /**
     * Load a single image from a file path, trim its background and store it.
     *
     * @param name    retrieval key
     * @param path    file-system path to the image
     * @param bgColor background colour to trim; pass {@code null} to skip trimming
     */
    public void loadImage(String name, String path, Color bgColor) throws IOException {
        BufferedImage raw = ImageIO.read(new File(path));
        if (raw == null) throw new IOException("Cannot read image: " + path);
        assets.put(name, bgColor != null ? trim(raw, bgColor) : toRGB(raw));
    }

    /**
     * Load a single image from a classpath resource.
     */
    public void loadImageResource(String name, String resource, Color bgColor) throws IOException {
        InputStream is = Objects.requireNonNull(
                getClass().getResourceAsStream(resource), "Resource not found: " + resource);
        BufferedImage raw = ImageIO.read(is);
        if (raw == null) throw new IOException("Cannot decode resource: " + resource);
        assets.put(name, bgColor != null ? trim(raw, bgColor) : toRGB(raw));
    }

    /**
     * Load a single image from a classpath resource and trim away any fully
     * transparent border (rather than a solid background colour — see
     * {@link #loadImageResource}). Use this for art that's already saved
     * with a transparent background and possibly extra padding around the
     * actual artwork (e.g. a tall canvas with an isometric diamond tile
     * sitting at the bottom of it) — without trimming, the padding gets
     * counted as part of the image's size, so anything that scales the
     * image to a fixed draw size (like a floor tile forced to cellWidth ×
     * cellHeight) squashes the real artwork down small and shifts it off
     * from where it should sit.
     */
    public void loadImageResourceTrimAlpha(String name, String resource) throws IOException {
        InputStream is = Objects.requireNonNull(
                getClass().getResourceAsStream(resource), "Resource not found: " + resource);
        BufferedImage raw = ImageIO.read(is);
        if (raw == null) throw new IOException("Cannot decode resource: " + resource);
        assets.put(name, trimTransparent(toRGB(raw)));
    }

    /**
     * Load an animation sheet from a file.
     * Frames are identified by finding all rows and columns that consist entirely
     * of the background colour; the remaining rectangular cells are extracted,
     * trimmed and stored as {@code name[0]}, {@code name[1]}, …
     *
     * @return number of frames found
     */
    public int loadAnimation(String name, String path, Color bgColor) throws IOException {
        BufferedImage sheet = ImageIO.read(new File(path));
        if (sheet == null) throw new IOException("Cannot read image: " + path);
        return storeFrames(name, sheet, bgColor);
    }

    /**
     * Load an animation sheet from a classpath resource.
     *
     * @return number of frames found
     */
    public int loadAnimationResource(String name, String resource, Color bgColor) throws IOException {
        InputStream is = Objects.requireNonNull(
                getClass().getResourceAsStream(resource), "Resource not found: " + resource);
        BufferedImage sheet = ImageIO.read(is);
        if (sheet == null) throw new IOException("Cannot decode resource: " + resource);
        return storeFrames(name, sheet, bgColor);
    }

    /**
     * Store a pre-built image directly.
     */
    public void put(String name, BufferedImage image) {
        assets.put(name, image);
    }

    // =========================================================================
    // Public API – retrieval
    // =========================================================================

    /** @return the stored image, or {@code null} if not found. */
    public BufferedImage get(String name) {
        return assets.get(name);
    }

    public boolean has(String name) {
        return assets.containsKey(name);
    }

    public int getWidth(String name) {
        BufferedImage i = assets.get(name);
        return i == null ? 0 : i.getWidth();
    }

    public int getHeight(String name) {
        BufferedImage i = assets.get(name);
        return i == null ? 0 : i.getHeight();
    }

    /**
     * Number of animation frames stored under {@code baseName}.
     * Returns 0 if none exist.
     */
    public int getFrameCount(String baseName) {
        int count = 0;
        while (assets.containsKey(baseName + "[" + count + "]")) count++;
        return count;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private int storeFrames(String baseName, BufferedImage sheet, Color bgColor) {
        List<BufferedImage> frames = extractFrames(sheet, bgColor);
        if (frames.isEmpty()) return 0;

        // 1. Находим максимальные ширину и высоту
        int maxW = 0, maxH = 0;
        for (BufferedImage f : frames) {
            maxW = Math.max(maxW, f.getWidth());
            maxH = Math.max(maxH, f.getHeight());
        }

        // 2. Нормализуем каждый фрейм к одному размеру.
        //    Важно: индекс кадра в имени ассета ("baseName[i]") всегда i —
        //    раньше кадры, которым требовался паддинг, попадали под
        //    ПЕРЕВЁРНУТЫЙ индекс (frames.size()-1-i), а кадры без паддинга —
        //    под обычный, из-за чего порядок кадров перемешивался, стоило
        //    хотя бы одному кадру отличаться размером от остальных.
        for (int i = 0; i < frames.size(); i++) {
            BufferedImage src = frames.get(i);
            int w = src.getWidth();
            int h = src.getHeight();

            BufferedImage stored;
            if (w == maxW && h == maxH) {
                stored = src;
            } else {
                BufferedImage padded = new BufferedImage(
                        maxW, maxH, BufferedImage.TYPE_INT_ARGB);

                Graphics2D g = padded.createGraphics();
                try {
                    // По X центрируем, по Y прижимаем к низу ("ноги" на
                    // одной высоте у всех кадров, а не "прыгают" при смене
                    // кадра из-за паддинга по центру).
                    int dx = (maxW - w) / 2;
                    int dy = maxH - h;
                    g.drawImage(src, dx, dy, null);
                } finally {
                    g.dispose();
                }
                stored = padded;
            }

            assets.put(baseName + "[" + i + "]", stored);
            System.out.println(baseName + "[" + i + "]:" + maxW + "x" + maxH
                    + " (source was " + w + "x" + h + ")");
        }

        return frames.size();
    }

    /**
     * Split a sprite sheet into individual frames.
     *
     * Algorithm:
     *  1. Scan every row; mark it a "separator" if all pixels match bgColor.
     *  2. Scan every column the same way.
     *  3. Use separator rows/columns to delimit a grid of cells.
     *  4. Discard empty cells; trim and keep the rest.
     */
    private List<BufferedImage> extractFrames(BufferedImage sheet, Color bgColor) {
        int bgRGB = bgColor.getRGB() & 0xFFFFFF;
        int w = sheet.getWidth(), h = sheet.getHeight();

        // Gather separator rows  ------------------------------------------------
        List<Integer> rowSeps = new ArrayList<>();
        rowSeps.add(-1);
        for (int y = 0; y < h; y++) {
            if (isRowBackground(sheet, y, bgRGB)) rowSeps.add(y);
        }
        rowSeps.add(h);

        // Gather separator columns  ---------------------------------------------
        List<Integer> colSeps = new ArrayList<>();
        colSeps.add(-1);
        for (int x = 0; x < w; x++) {
            if (isColBackground(sheet, x, bgRGB)) colSeps.add(x);
        }
        colSeps.add(w);

        // Extract grid cells  ---------------------------------------------------
        List<BufferedImage> frames = new ArrayList<>();
        for (int ri = 0; ri < rowSeps.size() - 1; ri++) {
            int y1 = rowSeps.get(ri) + 1;
            int y2 = rowSeps.get(ri + 1);
            if (y2 - y1 <= 0) continue;

            for (int ci = 0; ci < colSeps.size() - 1; ci++) {
                int x1 = colSeps.get(ci) + 1;
                int x2 = colSeps.get(ci + 1);
                if (x2 - x1 <= 0) continue;

                BufferedImage cell = sheet.getSubimage(x1, y1, x2 - x1, y2 - y1);
                if (hasNonBackground(cell, bgRGB)) {
                    frames.add(trim(cell, bgColor));
                }
            }
        }
        return frames;
    }

    // ── Trim helpers ──────────────────────────────────────────────────────────

    /**
     * Remove solid background border from all four sides of an image.
     * Comparison is done on the lower 24 bits (RGB, ignoring alpha).
     */
    private BufferedImage trim(BufferedImage src, Color bgColor) {
        int bgRGB = bgColor.getRGB() & 0xFFFFFF;
        int w = src.getWidth(), h = src.getHeight();
        int minX = w, maxX = -1, minY = h, maxY = -1;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((src.getRGB(x, y) & 0xFFFFFF) != bgRGB) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < 0) return src; // image is entirely background

        // Use TYPE_INT_ARGB so transparency survives sub-imaging
        BufferedImage result = new BufferedImage(maxX - minX + 1, maxY - minY + 1,
                                                 BufferedImage.TYPE_INT_ARGB);
        result.getGraphics().drawImage(
                src.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1),
                0, 0, null);
        return result;
    }

    /**
     * Same idea as {@link #trim}, but treats any fully-transparent pixel
     * (alpha == 0) as "background" instead of matching a solid colour —
     * for art authored with real transparency rather than a chroma-key colour.
     */
    private BufferedImage trimTransparent(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int minX = w, maxX = -1, minY = h, maxY = -1;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((src.getRGB(x, y) >>> 24) != 0) { // alpha channel non-zero => not background
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        if (maxX < 0) return src; // image is entirely transparent

        BufferedImage result = new BufferedImage(maxX - minX + 1, maxY - minY + 1,
                                                 BufferedImage.TYPE_INT_ARGB);
        result.getGraphics().drawImage(
                src.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1),
                0, 0, null);
        return result;
    }

    private boolean isRowBackground(BufferedImage img, int y, int bgRGB) {
        for (int x = 0; x < img.getWidth(); x++)
            if ((img.getRGB(x, y) & 0xFFFFFF) != bgRGB) return false;
        return true;
    }

    private boolean isColBackground(BufferedImage img, int x, int bgRGB) {
        for (int y = 0; y < img.getHeight(); y++)
            if ((img.getRGB(x, y) & 0xFFFFFF) != bgRGB) return false;
        return true;
    }

    private boolean hasNonBackground(BufferedImage img, int bgRGB) {
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) & 0xFFFFFF) != bgRGB) return true;
        return false;
    }

    /** Convert any format to TYPE_INT_RGB for uniform pixel access. */
    private BufferedImage toRGB(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(),
                                              BufferedImage.TYPE_INT_ARGB);
        dst.getGraphics().drawImage(src, 0, 0, null);
        return dst;
    }
}
