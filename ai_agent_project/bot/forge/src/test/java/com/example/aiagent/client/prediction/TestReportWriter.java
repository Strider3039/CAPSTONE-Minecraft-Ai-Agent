package com.example.aiagent.client.prediction;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes test outputs (log + csv + png) into ./ghost_test_reports
 * relative to the test working directory (usually project root).
 */
public final class TestReportWriter {

    private static final Path OUT_DIR = Paths.get("ghost_test_reports");

    private TestReportWriter() {}

    public static Path ensureOutDir() throws IOException {
        Files.createDirectories(OUT_DIR);
        return OUT_DIR;
    }

    public static String timestampTag() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    public static void writeLog(String baseName, List<String> lines) throws IOException {
        ensureOutDir();
        Path p = OUT_DIR.resolve(baseName + ".log");
        Files.write(p, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static void writeCsv(String baseName, List<String> lines) throws IOException {
        ensureOutDir();
        Path p = OUT_DIR.resolve(baseName + ".csv");
        Files.write(p, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Minimal chart generator (no external libs): draws up to 3 series.
     * Provide equal-length arrays. Values are auto-scaled per chart.
     */
    public static void writePngChart(
            String baseName,
            String title,
            int width,
            int height,
            double[] s1, String s1Label,
            double[] s2, String s2Label,
            double[] s3, String s3Label
    ) throws IOException {
        ensureOutDir();
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        // background
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int padL = 60, padR = 20, padT = 40, padB = 45;
        int plotW = width - padL - padR;
        int plotH = height - padT - padB;

        // title
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.drawString(title, padL, 20);

        // find min/max across provided series
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        minMax(s1, minMaxHolder -> { /* noop */ });
        double[] all = concatNonNull(s1, s2, s3);
        for (double v : all) {
            if (Double.isFinite(v)) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max) || min == max) {
            min = min - 1.0;
            max = max + 1.0;
        }

        // axes
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(new Color(230, 230, 230));
        // horizontal grid lines
        for (int i = 0; i <= 5; i++) {
            int y = padT + (int) Math.round(plotH * (i / 5.0));
            g.drawLine(padL, y, padL + plotW, y);
        }
        g.setColor(Color.BLACK);
        g.drawRect(padL, padT, plotW, plotH);

        // y labels
        g.drawString(String.format("%.3f", max), 8, padT + 12);
        g.drawString(String.format("%.3f", min), 8, padT + plotH);

        // plot series
        plotSeries(g, padL, padT, plotW, plotH, min, max, s1, Color.BLUE);
        plotSeries(g, padL, padT, plotW, plotH, min, max, s2, Color.RED);
        plotSeries(g, padL, padT, plotW, plotH, min, max, s3, new Color(0, 140, 0));

        // legend
        int lx = padL, ly = padT + plotH + 28;
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        if (s1 != null && s1Label != null) { legendItem(g, lx, ly, Color.BLUE, s1Label); lx += 160; }
        if (s2 != null && s2Label != null) { legendItem(g, lx, ly, Color.RED, s2Label); lx += 160; }
        if (s3 != null && s3Label != null) { legendItem(g, lx, ly, new Color(0, 140, 0), s3Label); }

        g.dispose();

        Path out = OUT_DIR.resolve(baseName + ".png");
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }

    private static void legendItem(Graphics2D g, int x, int y, Color c, String label) {
        g.setColor(c);
        g.fillRect(x, y - 10, 14, 10);
        g.setColor(Color.BLACK);
        g.drawString(label, x + 20, y);
    }

    private static void plotSeries(Graphics2D g, int padL, int padT, int plotW, int plotH,
                                   double min, double max, double[] s, Color c) {
        if (s == null || s.length < 2) return;
        g.setColor(c);

        int n = s.length;
        for (int i = 0; i < n - 1; i++) {
            double v1 = s[i];
            double v2 = s[i + 1];
            if (!Double.isFinite(v1) || !Double.isFinite(v2)) continue;

            int x1 = padL + (int) Math.round(plotW * (i / (double) (n - 1)));
            int x2 = padL + (int) Math.round(plotW * ((i + 1) / (double) (n - 1)));

            int y1 = padT + (int) Math.round(plotH * (1.0 - (v1 - min) / (max - min)));
            int y2 = padT + (int) Math.round(plotH * (1.0 - (v2 - min) / (max - min)));

            g.drawLine(x1, y1, x2, y2);
        }
    }

    private static double[] concatNonNull(double[] a, double[] b, double[] c) {
        int na = a == null ? 0 : a.length;
        int nb = b == null ? 0 : b.length;
        int nc = c == null ? 0 : c.length;
        double[] out = new double[na + nb + nc];
        int k = 0;
        if (a != null) { System.arraycopy(a, 0, out, k, na); k += na; }
        if (b != null) { System.arraycopy(b, 0, out, k, nb); k += nb; }
        if (c != null) { System.arraycopy(c, 0, out, k, nc); }
        return out;
    }

    // tiny helper to avoid warnings; not strictly needed
    private static void minMax(double[] s, java.util.function.Consumer<double[]> consumer) {
        // no-op
    }
}
