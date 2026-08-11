package org.toltec.render;

import org.toltec.engine.GpuAcceleration;
import org.toltec.engine.TileGameEngine;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *
 * <h3>No-crop loading ({@code ...NoTrim} / {@code ...Grid...})</h3>
 * {@link #loadImage}/{@link #loadImageTrimAlpha}/{@link #loadAnimation}/
 * {@link #loadAnimationTrimAlpha} and their resource counterparts all crop
 * their result to the tight bounding box of actual content, which is
 * exactly right when the goal is to strip incidental padding. It's the
 * wrong tool, though, whenever something downstream anchors the image by a
 * size- or position-derived offset (see {@code sizeCells} on {@link
 * org.toltec.render.ObjectTextureConfig} and {@link org.toltec.render.GraphicObject#footprintCols}):
 * a cropped frame's bounding box moves around per image depending on how
 * much incidental padding it happened to have, so a supposedly consistent
 * offset ends up landing differently each time. {@link #loadImageNoTrim}
 * (single frame) and {@link #loadAnimationGrid}/{@link #loadAnimationGridTrimAlpha}
 * (sheet → even N-row × M-column grid, auto-detected the same way the
 * trimming loaders find their separators, but never cropped per frame)
 * keep every loaded frame's native size and anchor point intact instead.
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
        assets.put(name, accelerate(bgColor != null ? trim(raw, bgColor) : toRGB(raw)));
    }

    /**
     * Load a single image from a classpath resource.
     */
    public void loadImageResource(String name, String resource, Color bgColor) throws IOException {
        InputStream is = Objects.requireNonNull(
                getClass().getResourceAsStream(resource), "Resource not found: " + resource);
        BufferedImage raw = ImageIO.read(is);
        if (raw == null) throw new IOException("Cannot decode resource: " + resource);
        assets.put(name, accelerate(bgColor != null ? trim(raw, bgColor) : toRGB(raw)));
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
        assets.put(name, accelerate(trimTransparent(toRGB(raw))));
    }

    /**
     * Filesystem counterpart of {@link #loadImageResourceTrimAlpha} — same trim-by-
     * transparency behaviour, but reads from an arbitrary {@link Path} on disk
     * instead of a classpath resource. Used by the editor tooling (and any other
     * caller working with loose files outside the packaged classpath, e.g. a
     * live preview reading straight from a user's working directory).
     */
    public void loadImageTrimAlpha(String name, Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            BufferedImage raw = ImageIO.read(is);
            if (raw == null) throw new IOException("Cannot decode image: " + path);
            assets.put(name, accelerate(trimTransparent(toRGB(raw))));
        }
    }

    /**
     * Load a single image from a file path WITHOUT any trimming or cropping —
     * the full canvas is kept exactly as authored, at its native size.
     * <p>
     * Unlike {@link #loadImage}/{@link #loadImageTrimAlpha}, this never
     * changes the image's bounding box. That matters for anything whose
     * on-screen vertical anchor is computed from a per-object "size" (see
     * {@code sizeCells} on {@link org.toltec.render.ObjectTextureConfig} and
     * {@link org.toltec.render.GraphicObject#footprintCols}): a trimmed frame's
     * bounding box shifts around depending on how much transparent padding
     * the source file happened to have, so what's meant to be a consistent,
     * size-proportional drop ends up landing differently per image. Keeping
     * the untouched canvas means every frame anchors the same way.
     */
    public void loadImageNoTrim(String name, String path) throws IOException {
        BufferedImage raw = ImageIO.read(new File(path));
        if (raw == null) throw new IOException("Cannot read image: " + path);
        assets.put(name, accelerate(toRGB(raw)));
    }

    /** Classpath counterpart of {@link #loadImageNoTrim}. */
    public void loadImageResourceNoTrim(String name, String resource) throws IOException {
        InputStream is = Objects.requireNonNull(
                getClass().getResourceAsStream(resource), "Resource not found: " + resource);
        BufferedImage raw = ImageIO.read(is);
        if (raw == null) throw new IOException("Cannot decode resource: " + resource);
        assets.put(name, accelerate(toRGB(raw)));
    }

    /**
     * Load an animation sheet from a file, sliced into an even grid of equal-
     * size rectangles — rows/columns are auto-detected by finding fully
     * {@code bgColor} rows/columns (same rule {@link #extractFrames} uses to
     * find separators), but unlike {@link #loadAnimation}, each resulting
     * frame is the full, uncropped grid cell rather than the tight bounding
     * box of its own non-background pixels — see {@link #loadImageNoTrim}
     * for why that consistency matters.
     *
     * @return number of frames found (rows × cols)
     */
    public int loadAnimationGrid(String name, String path, Color bgColor) throws IOException {
        BufferedImage sheet = toRGB(readOrThrow(path));
        return loadGrid(name, sheet, detectGridByColor(sheet, bgColor));
    }

    /** Classpath counterpart of {@link #loadAnimationGrid(String, String, Color)}. */
    public int loadAnimationGridResource(String name, String resource, Color bgColor) throws IOException {
        BufferedImage sheet = toRGB(readResourceOrThrow(resource));
        return loadGrid(name, sheet, detectGridByColor(sheet, bgColor));
    }

    /**
     * Same idea as {@link #loadAnimationGrid(String, String, Color)}, but for
     * art with real alpha transparency instead of a chroma-key colour — rows/
     * columns are separators when every pixel in them is fully transparent
     * (see {@link #loadAnimationTrimAlpha}), but slicing is still an even
     * grid, never a per-frame crop.
     *
     * @return number of frames found (rows × cols)
     */
    public int loadAnimationGridTrimAlpha(String name, String path) throws IOException {
        BufferedImage sheet = toRGB(readOrThrow(path));
        return loadGrid(name, sheet, detectGridByAlpha(sheet));
    }

    /** Classpath counterpart of {@link #loadAnimationGridTrimAlpha}. */
    public int loadAnimationGridResourceTrimAlpha(String name, String resource) throws IOException {
        BufferedImage sheet = toRGB(readResourceOrThrow(resource));
        return loadGrid(name, sheet, detectGridByAlpha(sheet));
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
        return storeFrames(name, extractFrames(sheet, bgColor));
    }

    /**
     * Same idea as {@link #loadAnimation(String, String, Color)}, but for art
     * with real alpha transparency instead of a chroma-key colour: a row or
     * column separates frames when every pixel in it is fully transparent,
     * rather than when every pixel matches a given colour. No {@code bgColor}
     * needed — handy for sheets exported straight from an editor with a
     * transparent canvas.
     *
     * @return number of frames found
     */
    public int loadAnimationTrimAlpha(String name, String path) throws IOException {
        BufferedImage sheet = ImageIO.read(new File(path));
        if (sheet == null) throw new IOException("Cannot read image: " + path);
        return storeFrames(name, extractFramesByAlpha(toRGB(sheet)));
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
        return storeFrames(name, extractFrames(sheet, bgColor));
    }

    /** Classpath counterpart of {@link #loadAnimationTrimAlpha} — see there for the slicing rule. */
    public int loadAnimationResourceTrimAlpha(String name, String resource) throws IOException {
        InputStream is = Objects.requireNonNull(
                getClass().getResourceAsStream(resource), "Resource not found: " + resource);
        BufferedImage sheet = ImageIO.read(is);
        if (sheet == null) throw new IOException("Cannot decode resource: " + resource);
        return storeFrames(name, extractFramesByAlpha(toRGB(sheet)));
    }

    /**
     * Store a pre-built image directly. Note the image is copied into a
     * GPU-friendly managed image (see {@link #accelerate}) rather than
     * stored by reference — mutating the {@code BufferedImage} you passed in
     * afterwards will not affect what's drawn.
     */
    public void put(String name, BufferedImage image) {
        assets.put(name, accelerate(image));
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

    private int storeFrames(String baseName, List<BufferedImage> frames) {
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

            assets.put(baseName + "[" + i + "]", accelerate(stored));
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

    /**
     * Same idea as {@link #extractFrames}, but a row/column is a separator
     * when every pixel in it is fully transparent (alpha == 0) instead of
     * matching a solid background colour — see {@link #loadAnimationTrimAlpha}.
     */
    private List<BufferedImage> extractFramesByAlpha(BufferedImage sheet) {
        int w = sheet.getWidth(), h = sheet.getHeight();

        List<Integer> rowSeps = new ArrayList<>();
        rowSeps.add(-1);
        for (int y = 0; y < h; y++) {
            if (isRowTransparent(sheet, y)) rowSeps.add(y);
        }
        rowSeps.add(h);

        List<Integer> colSeps = new ArrayList<>();
        colSeps.add(-1);
        for (int x = 0; x < w; x++) {
            if (isColTransparent(sheet, x)) colSeps.add(x);
        }
        colSeps.add(w);

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
                if (hasOpaquePixel(cell)) {
                    frames.add(trimTransparent(cell));
                }
            }
        }
        return frames;
    }

    private boolean isRowTransparent(BufferedImage img, int y) {
        for (int x = 0; x < img.getWidth(); x++) {
            if ((img.getRGB(x, y) >>> 24) != 0) return false;
        }
        return true;
    }

    private boolean isColTransparent(BufferedImage img, int x) {
        for (int y = 0; y < img.getHeight(); y++) {
            if ((img.getRGB(x, y) >>> 24) != 0) return false;
        }
        return true;
    }

    // ── Grid slicing (no crop) ──────────────────────────────────────────────

    private static BufferedImage readOrThrow(String path) throws IOException {
        BufferedImage img = ImageIO.read(new File(path));
        if (img == null) throw new IOException("Cannot read image: " + path);
        return img;
    }

    private BufferedImage readResourceOrThrow(String resource) throws IOException {
        InputStream is = Objects.requireNonNull(
                getClass().getResourceAsStream(resource), "Resource not found: " + resource);
        BufferedImage img = ImageIO.read(is);
        if (img == null) throw new IOException("Cannot decode resource: " + resource);
        return img;
    }

    private int loadGrid(String baseName, BufferedImage sheet, int[] rowsCols) {
        return storeFrames(baseName, sliceGridEven(sheet, rowsCols[0], rowsCols[1]));
    }

    /**
     * Counts how many rows and columns of content a sheet has, using the same
     * "row/column is a separator when every pixel in it is fully transparent"
     * rule as {@link #extractFramesByAlpha} — but only to COUNT the grid's
     * dimensions from where the separators sit, not to size or crop each
     * frame (see {@link #sliceGridEven}, which does the actual slicing as an
     * even grid instead).
     *
     * @return {@code {rows, cols}}, each {@code >= 1}
     */
    private int[] detectGridByAlpha(BufferedImage sheet) {
        int w = sheet.getWidth(), h = sheet.getHeight();

        List<Integer> rowSeps = new ArrayList<>();
        rowSeps.add(-1);
        for (int y = 0; y < h; y++) if (isRowTransparent(sheet, y)) rowSeps.add(y);
        rowSeps.add(h);

        List<Integer> colSeps = new ArrayList<>();
        colSeps.add(-1);
        for (int x = 0; x < w; x++) if (isColTransparent(sheet, x)) colSeps.add(x);
        colSeps.add(w);

        return new int[]{countBands(rowSeps), countBands(colSeps)};
    }

    /** Same idea as {@link #detectGridByAlpha}, but a row/column is a separator when it matches {@code bgColor} instead of being transparent. */
    private int[] detectGridByColor(BufferedImage sheet, Color bgColor) {
        int bgRGB = bgColor.getRGB() & 0xFFFFFF;
        int w = sheet.getWidth(), h = sheet.getHeight();

        List<Integer> rowSeps = new ArrayList<>();
        rowSeps.add(-1);
        for (int y = 0; y < h; y++) if (isRowBackground(sheet, y, bgRGB)) rowSeps.add(y);
        rowSeps.add(h);

        List<Integer> colSeps = new ArrayList<>();
        colSeps.add(-1);
        for (int x = 0; x < w; x++) if (isColBackground(sheet, x, bgRGB)) colSeps.add(x);
        colSeps.add(w);

        return new int[]{countBands(rowSeps), countBands(colSeps)};
    }

    /** Counts non-empty bands between consecutive separators (a "row"/"column" of the grid). */
    private static int countBands(List<Integer> seps) {
        int count = 0;
        for (int i = 0; i < seps.size() - 1; i++) {
            if (seps.get(i + 1) - seps.get(i) - 1 > 0) count++;
        }
        return Math.max(1, count);
    }

    /**
     * Slices {@code sheet} into {@code rows} × {@code cols} EQUAL-size
     * rectangles — straight division of the sheet's pixel dimensions, no
     * cropping and no per-frame trimming. Every resulting frame is exactly
     * the same size, so its anchor point never shifts between frames —
     * unlike {@link #extractFrames}/{@link #extractFramesByAlpha}, which
     * crop each frame down to its own opaque/non-background bounding box.
     */
    private List<BufferedImage> sliceGridEven(BufferedImage sheet, int rows, int cols) {
        List<BufferedImage> out = new ArrayList<>();
        int cw = sheet.getWidth()  / Math.max(1, cols);
        int ch = sheet.getHeight() / Math.max(1, rows);
        if (cw <= 0 || ch <= 0) return out;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                out.add(sheet.getSubimage(c * cw, r * ch, cw, ch));
            }
        }
        return out;
    }

    private boolean hasOpaquePixel(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
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

    /**
     * Copies {@code img} into a "managed" image tied to the default
     * screen's {@link GraphicsConfiguration} — the only kind of image
     * Java2D's hardware pipelines (OpenGL/Direct3D, see
     * {@link GpuAcceleration}) can actually blit with the GPU.
     * <p>
     * Every helper above ({@code trim}, {@code toRGB}, {@code ImageIO.read}
     * itself, {@code getSubimage}, …) produces a plain, unmanaged
     * {@code BufferedImage}. That's fine for the pixel-level scanning they
     * do (getRGB/trimming), but an unmanaged image is <em>never</em>
     * GPU-accelerated for {@code drawImage} no matter which pipeline is
     * active — Java2D can only hand a blit to the GPU when both the source
     * and destination are managed/compatible images. Since every sprite is
     * drawn hundreds of times a frame onto the engine's backbuffer (which
     * {@link TileGameEngine#draw} now also allocates as a compatible
     * image), doing this one-time copy at load time is what actually lets
     * that later per-frame blit run on the GPU instead of being silently
     * rasterized on the CPU regardless of the pipeline flags.
     */
    private static BufferedImage accelerate(BufferedImage img) {
        try {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            BufferedImage managed = gc.createCompatibleImage(
                    Math.max(1, img.getWidth()), Math.max(1, img.getHeight()), Transparency.TRANSLUCENT);
            Graphics2D g = managed.createGraphics();
            try {
                g.drawImage(img, 0, 0, null);
            } finally {
                g.dispose();
            }
            return managed;
        } catch (HeadlessException e) {
            return img; // no display available (headless run/tests) — keep the plain image
        }
    }
}
