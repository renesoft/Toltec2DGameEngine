package org.toltec.editor.preview;

import org.toltec.unit.Direction8;
import org.toltec.render.GraphicObject;
import org.toltec.render.ObjectTextureConfig;
import org.toltec.render.TileTextureConfig;
import org.toltec.unit.Unit;
import org.toltec.unit.UnitAnimationConfig;
import org.toltec.editor.io.EditorPaths;
import org.toltec.editor.model.ClipGroup;
import org.toltec.editor.model.ClipParams;
import org.toltec.editor.model.EditableObject;
import org.toltec.editor.util.SheetSlicer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns the in-memory {@link EditableObject} into a tiny temp .ini — using
 * each dropped frame's <em>original, absolute</em> source path, so nothing
 * needs to be copied just to preview it — and reloads it through the real
 * {@code TileTextureConfig}/{@code UnitAnimationConfig}/{@code
 * ObjectTextureConfig} loaders, so what you see in the preview is exactly
 * what {@code ObjectRepository.save} will (once it copies the files into the
 * object's own folder and writes its permanent .ini) persist.
 * <p>
 * Every refresh uses a fresh generation-suffixed key internally (see {@code
 * generation}) so the loaders' own "already loaded, skip" dedup never serves
 * a stale image back after the same slot's source file changes — the
 * previewEngine's {@code AssetStorage} is long-lived (can't be swapped out
 * from under the running Swing/AWT render thread), so avoiding key reuse is
 * what keeps a re-drag actually visible.
 */
public class PreviewService {

    private final PreviewEngine engine;
    private final Path tempIni;
    private final AtomicInteger generation = new AtomicInteger();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PreviewRefresh");
        t.setDaemon(true);
        return t;
    });

    public PreviewService(PreviewEngine engine) {
        this.engine = engine;
        this.tempIni = EditorPaths.previewScratchDir().resolve("preview.ini");
    }

    /** Schedules a rebuild on a background thread — safe to call as often as you like, e.g. on every drop. */
    public void refreshAsync(EditableObject obj) {
        worker.submit(() -> {
            try {
                refreshNow(obj);
            } catch (Exception e) {
                System.err.println("Live preview refresh failed: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private void refreshNow(EditableObject obj) throws IOException {
        switch (obj.category) {
            case FLOOR -> refreshFloor(obj);
            case UNIT -> refreshUnit(obj);
            case OBJECT -> refreshObject(obj);
        }
    }

    // =========================================================================
    // Floor
    // =========================================================================

    private void refreshFloor(EditableObject obj) throws IOException {
        String type = "preview_" + generation.incrementAndGet();
        StringBuilder sb = new StringBuilder();
        for (String dir : List.of("N", "E", "S", "W")) {
            File f = obj.floorImages.get(dir);
            if (f != null && f.isFile()) {
                sb.append("img[").append(type).append("][").append(dir).append("] = ")
                        .append(f.getAbsolutePath()).append('\n');
            }
        }
        sb.append("walkable[").append(type).append("] = ").append(obj.walkable).append('\n');
        sb.append("speedMultiplier[").append(type).append("] = ").append(num(obj.speedMultiplier)).append('\n');
        sb.append("damagePerSecond[").append(type).append("] = ").append(num(obj.damagePerSecond)).append('\n');
        Files.writeString(tempIni, sb.toString(), StandardCharsets.UTF_8);

        TileTextureConfig cfg = new TileTextureConfig(engine.assets);
        cfg.load(tempIni);
        if (cfg.has(type)) {
            engine.showFloorTiling(cfg, type);
        } else {
            engine.restoreNeutralGround();
        }
    }

    // =========================================================================
    // Unit
    // =========================================================================

    private void refreshUnit(EditableObject obj) throws IOException {
        int gen = generation.incrementAndGet();
        String g = obj.curGender.key();
        String w = sanitize(obj.curWeapon) + "_" + gen;
        String st = obj.curStatus.key();

        ClipGroup group = obj.currentUnitClipGroup();

        StringBuilder sb = new StringBuilder();
        for (Direction8 d : Direction8.values()) {
            writeFrameLine(sb, group.peek(d), "[" + g + "][" + w + "][" + st + "][" + d.key() + "]");
        }
        writeParamLines(sb, group.params, "[" + g + "][" + w + "][" + st + "][*]");
        sb.append("hitChance[*][*][*][*]             = ").append(num(obj.hitChance)).append('\n');
        sb.append("dodgeChance[*][*][*][*]           = ").append(num(obj.dodgeChance)).append('\n');
        sb.append("blockChance[*][*][*][*]           = ").append(num(obj.blockChance)).append('\n');
        sb.append("attackInterruptChance[*][*][*][*] = ").append(num(obj.attackInterruptChance)).append('\n');
        sb.append("fitToCell[*][*][*][*]             = ").append(obj.fitToCell).append('\n');
        sb.append("scale[*][*][*][*]                 = ").append(num(obj.fitScale)).append('\n');
        sb.append("sizeCells[*][*][*][*]             = ").append(obj.sizeCells).append('\n');
        Files.writeString(tempIni, sb.toString(), StandardCharsets.UTF_8);

        UnitAnimationConfig cfg = new UnitAnimationConfig(engine.assets);
        cfg.load(tempIni);

        Unit unit = new Unit(cfg, obj.curGender, w);
        unit.setStatus(obj.curStatus);
        unit.setDirection(obj.curDirection);
        unit.setIsometricType();
        unit.hitChance = obj.hitChance;
        unit.dodgeChance = obj.dodgeChance;
        unit.blockChance = obj.blockChance;
        unit.attackInterruptChance = obj.attackInterruptChance;
        unit.fitToCell = obj.fitToCell;
        unit.fitScale = obj.fitScale;
        engine.showUnit(unit);
    }

    // =========================================================================
    // Object
    // =========================================================================

    private void refreshObject(EditableObject obj) throws IOException {
        int gen = generation.incrementAndGet();
        String st = sanitize(obj.curState) + "_" + gen;

        ClipGroup group = obj.currentObjectClipGroup();

        StringBuilder sb = new StringBuilder();
        if (obj.omnidirectional) {
            writeFrameLine(sb, group.peek(Direction8.S), "[" + st + "][*]");
        } else {
            for (Direction8 d : Direction8.values()) {
                writeFrameLine(sb, group.peek(d), "[" + st + "][" + d.key() + "]");
            }
        }
        writeParamLines(sb, group.params, "[" + st + "][*]");

        sb.append("collision  = ").append(obj.objCollision).append('\n');
        sb.append("layer      = ").append(obj.objLayer).append('\n');
        sb.append("isometric  = ").append(obj.isometric).append('\n');
        sb.append("drawWidth  = ").append(obj.drawWidth).append('\n');
        sb.append("drawHeight = ").append(obj.drawHeight).append('\n');
        sb.append("fitToCell  = ").append(obj.fitToCell).append('\n');
        sb.append("fitScale   = ").append(num(obj.fitScale)).append('\n');
        sb.append("xOffset    = ").append(obj.xOffset).append('\n');
        sb.append("yOffset    = ").append(obj.yOffset).append('\n');
        sb.append("sizeCols   = ").append(obj.sizeCols).append('\n');
        sb.append("sizeRows   = ").append(obj.sizeRows).append('\n');
        Files.writeString(tempIni, sb.toString(), StandardCharsets.UTF_8);

        ObjectTextureConfig cfg = new ObjectTextureConfig(engine.assets);
        cfg.load(tempIni);

        var clip = cfg.resolve(st, obj.curDirection);
        GraphicObject preview = clip != null ? new AnimatedPreviewObject(clip) : new GraphicObject("");
        cfg.applyTo(preview);
        engine.showObject(preview);
    }

    // =========================================================================
    // Shared line-writing (mirrors IniFormat, but against absolute source paths)
    // =========================================================================

    private static void writeFrameLine(StringBuilder sb, List<File> frames, String brackets) {
        if (frames.isEmpty()) return;
        boolean asStatic = frames.size() == 1 && !SheetSlicer.looksLikeMultiFrameSheet(frames.get(0));
        String directive = asStatic ? "img" : "anim";
        sb.append(directive).append(brackets).append(" = ");
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(frames.get(i).getAbsolutePath());
        }
        sb.append('\n');
    }

    private static void writeParamLines(StringBuilder sb, ClipParams p, String brackets) {
        if (p.durationMs != null) sb.append("duration").append(brackets).append(" = ").append(p.durationMs).append('\n');
        if (p.ticksPerFrame != null) sb.append("speed").append(brackets).append(" = ").append(p.ticksPerFrame).append('\n');
        if (p.loop != null) sb.append("loop").append(brackets).append(" = ").append(p.loop).append('\n');
        if (p.reverse) sb.append("order").append(brackets).append(" = reverse\n");
        if (p.scale != null) sb.append("scale").append(brackets).append(" = ").append(num(p.scale)).append('\n');
    }

    private static String num(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return String.format(Locale.ROOT, "%.1f", d);
        return String.valueOf(d);
    }

    private static String sanitize(String s) {
        return s == null ? "x" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
    }
}
