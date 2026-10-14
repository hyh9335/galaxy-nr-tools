package com.example.galaxy5gmanager;
import android.content.Context;
import java.util.Locale;
/** English fallback, Chinese when the system language is zh. */
final class UiText {
    private UiText() { }
    static boolean chinese(Context context) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        return "zh".equals(locale.getLanguage());
    }
    static String t(Context context, String en, String zh) {
        return chinese(context) ? zh : en;
    }
}
