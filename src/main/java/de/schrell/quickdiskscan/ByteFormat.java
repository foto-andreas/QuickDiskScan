package de.schrell.quickdiskscan;

import java.text.NumberFormat;

import static de.schrell.quickdiskscan.I18n.numberLocale;

final class ByteFormat {
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};
    private static final NumberFormat INTEGER = NumberFormat.getIntegerInstance(numberLocale());

    private ByteFormat() {}

    static String bytes(long bytes) {
        if (bytes < 1_000) {
            return INTEGER.format(bytes) + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1_000 && unit < UNITS.length - 1) {
            value /= 1_000;
            unit++;
        }
        return String.format(numberLocale(), value >= 100 ? "%.0f %s" : value >= 10 ? "%.1f %s" : "%.2f %s",
                value, UNITS[unit]);
    }

    static String rate(long entries, long elapsedMillis) {
        if (elapsedMillis <= 0) {
            return "0/s";
        }
        return INTEGER.format(entries * 1_000L / elapsedMillis) + "/s";
    }
}
