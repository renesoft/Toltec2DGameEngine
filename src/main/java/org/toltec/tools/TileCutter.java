package org.toltec.tools;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TileCutter {

    // Цвета, которые станут прозрачными
    private static final Color MAGIC_PINK = new Color(255, 0, 255); // стандартный "game pink"
    private static final Color BLACK = new Color(0, 0, 0);

    // Допуск на совпадение цвета (0 = только точное совпадение)
    private static final int TOLERANCE = 0;

    public static void main(String[] args) {
        File inputDir = new File("src/main/resources/tiles_cut");

        if (!inputDir.exists() || !inputDir.isDirectory()) {
            System.err.println("Папка не найдена: " + inputDir.getAbsolutePath());
            return;
        }

        List<File> files = new ArrayList<>();
        collectFiles(inputDir, files);

        if (files.isEmpty()) {
            System.out.println("Файлы с '256x128' в названии не найдены.");
            return;
        }

        for (File file : files) {
            try {
                processFile(file);
            } catch (IOException e) {
                System.err.println("Ошибка при обработке " + file.getAbsolutePath() + ": " + e.getMessage());
            }
        }

        System.out.println("Готово. Обработано файлов: " + files.size());
    }

    /** Рекурсивный сбор файлов */
    private static void collectFiles(File dir, List<File> files) {
        File[] list = dir.listFiles();
        if (list == null) return;

        for (File f : list) {
            if (f.isDirectory()) {
                collectFiles(f, files);
            } else if (f.getName().toLowerCase().contains("256x128")) {
                files.add(f);
            }
        }
    }

    private static void processFile(File inputFile) throws IOException {
        BufferedImage source = ImageIO.read(inputFile);
        if (source == null) {
            System.err.println("Не удалось прочитать изображение: " + inputFile.getAbsolutePath());
            return;
        }

        int tileWidth = 256;
        int tileHeight = 128;

        String originalName = inputFile.getName();
        String baseName = originalName.replace("256x128", "");

        // Убираем расширение
        int dotIndex = baseName.lastIndexOf('.');
        String nameWithoutExt;
        if (dotIndex > 0) {
            nameWithoutExt = baseName.substring(0, dotIndex);
        } else {
            nameWithoutExt = baseName;
        }

        // Чистим края от мусора
        nameWithoutExt = nameWithoutExt.replaceAll("[_\\s]+$", "").replaceAll("^[_\\s]+", "");
        if (nameWithoutExt.isEmpty()) {
            nameWithoutExt = "tile";
        }

        int cols = source.getWidth() / tileWidth;
        int rows = source.getHeight() / tileHeight;

        if (cols == 0 || rows == 0) {
            System.err.println("Изображение меньше тайла, пропускаем: " + inputFile.getName());
            return;
        }

        int tileIndex = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                BufferedImage sub = source.getSubimage(
                        col * tileWidth,
                        row * tileHeight,
                        tileWidth,
                        tileHeight
                );

                // Создаём копию с альфа-каналом и убираем фон
                BufferedImage tile = makeTransparent(sub, MAGIC_PINK, BLACK);

                String outputName = nameWithoutExt + "_" + tileIndex + ".png";
                File outputFile = new File(inputFile.getParent(), outputName);

                ImageIO.write(tile, "png", outputFile);
                System.out.println("Сохранён: " + outputFile.getAbsolutePath());

                tileIndex++;
            }
        }
    }

    /**
     * Копирует изображение в ARGB и делает указанные цвета полностью прозрачными.
     */
    private static BufferedImage makeTransparent(BufferedImage source, Color... targetColors) {
        BufferedImage result = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g = result.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();

        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                int rgb = result.getRGB(x, y);
                // Берём только RGB, игнорируя исходную альфу
                Color pixel = new Color(rgb & 0x00FFFFFF, false);

                for (Color target : targetColors) {
                    if (colorsMatch(pixel, target)) {
                        result.setRGB(x, y, 0x00000000); // полностью прозрачный
                        break;
                    }
                }
            }
        }

        return result;
    }

    private static boolean colorsMatch(Color c1, Color c2) {
        return Math.abs(c1.getRed() - c2.getRed()) <= TOLERANCE &&
               Math.abs(c1.getGreen() - c2.getGreen()) <= TOLERANCE &&
               Math.abs(c1.getBlue() - c2.getBlue()) <= TOLERANCE;
    }
}