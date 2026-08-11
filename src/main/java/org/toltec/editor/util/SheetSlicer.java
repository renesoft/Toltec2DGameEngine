package org.toltec.editor.util;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A single dropped image could be either an already-final static pose, or a
 * whole multi-frame animation sheet on one transparent canvas — the editor
 * can't tell just from the drop itself, so it peeks at the pixels. This
 * mirrors {@code AssetStorage}'s own alpha-based sheet slicer (a row/column
 * is a separator when every pixel in it is fully transparent) closely
 * enough to answer "would this actually produce more than one frame" without
 * needing a live {@code AssetStorage} to ask.
 * <p>
 * Used only to pick which ini directive the writer emits for a one-file slot
 * ({@code img[...]}, never sliced, vs {@code anim[...]}, sliced by the real
 * engine loader at load time) — the actual slicing always happens through
 * the real loader, so this only ever needs to be roughly right.
 */
public final class SheetSlicer {

    private SheetSlicer() {}

    /** @return true if slicing {@code file} by fully-transparent separator rows/columns would yield 2+ frames. */
    public static boolean looksLikeMultiFrameSheet(File file) {
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) return false;
            return countFrames(img) >= 2;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static int countFrames(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();

        List<Integer> rowSeps = new ArrayList<>();
        rowSeps.add(-1);
        for (int y = 0; y < h; y++) if (isRowTransparent(img, y)) rowSeps.add(y);
        rowSeps.add(h);

        List<Integer> colSeps = new ArrayList<>();
        colSeps.add(-1);
        for (int x = 0; x < w; x++) if (isColTransparent(img, x)) colSeps.add(x);
        colSeps.add(w);

        int count = 0;
        for (int ri = 0; ri < rowSeps.size() - 1; ri++) {
            int y1 = rowSeps.get(ri) + 1, y2 = rowSeps.get(ri + 1);
            if (y2 - y1 <= 0) continue;
            for (int ci = 0; ci < colSeps.size() - 1; ci++) {
                int x1 = colSeps.get(ci) + 1, x2 = colSeps.get(ci + 1);
                if (x2 - x1 <= 0) continue;
                if (hasOpaquePixel(img, x1, y1, x2, y2)) count++;
            }
        }
        return count;
    }

    private static boolean isRowTransparent(BufferedImage img, int y) {
        for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) >>> 24) != 0) return false;
        return true;
    }

    private static boolean isColTransparent(BufferedImage img, int x) {
        for (int y = 0; y < img.getHeight(); y++) if ((img.getRGB(x, y) >>> 24) != 0) return false;
        return true;
    }

    private static boolean hasOpaquePixel(BufferedImage img, int x1, int y1, int x2, int y2) {
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                if ((img.getRGB(x, y) >>> 24) != 0) return true;
            }
        }
        return false;
    }
}
