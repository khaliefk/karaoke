package com.khalief.karaoke.remote;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Tema tampilan Remote (v1.3.8): system / dark / light.
 * Disimpan di SharedPreferences "karaoke", kunci "theme", supaya
 * layar pilihan native dan halaman web (lewat bridge) pakai pilihan
 * yang sama. Default system = ikut pengaturan HP.
 */
public final class ThemeHelper {
    public static final String PREFS = "karaoke";
    public static final String KEY_THEME = "theme";
    public static final String SYSTEM = "system";
    public static final String DARK = "dark";
    public static final String LIGHT = "light";

    private ThemeHelper() {}

    public static String getTheme(Context ctx) {
        String t = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, SYSTEM);
        if (DARK.equals(t) || LIGHT.equals(t)) return t;
        return SYSTEM;
    }

    public static void setTheme(Context ctx, String mode) {
        if (!DARK.equals(mode) && !LIGHT.equals(mode)) mode = SYSTEM;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_THEME, mode).commit();
    }

    /** Gelap atau terang, setelah "system" diterjemahkan dari HP. */
    public static boolean isDark(Context ctx) {
        String mode = getTheme(ctx);
        if (DARK.equals(mode)) return true;
        if (LIGHT.equals(mode)) return false;
        int night = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    public static String label(String mode) {
        if (DARK.equals(mode)) return "Gelap";
        if (LIGHT.equals(mode)) return "Terang";
        return "Sistem";
    }

    /* Warna layar pilihan, sepasang gelap/terang. Hijau tombol
       (#16A34A) sama di kedua tema. */
    public static int bg(boolean dark) { return dark ? 0xFF111111 : 0xFFF4F4F5; }
    public static int text(boolean dark) { return dark ? 0xFFFFFFFF : 0xFF171717; }
    public static int muted(boolean dark) { return dark ? 0xFFAAAAAA : 0xFF71717A; }
    public static int hint(boolean dark) { return dark ? 0xFF888888 : 0xFFA1A1AA; }
    public static int ghostBtn(boolean dark) { return dark ? 0xFF262626 : 0xFFE4E4E7; }
    public static int ghostBtnText(boolean dark) { return dark ? 0xFFFFFFFF : 0xFF171717; }
}
