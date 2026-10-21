package br.com.myfinancehub;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Quote settings must open on supported Android versions without contacting a provider. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "pt-rBR-notnight")
public final class QuotesActivityTest {
    private Context context;
    private ActivityController<QuotesActivity> controller;
    private QuotesActivity activity;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("investment_quotes", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After public void after() throws Exception {
        if (controller != null) {
            controller.pause().stop().destroy();
            Field worker = QuotesActivity.class.getDeclaredField("worker");
            worker.setAccessible(true);
            assertTrue(((ExecutorService) worker.get(activity)).awaitTermination(10, TimeUnit.SECONDS));
        }
        context.getSharedPreferences("investment_quotes", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void android15OpensSettingsWithAnInstalledDecorAndNoAutomaticNetworkWork() {
        start();
        assertNotNull(activity.getWindow().peekDecorView());
        assertNotNull(find("Cotações e atualização"));
        assertNotNull(find("Pesquisar um ativo"));
        assertFalse(((Switch) find("Atualizar ao abrir a carteira")).isChecked());
        assertFalse(InvestmentQuoteUpdater.autoRefreshEnabled(context));
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertFalse(context.getSharedPreferences("investment_quotes", Context.MODE_PRIVATE).contains("last_attempt"));
    }

    @Test @Config(sdk = 28, qualifiers = "pt-rBR-night")
    public void legacyAndroidOpensTheSameControlsInDarkMode() {
        start();
        assertTrue(AppAppearance.isDark(activity));
        assertNotNull(find("Salvar configuração"));
        assertNotNull(find("Atualizar investimentos agora"));
        assertFalse(InvestmentQuoteUpdater.autoRefreshEnabled(context));
    }

    @Test public void savingTheOptInAndMaskedKeySurvivesRecreationWithoutPuttingTheKeyInInstanceState() {
        start();
        EditText key = fieldWithHint(screen(), "Chave do provedor");
        assertNotNull(key);
        assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertFalse(key.isSaveEnabled());
        key.setText("fixture-key-not-a-real-secret");
        ((Switch) find("Atualizar ao abrir a carteira")).setChecked(true);
        ((Button) find("Salvar configuração")).performClick();
        assertEquals("fixture-key-not-a-real-secret", InvestmentQuoteUpdater.token(context));
        assertTrue(InvestmentQuoteUpdater.autoRefreshEnabled(context));
        Bundle state = new Bundle(); controller.saveInstanceState(state);
        for (String name : state.keySet()) assertFalse(String.valueOf(state.get(name)).contains("fixture-key"));
        controller.recreate(); activity = controller.get();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(((Switch) find("Atualizar ao abrir a carteira")).isChecked());
        assertEquals("fixture-key-not-a-real-secret", fieldWithHint(screen(), "Chave do provedor").getText().toString());
        assertFalse(context.getSharedPreferences("investment_quotes", Context.MODE_PRIVATE).contains("last_attempt"));
    }

    @Test public void anEmptySearchShowsAnActionableMessageWithoutStartingARequest() {
        start();
        ((Button) find("Pesquisar ativo")).performClick();
        assertNotNull(find("Informe o código ou nome do ativo, com até 80 caracteres."));
        assertTrue(((Button) find("Pesquisar ativo")).isEnabled());
        assertFalse(context.getSharedPreferences("investment_quotes", Context.MODE_PRIVATE).contains("last_attempt"));
    }

    private void start() {
        controller = Robolectric.buildActivity(QuotesActivity.class).setup(); activity = controller.get();
        shadowOf(Looper.getMainLooper()).idle();
    }
    private View screen() { return activity.getWindow().getDecorView(); }
    private TextView find(String label) { return byText(screen(), label); }
    private static TextView byText(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView result = byText(((ViewGroup) view).getChildAt(i), label); if (result != null) return result;
        }
        return null;
    }
    private static EditText fieldWithHint(View view, String hint) {
        if (view instanceof EditText && hint.contentEquals(((EditText) view).getHint())) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText result = fieldWithHint(((ViewGroup) view).getChildAt(i), hint); if (result != null) return result;
        }
        return null;
    }
}
