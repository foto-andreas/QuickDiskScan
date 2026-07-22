package de.schrell.quickdiskscan;

public final class ByteFormatTest {
    public static void main(String[] args) {
        I18n.Language original = I18n.language();
        try {
            assertFormat(I18n.Language.GERMAN, "1,23 MB", "12.345/s");
            assertFormat(I18n.Language.ENGLISH, "1.23 MB", "12,345/s");
        } finally {
            I18n.saveLanguage(original);
        }
    }

    private static void assertFormat(I18n.Language language, String bytes, String rate) {
        I18n.saveLanguage(language);
        assertEquals(bytes, ByteFormat.bytes(1_234_567));
        assertEquals(rate, ByteFormat.rate(12_345, 1_000));
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Erwartet " + expected + ", erhalten " + actual);
        }
    }
}
