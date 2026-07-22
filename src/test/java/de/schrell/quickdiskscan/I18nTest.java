package de.schrell.quickdiskscan;

public final class I18nTest {
    public static void main(String[] args) {
        assertEquals("Deutsch", I18n.text(I18n.Language.GERMAN, "Deutsch", "English"));
        assertEquals("English", I18n.text(I18n.Language.ENGLISH, "Deutsch", "English"));
        assertEquals(java.util.Locale.GERMANY, I18n.numberLocale(I18n.Language.GERMAN));
        assertEquals(java.util.Locale.US, I18n.numberLocale(I18n.Language.ENGLISH));
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Erwartet " + expected + ", erhalten " + actual);
        }
    }
}
