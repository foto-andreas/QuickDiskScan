package de.schrell.quickdiskscan;

import java.util.Locale;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

final class I18n {
    enum Language {
        GERMAN("Deutsch", Locale.GERMANY),
        ENGLISH("English", Locale.US);

        private final String label;
        private final Locale numberLocale;

        Language(String label, Locale numberLocale) {
            this.label = label;
            this.numberLocale = numberLocale;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final String PREF_LANGUAGE = "language";
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(I18n.class);
    private static final Language LANGUAGE = language(PREFERENCES.get(PREF_LANGUAGE,
            Locale.getDefault().getLanguage()));

    private I18n() {}

    static String text(String german, String english) {
        return text(LANGUAGE, german, english);
    }

    static Locale numberLocale() {
        return numberLocale(LANGUAGE);
    }

    static Language language() {
        return LANGUAGE;
    }

    static void saveLanguage(Language language) {
        PREFERENCES.put(PREF_LANGUAGE, language.name());
        try {
            PREFERENCES.flush();
        } catch (BackingStoreException ignored) {
            // The language still applies once the preference store is available again.
        }
    }

    static String text(Language language, String german, String english) {
        return language == Language.GERMAN ? german : english;
    }

    static Locale numberLocale(Language language) {
        return language.numberLocale;
    }

    private static Language language(String value) {
        try {
            return Language.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return value.startsWith("de") ? Language.GERMAN : Language.ENGLISH;
        }
    }
}
