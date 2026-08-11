package org.toltec.tools;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Небольшой автономный дебагер для подгонки картинки (или листа анимации) к
 * клетке сетки.
 * <p>
 * Не рендерит через {@code TileGameEngine} напрямую — вместо этого повторяет
 * ТУ ЖЕ САМУЮ математику позиционирования, что и {@code TileGameEngine#renderCell}
 * (ветки {@code fitToCell}/{@code drawWidth,drawHeight} и {@code obj.isIsometric()}).
 * Если формулы там когда-нибудь изменятся — поправьте и тут, иначе дебагер
 * начнёт врать.
 * <p>
 * Запуск: {@code mvn compile exec:java -Dexec.mainClass=org.toltec.tools.GridFitDebugger}
 * <p>
 * <b>Обновление:</b> идеи, опробованные здесь (footprint-зависимый отступ
 * "noTrimHalfDrop", нарезка листа на ровную сетку N×K без обрезки) теперь
 * реализованы в продакшен-коде: см. {@link org.toltec.render.GraphicObject#footprintCols}/{@link org.toltec.render.GraphicObject#footprintRows},
 * {@code sizeCells} в {@link org.toltec.render.ObjectTextureConfig} и
 * {@code AssetStorage#loadImageNoTrim}/{@code AssetStorage#loadAnimationGrid}.
 * Этот класс остаётся отдельной песочницей для визуальной отладки и в рендере
 * объектов уже не участвует.
 */
public class GridFitDebugger extends JFrame {

    // ── исходник и нарезанные кадры ─────────────────────────────────────────
    private BufferedImage original;
    private String fileName = "(картинка не загружена)";
    private final List<BufferedImage> frames = new ArrayList<>();
    private int currentFrameIdx = 0;

    private enum LoadMode { SINGLE, ANIM_AUTO, ANIM_GRID }
    private LoadMode loadMode = LoadMode.SINGLE;
    private int gridRows = 1;
    private int gridCols = 4;

    private boolean trimAlpha      = true;  // как AssetStorage.loadImageTrimAlpha — только для SINGLE
    private boolean noTrimHalfDrop = false; // выключить trim и опустить на cellHeight/2 (см. п.2 запроса)

    private Timer   animTimer;
    private boolean playing     = false;
    private int     frameDelayMs = 150;

    // ── параметры позиционирования (см. GraphicObject / TileGameEngine) ────
    private enum ViewType { ISOMETRIC, TOP_DOWN }
    private ViewType viewType       = ViewType.ISOMETRIC;
    private boolean  isometricSprite = true;
    private boolean  fitToCell      = true;
    private double   fitScale       = 1.0;
    private int      drawWidth      = -1;
    private int      drawHeight     = -1;
    private int      xOffset        = 0;
    private int      yOffset        = 0;
    private double   zoom           = 1.0;
    private int      neighborRadius = 1;

    private int cellWidth  = 128;
    private int cellHeight = 64;

    // "модель размером в N×M клеток" — см. п.3 запроса
    private int footprintCols = 1; // ширина, клеток
    private int footprintRows = 1; // длина (глубина), клеток

    private enum Bg { DARK, LIGHT, CHECKER }
    private Bg bg = Bg.CHECKER;

    private final CanvasPanel canvas      = new CanvasPanel();
    private final JTextArea   readout     = new JTextArea();
    private final JLabel      frameLabel  = new JLabel("Кадр: 0/0");
    private final JButton     playBtn     = new JButton("▶ Играть");

    public GridFitDebugger() {
        super("Grid Fit Debugger — подгонка картинки к сетке");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        add(buildToolbar(), BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.EAST);

        canvas.setDropTarget(new DropTarget(canvas, DnDConstants.ACTION_COPY, new java.awt.dnd.DropTargetAdapter() {
            @Override public void drop(DropTargetDropEvent evt) {
                try {
                    evt.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) evt.getTransferable()
                            .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) loadImage(files.get(0));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(GridFitDebugger.this, "Не удалось загрузить: " + ex.getMessage());
                }
            }
        }));

        setSize(1320, 900);
        setLocationRelativeTo(null);
        updateFrameLabel();
        updateReadout();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Тулбар
    // ═════════════════════════════════════════════════════════════════════

    private JComponent buildToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        JButton open = new JButton("Открыть картинку…");
        open.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Изображения", "png", "jpg", "jpeg", "gif", "bmp"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                loadImage(fc.getSelectedFile());
            }
        });
        tb.add(open);

        JButton exportFrames = new JButton("Сохранить кадры как PNG…");
        exportFrames.addActionListener(e -> exportFrames());
        tb.add(exportFrames);

        tb.addSeparator();
        tb.add(new JLabel("  (или перетащите файл прямо в окно)  "));
        return tb;
    }

    private void loadImage(File file) {
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) throw new IOException("формат не распознан");
            original = img;
            fileName = file.getName();
            currentFrameIdx = 0;
            rebuildFrames();
            canvas.repaint();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Не удалось загрузить картинку: " + ex.getMessage());
        }
    }

    private void exportFrames() {
        if (frames.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Сначала загрузите картинку.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Куда сохранить кадры");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        File dir = fc.getSelectedFile();
        int written = 0;
        try {
            for (int i = 0; i < frames.size(); i++) {
                File out = new File(dir, base + "_" + i + ".png");
                ImageIO.write(frames.get(i), "png", out);
                written++;
            }
            JOptionPane.showMessageDialog(this, "Сохранено кадров: " + written + " в " + dir.getAbsolutePath()
                    + "\nИх можно перетащить в редактор как обычную анимацию.");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Ошибка сохранения (" + written + " успели записать): " + ex.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Нарезка на кадры
    // ═════════════════════════════════════════════════════════════════════

    private void rebuildFrames() {
        stopPlaying();
        frames.clear();
        if (original != null) {
            switch (loadMode) {
                case SINGLE -> {
                    boolean doTrim = trimAlpha && !noTrimHalfDrop;
                    frames.add(doTrim ? trimTransparent(original) : original);
                }
                case ANIM_AUTO -> frames.addAll(extractFramesByAlphaPadded(original));
                case ANIM_GRID -> frames.addAll(sliceGrid(original, Math.max(1, gridRows), Math.max(1, gridCols)));
            }
        }
        currentFrameIdx = frames.isEmpty() ? 0 : Math.min(currentFrameIdx, frames.size() - 1);
        updateFrameLabel();
        updateReadout();
    }

    /** Текущий показываемый кадр (или {@code null}, если ничего не загружено). */
    private BufferedImage displayed() {
        if (frames.isEmpty()) return null;
        return frames.get(Math.max(0, Math.min(currentFrameIdx, frames.size() - 1)));
    }

    /** Повторяет {@code AssetStorage#trimTransparent} — обрезает полностью прозрачную рамку. */
    private static BufferedImage trimTransparent(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((src.getRGB(x, y) >>> 24) != 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return src; // полностью прозрачная — не режем
        return src.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static boolean isRowTransparent(BufferedImage img, int y) {
        int w = img.getWidth();
        for (int x = 0; x < w; x++) if ((img.getRGB(x, y) >>> 24) != 0) return false;
        return true;
    }

    private static boolean isColTransparent(BufferedImage img, int x) {
        int h = img.getHeight();
        for (int y = 0; y < h; y++) if ((img.getRGB(x, y) >>> 24) != 0) return false;
        return true;
    }

    private static boolean hasOpaquePixel(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++)
            for (int x = 0; x < img.getWidth(); x++)
                if ((img.getRGB(x, y) >>> 24) != 0) return true;
        return false;
    }

    /**
     * Повторяет {@code AssetStorage#extractFramesByAlpha} + {@code #storeFrames}:
     * режет лист по полностью прозрачным строкам/столбцам, обрезает каждый
     * найденный кадр по alpha, затем приводит все кадры к общему размеру,
     * прижимая по низу и центрируя по X (как в игре) — чтобы "ноги" не
     * прыгали при смене кадра.
     */
    private static List<BufferedImage> extractFramesByAlphaPadded(BufferedImage sheet) {
        int w = sheet.getWidth(), h = sheet.getHeight();

        List<Integer> rowSeps = new ArrayList<>();
        rowSeps.add(-1);
        for (int y = 0; y < h; y++) if (isRowTransparent(sheet, y)) rowSeps.add(y);
        rowSeps.add(h);

        List<Integer> colSeps = new ArrayList<>();
        colSeps.add(-1);
        for (int x = 0; x < w; x++) if (isColTransparent(sheet, x)) colSeps.add(x);
        colSeps.add(w);

        List<BufferedImage> raw = new ArrayList<>();
        for (int ri = 0; ri < rowSeps.size() - 1; ri++) {
            int y1 = rowSeps.get(ri) + 1, y2 = rowSeps.get(ri + 1);
            if (y2 - y1 <= 0) continue;
            for (int ci = 0; ci < colSeps.size() - 1; ci++) {
                int x1 = colSeps.get(ci) + 1, x2 = colSeps.get(ci + 1);
                if (x2 - x1 <= 0) continue;
                BufferedImage cell = sheet.getSubimage(x1, y1, x2 - x1, y2 - y1);
                if (hasOpaquePixel(cell)) raw.add(trimTransparent(cell));
            }
        }
        if (raw.isEmpty()) return raw;

        int maxW = 0, maxH = 0;
        for (BufferedImage f : raw) { maxW = Math.max(maxW, f.getWidth()); maxH = Math.max(maxH, f.getHeight()); }

        List<BufferedImage> out = new ArrayList<>();
        for (BufferedImage f : raw) {
            if (f.getWidth() == maxW && f.getHeight() == maxH) { out.add(f); continue; }
            BufferedImage padded = new BufferedImage(maxW, maxH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = padded.createGraphics();
            try {
                int dx = (maxW - f.getWidth()) / 2;
                int dy = maxH - f.getHeight(); // прижать к низу
                g.drawImage(f, dx, dy, null);
            } finally {
                g.dispose();
            }
            out.add(padded);
        }
        return out;
    }

    /**
     * Ручная нарезка листа на равные kадры по указанному числу строк/столбцов
     * — БЕЗ какой-либо дополнительной обрезки: у соседних кадров сохраняется
     * одинаковое положение "точки крепления" внутри своей ячейки листа, что
     * и требуется, когда авто-нарезка по прозрачным разрывам ошибается или
     * кадры не разделены прозрачными промежутками вовсе.
     */
    private static List<BufferedImage> sliceGrid(BufferedImage sheet, int rows, int cols) {
        List<BufferedImage> out = new ArrayList<>();
        int cw = sheet.getWidth()  / cols;
        int ch = sheet.getHeight() / rows;
        if (cw <= 0 || ch <= 0) return out;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                out.add(sheet.getSubimage(c * cw, r * ch, cw, ch));
            }
        }
        return out;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Проигрывание анимации
    // ═════════════════════════════════════════════════════════════════════

    private void togglePlay() {
        if (frames.size() < 2) { playing = false; updatePlayButton(); return; }
        playing = !playing;
        if (playing) {
            if (animTimer == null) {
                animTimer = new Timer(frameDelayMs, e -> {
                    if (frames.isEmpty()) return;
                    currentFrameIdx = (currentFrameIdx + 1) % frames.size();
                    canvas.repaint();
                    updateFrameLabel();
                });
            }
            animTimer.setDelay(frameDelayMs);
            animTimer.start();
        } else if (animTimer != null) {
            animTimer.stop();
        }
        updatePlayButton();
    }

    private void stopPlaying() {
        playing = false;
        if (animTimer != null) animTimer.stop();
        updatePlayButton();
    }

    private void updatePlayButton() {
        playBtn.setText(playing ? "⏸ Пауза" : "▶ Играть");
    }

    private void updateFrameLabel() {
        frameLabel.setText("Кадр: " + (frames.isEmpty() ? 0 : currentFrameIdx + 1) + "/" + frames.size());
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Панель управления
    // ═════════════════════════════════════════════════════════════════════

    private JComponent buildControls() {
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(new EmptyBorder(8, 8, 8, 8));
        root.setPreferredSize(new Dimension(360, 0));

        root.add(section("Вид / сетка", panel -> {
            JComboBox<ViewType> viewBox = new JComboBox<>(ViewType.values());
            viewBox.setSelectedItem(viewType);
            viewBox.addActionListener(e -> { viewType = (ViewType) viewBox.getSelectedItem(); canvas.repaint(); updateReadout(); });
            row(panel, "Проекция:", viewBox);

            row(panel, "cellWidth:", spinner(cellWidth, 8, 1024, 8, v -> { cellWidth = v; canvas.repaint(); updateReadout(); }));
            row(panel, "cellHeight:", spinner(cellHeight, 8, 1024, 8, v -> { cellHeight = v; canvas.repaint(); updateReadout(); }));
            row(panel, "Соседних клеток вокруг:", spinner(neighborRadius, 0, 4, 1, v -> { neighborRadius = v; canvas.repaint(); }));

            JComboBox<Bg> bgBox = new JComboBox<>(Bg.values());
            bgBox.setSelectedItem(bg);
            bgBox.addActionListener(e -> { bg = (Bg) bgBox.getSelectedItem(); canvas.repaint(); });
            row(panel, "Фон:", bgBox);

            JSlider zoomSlider = slider(50, 400, (int) (zoom * 100));
            zoomSlider.addChangeListener(e -> { zoom = zoomSlider.getValue() / 100.0; canvas.repaint(); updateReadout(); });
            row(panel, "Zoom:", zoomSlider);
        }));

        root.add(section("Загрузка / анимация", panel -> {
            JRadioButton single   = new JRadioButton("Один кадр", loadMode == LoadMode.SINGLE);
            JRadioButton animAuto = new JRadioButton("Анимация — авто (по прозрачным разрывам)", loadMode == LoadMode.ANIM_AUTO);
            JRadioButton animGrid = new JRadioButton("Анимация — сетка N×K (без обрезки)", loadMode == LoadMode.ANIM_GRID);
            ButtonGroup modeGroup = new ButtonGroup();
            modeGroup.add(single); modeGroup.add(animAuto); modeGroup.add(animGrid);
            single.setAlignmentX(LEFT_ALIGNMENT);
            animAuto.setAlignmentX(LEFT_ALIGNMENT);
            animGrid.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(single); panel.add(animAuto); panel.add(animGrid);

            JSpinner rowsSpinner = spinner(gridRows, 1, 64, 1, v -> { gridRows = v; if (loadMode == LoadMode.ANIM_GRID) { rebuildFrames(); canvas.repaint(); } });
            JSpinner colsSpinner = spinner(gridCols, 1, 64, 1, v -> { gridCols = v; if (loadMode == LoadMode.ANIM_GRID) { rebuildFrames(); canvas.repaint(); } });
            row(panel, "  Рядов (N):", rowsSpinner);
            row(panel, "  Столбцов (K):", colsSpinner);

            single.addActionListener(e -> { loadMode = LoadMode.SINGLE; rebuildFrames(); canvas.repaint(); });
            animAuto.addActionListener(e -> { loadMode = LoadMode.ANIM_AUTO; rebuildFrames(); canvas.repaint(); });
            animGrid.addActionListener(e -> { loadMode = LoadMode.ANIM_GRID; rebuildFrames(); canvas.repaint(); });

            panel.add(Box.createVerticalStrut(6));

            JCheckBox trim = new JCheckBox("trim alpha при загрузке (только для «Один кадр»)", trimAlpha);
            trim.addActionListener(e -> { trimAlpha = trim.isSelected(); rebuildFrames(); canvas.repaint(); });
            trim.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(trim);

            JCheckBox noTrim = new JCheckBox("без trim alpha + опустить на cellHeight/2", noTrimHalfDrop);
            noTrim.addActionListener(e -> {
                noTrimHalfDrop = noTrim.isSelected();
                trim.setEnabled(!noTrimHalfDrop);
                rebuildFrames();
                canvas.repaint();
            });
            noTrim.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(noTrim);

            panel.add(Box.createVerticalStrut(6));

            JPanel navRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            navRow.setAlignmentX(LEFT_ALIGNMENT);
            JButton prev = new JButton("◀");
            JButton next = new JButton("▶");
            prev.addActionListener(e -> { if (!frames.isEmpty()) { currentFrameIdx = (currentFrameIdx - 1 + frames.size()) % frames.size(); canvas.repaint(); updateFrameLabel(); } });
            next.addActionListener(e -> { if (!frames.isEmpty()) { currentFrameIdx = (currentFrameIdx + 1) % frames.size(); canvas.repaint(); updateFrameLabel(); } });
            playBtn.addActionListener(e -> togglePlay());
            navRow.add(prev); navRow.add(frameLabel); navRow.add(next); navRow.add(playBtn);
            panel.add(navRow);

            row(panel, "мс/кадр:", spinner(frameDelayMs, 30, 3000, 10, v -> { frameDelayMs = v; if (animTimer != null) animTimer.setDelay(v); }));
        }));

        root.add(section("Тип спрайта (как GraphicObject)", panel -> {
            JRadioButton iso = new JRadioButton("Изометрический (obj.setIsometricType()) — растёт вверх от центра клетки", isometricSprite);
            JRadioButton flat = new JRadioButton("Плоская плитка (billboard, как пол/ковёр)", !isometricSprite);
            ButtonGroup g = new ButtonGroup();
            g.add(iso); g.add(flat);
            iso.addActionListener(e -> { isometricSprite = true; canvas.repaint(); updateReadout(); });
            flat.addActionListener(e -> { isometricSprite = false; canvas.repaint(); updateReadout(); });
            iso.setAlignmentX(LEFT_ALIGNMENT);
            flat.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(iso);
            panel.add(flat);
        }));

        root.add(section("Размер (fitToCell / drawWidth,Height)", panel -> {
            JCheckBox fit = new JCheckBox("fitToCell", fitToCell);
            fit.addActionListener(e -> { fitToCell = fit.isSelected(); canvas.repaint(); updateReadout(); });
            fit.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(fit);

            JSlider fitScaleSlider = slider(10, 400, (int) (fitScale * 100));
            fitScaleSlider.addChangeListener(e -> { fitScale = fitScaleSlider.getValue() / 100.0; canvas.repaint(); updateReadout(); });
            row(panel, "fitScale:", fitScaleSlider);

            row(panel, "drawWidth (-1=natural):", spinner(drawWidth, -1, 4096, 1, v -> { drawWidth = v; canvas.repaint(); updateReadout(); }));
            row(panel, "drawHeight (-1=natural):", spinner(drawHeight, -1, 4096, 1, v -> { drawHeight = v; canvas.repaint(); updateReadout(); }));

            panel.add(Box.createVerticalStrut(6));
            JLabel fpLabel = new JLabel("Footprint модели (для fitToCell):");
            fpLabel.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(fpLabel);
            row(panel, "  ширина, клеток:", spinner(footprintCols, 1, 20, 1, v -> { footprintCols = v; canvas.repaint(); updateReadout(); }));
            row(panel, "  длина, клеток:", spinner(footprintRows, 1, 20, 1, v -> { footprintRows = v; canvas.repaint(); updateReadout(); }));
        }));

        root.add(section("Смещение (xOffset / yOffset)", panel -> {
            JSlider xs = slider(-400, 400, xOffset);
            xs.addChangeListener(e -> { xOffset = xs.getValue(); canvas.repaint(); updateReadout(); });
            row(panel, "xOffset:", xs);

            JSlider ys = slider(-400, 400, yOffset);
            ys.addChangeListener(e -> { yOffset = ys.getValue(); canvas.repaint(); updateReadout(); });
            row(panel, "yOffset:", ys);

            JButton reset = new JButton("Сбросить смещения в 0");
            reset.addActionListener(e -> { xOffset = 0; yOffset = 0; canvas.repaint(); updateReadout(); });
            reset.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(reset);
        }));

        readout.setEditable(false);
        readout.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        readout.setLineWrap(true);
        readout.setWrapStyleWord(true);
        readout.setBorder(new EmptyBorder(6, 6, 6, 6));
        JScrollPane readoutScroll = new JScrollPane(readout);
        readoutScroll.setBorder(new TitledBorder("Итог (для .ini)"));
        readoutScroll.setPreferredSize(new Dimension(340, 260));
        readoutScroll.setMaximumSize(new Dimension(2000, 300));

        JButton copy = new JButton("Скопировать в буфер");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(readout.getText()), null));
        copy.setAlignmentX(LEFT_ALIGNMENT);

        root.add(Box.createVerticalStrut(4));
        root.add(readoutScroll);
        root.add(Box.createVerticalStrut(4));
        root.add(copy);
        root.add(Box.createVerticalGlue());

        JScrollPane outer = new JScrollPane(root);
        outer.setBorder(null);
        return outer;
    }

    private interface PanelFiller { void fill(JPanel p); }

    private JPanel section(String title, PanelFiller filler) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new TitledBorder(title));
        p.setAlignmentX(LEFT_ALIGNMENT);
        filler.fill(p);
        return p;
    }

    private void row(JPanel parent, String label, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        parent.add(row);
    }

    private interface IntConsumer { void accept(int v); }

    private JSpinner spinner(int val, int min, int max, int step, IntConsumer onChange) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(val, min, max, step));
        sp.addChangeListener(e -> onChange.accept((Integer) sp.getValue()));
        return sp;
    }

    private JSlider slider(int min, int max, int val) {
        return new JSlider(min, max, val);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Расчёты (те же формулы, что и в движке)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Множитель ширины клетки для {@code fitToCell}, когда модель занимает
     * {@link #footprintCols}×{@link #footprintRows} клеток. В изометрии
     * ромб многоклеточного футпринта COLS×ROWS имеет полную ширину
     * {@code (COLS+ROWS) * cellWidth/2} (это стандартная геометрия
     * изометрической сетки — тот же принцип, что даёт ширину {@code cellWidth}
     * для обычной клетки 1×1: {@code (1+1)*cellWidth/2 = cellWidth}).
     * В top-down клетки — простые прямоугольники, там ширина считается
     * напрямую как {@code footprintCols * cellWidth}.
     */
    private double footprintWidthFactor() {
        if (viewType == ViewType.ISOMETRIC) return (footprintCols + footprintRows) / 2.0;
        return footprintCols;
    }

    private double footprintHeightFactor() {
        if (viewType == ViewType.ISOMETRIC) return (footprintCols + footprintRows) / 2.0;
        return footprintRows;
    }

    /** dw/dh по формуле TileGameEngine#renderCell, но targetW масштабирован под footprint. */
    private int[] computeDrawSize() {
        BufferedImage img = displayed();
        if (img == null) return null;
        int imgW = img.getWidth(), imgH = img.getHeight();
        int screenCw = (int) Math.round(cellWidth * zoom);
        int dw, dh;
        if (fitToCell) {
            double targetW = screenCw * footprintWidthFactor() * fitScale;
            dw = (int) Math.round(targetW);
            dh = (int) Math.round(targetW * imgH / (double) imgW);
        } else {
            double baseW = drawWidth  > 0 ? drawWidth  : imgW;
            double baseH = drawHeight > 0 ? drawHeight : imgH;
            dw = (int) Math.round(baseW * zoom);
            dh = (int) Math.round(baseH * zoom);
        }
        return new int[]{Math.max(dw, 0), Math.max(dh, 0)};
    }

    /** yOffset, который реально используется при рисовании — с поправкой "без trim + пол-клетки вниз". */
    private int effectiveYOffset() {
        return yOffset + (noTrimHalfDrop ? cellHeight / 2 : 0);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Текстовая сводка / .ini кусок
    // ═════════════════════════════════════════════════════════════════════

    private void updateReadout() {
        StringBuilder sb = new StringBuilder();
        sb.append("Файл: ").append(fileName).append('\n');
        sb.append("Режим: ").append(switch (loadMode) {
            case SINGLE -> "один кадр";
            case ANIM_AUTO -> "анимация, авто-нарезка (" + frames.size() + " кадров)";
            case ANIM_GRID -> "анимация, сетка " + gridRows + "×" + gridCols + " (" + frames.size() + " кадров)";
        }).append('\n');

        BufferedImage img = displayed();
        if (img != null) {
            sb.append("Размер текущего кадра: ").append(img.getWidth()).append('x').append(img.getHeight()).append('\n');
        }
        if (loadMode == LoadMode.ANIM_GRID && original != null) {
            if (original.getWidth() % gridCols != 0 || original.getHeight() % gridRows != 0) {
                sb.append("⚠ лист не делится нацело на ").append(gridRows).append('x').append(gridCols)
                        .append(" — остаток пикселей отброшен по правому/нижнему краю\n");
            }
        }

        int[] dwh = computeDrawSize();
        if (dwh != null) {
            sb.append("Итоговый размер на экране (zoom=").append(String.format("%.2f", zoom)).append("): ")
                    .append(dwh[0]).append('x').append(dwh[1]).append('\n');
        }
        if (footprintCols != 1 || footprintRows != 1) {
            sb.append("Footprint: ").append(footprintCols).append('x').append(footprintRows)
                    .append(" клеток → множитель ширины ").append(String.format("%.2f", footprintWidthFactor())).append('\n');
        }

        sb.append("yOffset введённый: ").append(yOffset);
        if (noTrimHalfDrop) {
            sb.append("  (+ cellHeight/2 = ").append(cellHeight / 2)
                    .append(" из-за «без trim» → эффективный yOffset = ").append(effectiveYOffset()).append(')');
        }
        sb.append('\n');

        sb.append('\n').append("--- вставить в <Имя>.ini ---\n");
        sb.append("fitToCell  = ").append(fitToCell).append('\n');
        if (fitToCell) {
            sb.append("fitScale   = ").append(String.format("%.2f", fitScale * footprintWidthFactor())).append('\n');
            if (footprintCols != 1 || footprintRows != 1) {
                sb.append("            (= fitScale ").append(String.format("%.2f", fitScale))
                        .append(" × footprint-множитель ").append(String.format("%.2f", footprintWidthFactor()))
                        .append(" — в формате .ini отдельного поля под footprint нет, множитель уже вшит в fitScale)\n");
            }
        } else {
            sb.append("drawWidth  = ").append(drawWidth).append('\n');
            sb.append("drawHeight = ").append(drawHeight).append('\n');
        }
        sb.append("xOffset    = ").append(xOffset).append('\n');
        sb.append("yOffset    = ").append(effectiveYOffset()).append('\n');
        sb.append("isometric  = ").append(isometricSprite).append('\n');
        if (noTrimHalfDrop) {
            sb.append("# картинку грузить БЕЗ trim alpha (обычным load, не loadImageTrimAlpha)\n");
        }
        if (loadMode != LoadMode.SINGLE) {
            sb.append("# анимация: anim[состояние][*] = ").append(frameFileNameHint(0)).append('|')
                    .append(frameFileNameHint(1)).append("|...  (см. «Сохранить кадры как PNG…»)\n");
        }

        readout.setText(sb.toString());
        readout.setCaretPosition(0);
    }

    private String frameFileNameHint(int i) {
        String base = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        return base + "_" + i + ".png";
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Канвас — 1:1 повторяет геометрию TileGameEngine.renderCell
    // ═════════════════════════════════════════════════════════════════════

    private class CanvasPanel extends JPanel {
        CanvasPanel() { setBackground(new Color(30, 30, 34)); }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            paintCanvasBackground(g);

            int cw = (int) Math.round(cellWidth  * zoom);
            int ch = (int) Math.round(cellHeight * zoom);
            int originX = getWidth()  / 2;
            int originY = getHeight() / 2;

            if (viewType == ViewType.ISOMETRIC) {
                paintIsometric(g, cw, ch, originX, originY);
            } else {
                paintTopDown(g, cw, ch, originX, originY);
            }

            if (displayed() == null) {
                g.setColor(Color.LIGHT_GRAY);
                g.drawString("Перетащите картинку сюда или нажмите «Открыть картинку…»", originX - 160, originY - ch);
            }
        }

        private void paintIsometric(Graphics2D g, int cw, int ch, int originX, int originY) {
            int halfW = cw / 2, halfH = ch / 2;
            int r = neighborRadius;

            for (int row = -r; row <= r; row++) {
                for (int col = -r; col <= r; col++) {
                    int sx = originX + (col - row) * halfW;
                    int sy = originY + (col + row) * halfH;
                    drawDiamond(g, sx, sy, cw, ch, col == 0 && row == 0);
                }
            }

            int sx = originX, sy = originY; // центральная клетка, col=0,row=0
            drawFootprintGuideIsometric(g, sx, sy, cw, ch);
            drawSpriteIsometric(g, sx, sy, cw, ch);
        }

        private void paintTopDown(Graphics2D g, int cw, int ch, int originX, int originY) {
            int r = neighborRadius;
            for (int row = -r; row <= r; row++) {
                for (int col = -r; col <= r; col++) {
                    int sx = originX + col * cw;
                    int sy = originY + row * ch;
                    g.setColor(col == 0 && row == 0 ? new Color(90, 90, 130) : new Color(55, 55, 65));
                    g.fillRect(sx, sy, cw, ch);
                    g.setColor(Color.GRAY);
                    g.drawRect(sx, sy, cw - 1, ch - 1);
                }
            }
            drawFootprintGuideTopDown(g, originX, originY, cw, ch);
            drawSpriteTopDown(g, originX, originY, cw, ch);
        }

        private void drawDiamond(Graphics2D g, int sx, int sy, int cw, int ch, boolean center) {
            int[] xPts = {sx, sx + cw / 2, sx, sx - cw / 2};
            int[] yPts = {sy, sy + ch / 2, sy + ch, sy + ch / 2};
            g.setColor(center ? new Color(70, 70, 110) : new Color(45, 45, 55));
            g.fillPolygon(xPts, yPts, 4);
            g.setColor(center ? new Color(140, 140, 200) : Color.GRAY);
            g.drawPolygon(xPts, yPts, 4);
        }

        /** Пунктирный контур того, сколько "земли" реально займёт footprintCols×footprintRows клеток. */
        private void drawFootprintGuideIsometric(Graphics2D g, int sx, int sy, int cw, int ch) {
            if (footprintCols == 1 && footprintRows == 1) return;
            double factor = footprintWidthFactor(); // тот же множитель и для ширины, и для высоты ромба
            double halfW = cw * factor / 2.0;
            double halfH = ch * factor / 2.0;
            int cx = sx, cy = sy + ch / 2;
            int[] xs = {cx, (int) Math.round(cx + halfW), cx, (int) Math.round(cx - halfW)};
            int[] ys = {(int) Math.round(cy - halfH), cy, (int) Math.round(cy + halfH), cy};
            Stroke old = g.getStroke();
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{6f, 4f}, 0f));
            g.setColor(new Color(60, 210, 230));
            g.drawPolygon(xs, ys, 4);
            g.setStroke(old);
        }

        private void drawFootprintGuideTopDown(Graphics2D g, int sx, int sy, int cw, int ch) {
            if (footprintCols == 1 && footprintRows == 1) return;
            int totalW = (int) Math.round(cw * footprintCols);
            int totalH = (int) Math.round(ch * footprintRows);
            int anchorX = sx + cw / 2, anchorY = sy + ch;
            int rx = anchorX - totalW / 2, ry = anchorY - totalH;
            Stroke old = g.getStroke();
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{6f, 4f}, 0f));
            g.setColor(new Color(60, 210, 230));
            g.drawRect(rx, ry, totalW, totalH);
            g.setStroke(old);
        }

        /** Буквальная копия ветки ISOMETRIC из TileGameEngine#renderCell. */
        private void drawSpriteIsometric(Graphics2D g, int sx, int sy, int cw, int ch) {
            BufferedImage img = displayed();
            if (img == null) return;
            int[] dwh = computeDrawSize();
            int dw = dwh[0], dh = dwh[1];
            if (dw <= 0 || dh <= 0) return;
            int effYOffset = effectiveYOffset();

            if (isometricSprite) {
                int dx = sx - dw / 2 + xOffset;
                int dy = sy + ch / 2 - dh + effYOffset;
                g.drawImage(img, dx, dy, dw, dh, null);
                markers(g, sx, sy + ch / 2, dx, dy, dw, dh);
            } else {
                double cx = sx + xOffset;
                double cy = sy + ch / 2.0 + effYOffset;
                AffineTransform old = g.getTransform();
                AffineTransform at = new AffineTransform();
                at.translate(cx, cy);
                at.rotate(Math.toRadians(45));
                at.scale(1.0, 0.5);
                at.translate(-dw / 2.0, -dh / 2.0);
                g.setTransform(at);
                g.drawImage(img, 0, 0, dw, dh, null);
                g.setTransform(old);
                markers(g, sx, sy + ch / 2, (int) (cx - dw / 2.0), (int) (cy - dh / 2.0), dw, dh);
            }
        }

        /** Буквальная копия ветки TOP_DOWN. */
        private void drawSpriteTopDown(Graphics2D g, int sx, int sy, int cw, int ch) {
            BufferedImage img = displayed();
            if (img == null) return;
            int[] dwh = computeDrawSize();
            int dw = dwh[0], dh = dwh[1];
            if (dw <= 0 || dh <= 0) return;
            int effYOffset = effectiveYOffset();
            int dx = sx + (cw - dw) / 2 + xOffset;
            int dy = sy + ch - dh + effYOffset;
            g.drawImage(img, dx, dy, dw, dh, null);
            markers(g, sx + cw / 2, sy + ch, dx, dy, dw, dh);
        }

        private void paintCanvasBackground(Graphics2D g) {
            switch (bg) {
                case DARK -> {
                    g.setColor(new Color(20, 20, 20));
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
                case LIGHT -> {
                    g.setColor(new Color(230, 230, 230));
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
                case CHECKER -> {
                    int tile = 12;
                    for (int y = 0; y < getHeight(); y += tile) {
                        for (int x = 0; x < getWidth(); x += tile) {
                            boolean odd = ((x / tile) + (y / tile)) % 2 == 0;
                            g.setColor(odd ? new Color(60, 60, 60) : new Color(75, 75, 75));
                            g.fillRect(x, y, tile, tile);
                        }
                    }
                }
            }
        }

        /**
         * Рисует поверх картинки:
         *  - жёлтая рамка = фактический прямоугольник кадра (frame), как он лёг на экран;
         *  - красная точка = точка крепления к сетке ДО xOffset/yOffset;
         *  - зелёная точка = фактическая точка крепления ПОСЛЕ xOffset/effectiveYOffset;
         *  - тонкая линия между ними = визуализация самого смещения.
         */
        private void markers(Graphics2D g, int anchorX, int anchorY, int dx, int dy, int dw, int dh) {
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(new Color(255, 210, 40));
            g.drawRect(dx, dy, dw, dh);

            int shiftedX = anchorX + xOffset;
            int shiftedY = anchorY + effectiveYOffset();

            g.setColor(new Color(230, 60, 60));
            dot(g, anchorX, anchorY, 4);

            g.setColor(new Color(60, 220, 90));
            dot(g, shiftedX, shiftedY, 4);

            if (shiftedX != anchorX || shiftedY != anchorY) {
                g.setColor(new Color(200, 200, 60));
                g.drawLine(anchorX, anchorY, shiftedX, shiftedY);
            }
        }

        private void dot(Graphics2D g, int x, int y, int r) {
            g.fillOval(x - r, y - r, r * 2, r * 2);
            g.setColor(Color.WHITE);
            g.drawOval(x - r, y - r, r * 2, r * 2);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GridFitDebugger().setVisible(true));
    }
}
