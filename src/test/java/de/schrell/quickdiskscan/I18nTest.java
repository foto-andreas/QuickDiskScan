package de.schrell.quickdiskscan;

import java.util.Locale;

public final class I18nTest {
    public static void main(String[] args) {
        String actual = I18n.text("Deutsch", "English");
        if (args.length != 1 || !actual.equals(args[0])) {
            throw new AssertionError("Erwartet " + java.util.Arrays.toString(args) + ", erhalten " + actual);
        }
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
            assertEquals(Locale.GERMANY, I18n.numberLocale());
            Locale.setDefault(Locale.Category.FORMAT, Locale.US);
            assertEquals(Locale.US, I18n.numberLocale());
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original);
        }
    }

    private static void assertEquals(Locale expected, Locale actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Erwartet " + expected + ", erhalten " + actual);
        }
    }
}
