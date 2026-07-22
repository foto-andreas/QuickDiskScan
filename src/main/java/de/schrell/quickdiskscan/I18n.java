package de.schrell.quickdiskscan;

import java.util.Locale;

final class I18n {
    private static final boolean GERMAN = Locale.getDefault().getLanguage().equals("de");

    private I18n() {}

    static String text(String german, String english) {
        return GERMAN ? german : english;
    }

    static Locale numberLocale() {
        return GERMAN ? Locale.GERMANY : Locale.US;
    }
}
