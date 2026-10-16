package br.com.myfinancehub;

import android.content.Context;
import android.content.res.Configuration;

/** Local display preferences, kept separate from financial data and exported backups. */
public final class AppAppearance {
    public enum Mode { SYSTEM, LIGHT, DARK }

    private static final String PREFERENCES = "appearance";
    private static final String THEME_MODE = "theme_mode";

    private static final Palette LIGHT = new Palette(
        0xffF6F7F2, 0xffFFFFFF, 0xffECF1E8, 0xff1C3029, 0xff52665A, 0xffD9E1D7,
        0xff255847, 0xffFFFFFF, 0xffD5F272, 0xff163A32, 0xff256044, 0xffA23F35,
        0xffE8F2E9, 0xffFBEAE6, 0xff163A32, 0xffFFFFFF, 0xffC6D5CD
    );
    private static final Palette DARK = new Palette(
        0xff101C18, 0xff192A23, 0xff23392F, 0xffF0F5EF, 0xffB1C3B7, 0xff354B3F,
        0xffA8D5B8, 0xff102C20, 0xffD5F272, 0xff172A16, 0xff9AD6AD, 0xffFFB4A8,
        0xff203B2D, 0xff422C28, 0xff203D32, 0xffF3F8F1, 0xffBBD1C3
    );

    private AppAppearance() {}

    public static Mode getMode(Context context) {
        String value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(THEME_MODE, "system");
        if ("light".equals(value)) return Mode.LIGHT;
        if ("dark".equals(value)) return Mode.DARK;
        return Mode.SYSTEM;
    }

    public static void setMode(Context context, Mode mode) {
        String value = mode == Mode.LIGHT ? "light" : mode == Mode.DARK ? "dark" : "system";
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(THEME_MODE, value).apply();
    }

    public static boolean isDark(Context context) {
        Mode mode = getMode(context);
        if (mode == Mode.LIGHT) return false;
        if (mode == Mode.DARK) return true;
        return (context.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int themeRes(Context context) {
        return isDark(context) ? R.style.AppTheme_Dark : R.style.AppTheme_Light;
    }

    public static Palette palette(Context context) {
        return isDark(context) ? DARK : LIGHT;
    }

    /** Semantic colors shared by screens, controls and charts. */
    public static final class Palette {
        public final int background, surface, surfaceAlt, text, muted, line;
        public final int primary, onPrimary, accent, onAccent;
        public final int income, expense, incomeSurface, expenseSurface;
        public final int hero, heroText, heroMuted;

        private Palette(int background, int surface, int surfaceAlt, int text, int muted,
                int line, int primary, int onPrimary, int accent, int onAccent,
                int income, int expense, int incomeSurface, int expenseSurface,
                int hero, int heroText, int heroMuted) {
            this.background = background;
            this.surface = surface;
            this.surfaceAlt = surfaceAlt;
            this.text = text;
            this.muted = muted;
            this.line = line;
            this.primary = primary;
            this.onPrimary = onPrimary;
            this.accent = accent;
            this.onAccent = onAccent;
            this.income = income;
            this.expense = expense;
            this.incomeSurface = incomeSurface;
            this.expenseSurface = expenseSurface;
            this.hero = hero;
            this.heroText = heroText;
            this.heroMuted = heroMuted;
        }
    }
}
