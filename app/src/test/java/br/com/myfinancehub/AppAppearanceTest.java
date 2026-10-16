package br.com.myfinancehub;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Display choices follow Android unless the user explicitly chooses a local override. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR-notnight")
public final class AppAppearanceTest {
    private Context context;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void after() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void defaultModeFollowsTheCurrentAndroidNightSetting() {
        assertEquals(AppAppearance.Mode.SYSTEM, AppAppearance.getMode(context));
        assertFalse(AppAppearance.isDark(context));
        assertEquals(R.style.AppTheme_Light, AppAppearance.themeRes(context));

        RuntimeEnvironment.setQualifiers("+night");
        assertTrue(AppAppearance.isDark(context));
        assertEquals(R.style.AppTheme_Dark, AppAppearance.themeRes(context));
        RuntimeEnvironment.setQualifiers("+notnight");
        assertFalse(AppAppearance.isDark(context));
    }

    @Test public void manualChoicesPersistAndOverrideAndroidInBothDirections() {
        AppAppearance.setMode(context, AppAppearance.Mode.DARK);
        assertEquals("dark", context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getString("theme_mode", ""));
        assertEquals(AppAppearance.Mode.DARK, AppAppearance.getMode(context));
        assertTrue(AppAppearance.isDark(context));
        RuntimeEnvironment.setQualifiers("+night");
        AppAppearance.setMode(context, AppAppearance.Mode.LIGHT);
        assertEquals("light", context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getString("theme_mode", ""));
        assertEquals(AppAppearance.Mode.LIGHT, AppAppearance.getMode(context));
        assertFalse(AppAppearance.isDark(context));

        AppAppearance.setMode(context, AppAppearance.Mode.SYSTEM);
        assertEquals(AppAppearance.Mode.SYSTEM, AppAppearance.getMode(context));
        assertTrue(AppAppearance.isDark(context));
        RuntimeEnvironment.setQualifiers("+notnight");
        assertFalse(AppAppearance.isDark(context));
    }

    @Test public void unknownOrMissingSavedValueReturnsToSystemMode() {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
                .putString("theme_mode", "unsupported-value").commit();
        assertEquals(AppAppearance.Mode.SYSTEM, AppAppearance.getMode(context));
        RuntimeEnvironment.setQualifiers("+night");
        assertTrue(AppAppearance.isDark(context));
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
                .remove("theme_mode").commit();
        assertEquals(AppAppearance.Mode.SYSTEM, AppAppearance.getMode(context));
        AppAppearance.setMode(context, null);
        assertEquals(AppAppearance.Mode.SYSTEM, AppAppearance.getMode(context));
    }

    @Test public void bothPalettesKeepNormalSizedTextAtWcagAaContrast() {
        for (AppAppearance.Mode mode : new AppAppearance.Mode[]{AppAppearance.Mode.LIGHT, AppAppearance.Mode.DARK}) {
            AppAppearance.setMode(context, mode);
            AppAppearance.Palette colors = AppAppearance.palette(context);
            for (int surface : new int[]{colors.background, colors.surface, colors.surfaceAlt}) {
                assertAa(mode + " primary text", colors.text, surface);
                assertAa(mode + " secondary text", colors.muted, surface);
            }
            assertAa(mode + " primary action", colors.onPrimary, colors.primary);
            assertAa(mode + " accent action", colors.onAccent, colors.accent);
            assertAa(mode + " balance text", colors.heroText, colors.hero);
            assertAa(mode + " balance hint", colors.heroMuted, colors.hero);
            assertAa(mode + " income text", colors.income, colors.incomeSurface);
            assertAa(mode + " expense text", colors.expense, colors.expenseSurface);
        }
    }

    private static void assertAa(String label, int foreground, int background) {
        double first = luminance(foreground);
        double second = luminance(background);
        double ratio = (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
        assertTrue(label + " contrast is " + ratio + "; normal text requires at least 4.5:1", ratio >= 4.5);
    }

    private static double luminance(int color) {
        return 0.2126 * linear((color >> 16) & 255)
                + 0.7152 * linear((color >> 8) & 255)
                + 0.0722 * linear(color & 255);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
