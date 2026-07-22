package de.schrell.quickdiskscan;

public final class ByteFormatTest {
    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException("Erwartet Dezimal- und Tausendertrennzeichen");
        }
        assertEquals("1" + args[0] + "23 MB", ByteFormat.bytes(1_234_567));
        assertEquals("12" + args[1] + "345/s", ByteFormat.rate(12_345, 1_000));
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Erwartet " + expected + ", erhalten " + actual);
        }
    }
}
