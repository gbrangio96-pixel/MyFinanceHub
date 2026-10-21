package br.com.myfinancehub;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Report navigation, date/account scope and real document export using the local SQLite data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR")
public final class ReportActivityTest {
    private Context context;
    private FinanceStore store;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("myfinancehub.db");
        store = new FinanceStore(context);
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        idle();
    }

    @After public void after() throws Exception {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (controller != null) {
            controller.pause().stop().destroy();
            assertTrue("The activity worker must close cleanly", worker().awaitTermination(10, TimeUnit.SECONDS));
        }
        if (store != null) store.close();
        context.deleteDatabase("myfinancehub.db");
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void emptyReportIsDirectlyAccessibleWithoutInventingMoneyOrChangingData() {
        String before = store.exportBackup();
        reports();
        assertNotNull(byText(screen(), "RESULTADO DO PERÍODO"));
        assertNotNull(byText(screen(), "Gastos por categoria"));
        assertEquals("Este mês", spinner("Período do relatório").getSelectedItem().toString());
        assertEquals("Todas as contas", spinner("Conta do relatório").getSelectedItem().toString());
        assertContains(screen(), "R$ 0,00");
        for (String action : new String[]{"Salvar PDF", "Compartilhar PDF", "Exportar CSV"})
            assertNotNull("Report action must be available: " + action, byDescription(screen(), action));
        assertEquals(before, store.exportBackup());
        assertTrue(store.entries().isEmpty());
    }

    @Test public void reportIncludesCreditConsumptionWithoutCountingTheInvoiceAndOwnTransfersTwice() {
        long account = store.accounts().get(0).id;
        Models.Category food = new Models.Category(0, "Alimentação do teste", "expense");
        store.saveCategory(food);
        Models.Card card = new Models.Card(0, "Cartão de teste", "1234", 25, 5);
        store.saveCard(card);
        LocalDate day = YearMonth.now().atDay(2);
        entry(account, 0, 0, "Salário de teste", day, "income", "pix", 100000);
        entry(account, food.id, 0, "Mercado de teste", day, "expense", "debit", 34567);
        entry(account, food.id, card.id, "Restaurante no crédito", day, "expense", "credit", 8956);
        entry(account, 0, 0, "Fatura já quitada", day, "expense", "card_payment", 8956);
        entry(account, 0, 0, "Transferência de teste", day, "expense", "transfer", 22222);
        entry(account, 0, 0, "Fora do período", day.minusMonths(1), "expense", "pix", 77777);
        String before = store.exportBackup();
        reports();
        assertContains(metric("Entradas"), "R$ 1.000,00");
        assertContains(metric("Despesas"), "R$ 435,23");
        assertContains(metric("RESULTADO DO PERÍODO"), "R$ 564,77");
        assertContains(screen(), "Alimentação do teste");
        assertNotContains(metric("RESULTADO DO PERÍODO"), "R$ 252,99");
        assertEquals(before, store.exportBackup());
    }

    @Test public void accountFilterChangesTheVisibleTotalsAndCategoryRanking() {
        long first = store.accounts().get(0).id;
        Models.Account second = new Models.Account(0, "Conta exclusiva do relatório", 0);
        store.saveAccount(second);
        Models.Category firstCategory = new Models.Category(0, "Categoria da primeira conta", "expense");
        Models.Category secondCategory = new Models.Category(0, "Categoria da segunda conta", "expense");
        store.saveCategory(firstCategory);
        store.saveCategory(secondCategory);
        LocalDate day = YearMonth.now().atDay(4);
        entry(first, firstCategory.id, 0, "Compra da primeira conta", day, "expense", "pix", 12345);
        entry(second.id, secondCategory.id, 0, "Compra da segunda conta", day, "expense", "pix", 6789);
        String before = store.exportBackup();
        reports();
        assertContains(metric("Despesas"), "R$ 191,34");
        select("Conta do relatório", second.name);
        assertContains(metric("Despesas"), "R$ 67,89");
        assertContains(screen(), secondCategory.name);
        assertNotContains(screen(), firstCategory.name);
        select("Conta do relatório", "Todas as contas");
        assertContains(metric("Despesas"), "R$ 191,34");
        assertContains(screen(), firstCategory.name);
        assertEquals(before, store.exportBackup());
    }

    @Test public void threeMonthPresetIncludesEarlierMonthsButLeavesOlderEntriesOutside() {
        long account = store.accounts().get(0).id;
        YearMonth current = YearMonth.now();
        entry(account, 0, 0, "Despesa do mês", current.atDay(2), "expense", "pix", 1111);
        entry(account, 0, 0, "Despesa há dois meses", current.minusMonths(2).atDay(2), "expense", "pix", 2222);
        entry(account, 0, 0, "Despesa há três meses", current.minusMonths(3).atDay(2), "expense", "pix", 4444);
        reports();
        assertContains(metric("Despesas"), "R$ 11,11");
        select("Período do relatório", "Últimos 3 meses");
        assertContains(metric("Despesas"), "R$ 33,33");
        Bundle saved = savedState();
        assertEquals(current.minusMonths(2).atDay(1).toString(), saved.getString("report_from"));
        assertEquals(current.atEndOfMonth().toString(), saved.getString("report_to"));
        assertEquals(1, saved.getInt("report_preset"));
    }

    @Test public void customPeriodAndAccountSurviveActivityRecreationAndThemeChanges() {
        Models.Account account = new Models.Account(0, "Conta personalizada", 0);
        store.saveAccount(account);
        YearMonth previous = YearMonth.now().minusMonths(1);
        entry(account.id, 0, 0, "Despesa no intervalo escolhido", previous.atDay(10), "expense", "pix", 2468);
        reports();
        select("Conta do relatório", account.name);
        select("Período do relatório", "Personalizado");
        chooseDate("Data inicial do relatório", previous.atDay(1));
        chooseDate("Data final do relatório", previous.atEndOfMonth());
        assertContains(metric("Despesas"), "R$ 24,68");
        String before = store.exportBackup();

        Bundle saved = savedState();
        assertEquals(previous.atDay(1).toString(), saved.getString("report_from"));
        assertEquals(previous.atEndOfMonth().toString(), saved.getString("report_to"));
        assertEquals(account.id, saved.getLong("report_account"));
        assertEquals(3, saved.getInt("report_preset"));
        AppAppearance.setMode(activity, AppAppearance.Mode.DARK);
        controller.recreate();
        activity = controller.get();
        idle();
        assertTrue(AppAppearance.isDark(activity));
        assertNotNull(byText(screen(), "RESULTADO DO PERÍODO"));
        assertEquals("Personalizado", spinner("Período do relatório").getSelectedItem().toString());
        assertEquals(account.name, spinner("Conta do relatório").getSelectedItem().toString());
        assertContains(metric("Despesas"), "R$ 24,68");
        assertEquals(before, store.exportBackup());
        Bundle recreated = savedState();
        assertEquals(saved.getString("report_from"), recreated.getString("report_from"));
        assertEquals(saved.getString("report_to"), recreated.getString("report_to"));
    }

    @Test public void pdfAndCsvUseTheAndroidSavePickerWithTheirCorrectMimeTypes() {
        reports();
        ShadowActivity.IntentForResult pdf = savePicker("Salvar PDF", 46, "application/pdf");
        assertTrue(pdf.intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".pdf"));
        shadowOf(activity).receiveResult(pdf.intent, Activity.RESULT_CANCELED, null);
        idle();
        ShadowActivity.IntentForResult csv = savePicker("Exportar CSV", 47, "text/csv");
        assertTrue(csv.intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".csv"));
        shadowOf(activity).receiveResult(csv.intent, Activity.RESULT_CANCELED, null);
        idle();
        assertTrue(store.entries().isEmpty());
    }

    @Test public void csvWritesTheCapturedPeriodAndAccountWithoutMutatingAnyFinancialData() throws Exception {
        long first = store.accounts().get(0).id;
        Models.Account second = new Models.Account(0, "Outra conta", 0);
        store.saveAccount(second);
        LocalDate day = YearMonth.now().atDay(2);
        entry(first, 0, 0, "Despesa incluída no arquivo", day, "expense", "pix", 1234);
        entry(second.id, 0, 0, "Despesa de outra conta", day, "expense", "pix", 9876);
        entry(first, 0, 0, "Despesa de outro período", day.minusMonths(1), "expense", "pix", 5555);
        reports();
        select("Conta do relatório", store.accounts().stream().filter(a -> a.id == first).findFirst().get().name);
        ShadowActivity.IntentForResult started = savePicker("Exportar CSV", 47, "text/csv");
        // The document must represent what the user requested, not a newly recomputed selection/data set.
        entry(first, 0, 0, "Inserida depois do pedido de exportação", day, "expense", "pix", 1111);
        String before = store.exportBackup();
        Uri destination = Uri.parse("content://report-tests.documents/document/scoped-csv");
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        };
        shadowOf(activity.getContentResolver()).registerOutputStream(destination, output);
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, new Intent().setData(destination));
        drainWorker();
        assertTrue("The destination stream must be closed", closed.get());
        String csv = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("Despesa incluída no arquivo"));
        assertTrue(csv.contains("12,34"));
        assertFalse(csv.contains("Despesa de outra conta"));
        assertFalse(csv.contains("Despesa de outro período"));
        assertFalse(csv.contains("Inserida depois do pedido de exportação"));
        assertEquals(before, store.exportBackup());
    }

    @Test public void cancellingTheSavePickerDoesNotOpenAnOutputStreamOrChangeMoney() throws Exception {
        entry(store.accounts().get(0).id, 0, 0, "Registro preservado", YearMonth.now().atDay(2), "expense", "pix", 4321);
        String before = store.exportBackup();
        reports();
        ShadowActivity.IntentForResult started = savePicker("Exportar CSV", 47, "text/csv");
        Uri destination = Uri.parse("content://report-tests.documents/document/cancelled");
        AtomicInteger opened = new AtomicInteger();
        shadowOf(activity.getContentResolver()).registerOutputStreamSupplier(destination, () -> {
            opened.incrementAndGet();
            return new ByteArrayOutputStream();
        });
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, new Intent().setData(destination));
        drainWorker();
        assertEquals(0, opened.get());
        assertEquals(before, store.exportBackup());
    }

    @Test public void hiddenAmountsStayMaskedAndEveryExportRequiresAnExplicitConfirmation() {
        entry(store.accounts().get(0).id, 0, 0, "Despesa privada", YearMonth.now().atDay(2), "expense", "pix", 12345);
        reports();
        click("Ocultar valores");
        assertNotContains(screen(), "123,45");
        assertContains(screen(), "R$ ••••");
        String before = store.exportBackup();
        for (String label : new String[]{"Salvar PDF", "Compartilhar PDF", "Exportar CSV"}) {
            click(label);
            AlertDialog confirmation = dialog();
            assertContains(confirmation.getWindow().getDecorView(), "Exportar valores visíveis?");
            assertNull("No external destination may start before the confirmation",
                    shadowOf(activity).getNextStartedActivity());
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            idle();
            assertNull(shadowOf(activity).getNextStartedActivity());
        }
        click("Exportar CSV");
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(started);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.getAction());
        assertEquals("text/csv", started.intent.getType());
        assertNotContains(screen(), "123,45");
        assertEquals(before, store.exportBackup());
    }

    private void entry(long accountId, long categoryId, long cardId, String description,
                       LocalDate date, String type, String method, long cents) {
        store.saveEntry(new Models.Entry(0, accountId, categoryId, cardId, description,
                date.toString(), type, method, "", cents));
    }

    private void reports() { click("Relatórios"); }
    private View screen() { return activity.findViewById(android.R.id.content); }
    private void click(String description) { clickClickable(byDescription(screen(), description)); }
    private Spinner spinner(String description) {
        View view = byDescription(screen(), description);
        assertTrue("Missing spinner " + description, view instanceof Spinner);
        return (Spinner) view;
    }
    private void select(String description, String value) {
        Spinner spinner = spinner(description);
        for (int i = 0; i < spinner.getCount(); i++) if (value.equals(spinner.getItemAtPosition(i).toString())) {
            spinner.setSelection(i);
            idle();
            return;
        }
        fail("Missing option " + value + " in " + description);
    }
    private View metric(String label) {
        View heading = byText(screen(), label);
        assertNotNull("Missing report metric " + label, heading);
        assertTrue(heading.getParent() instanceof View);
        return (View) heading.getParent();
    }
    private void chooseDate(String description, LocalDate date) {
        click(description);
        AlertDialog visible = dialog();
        assertTrue("A report date must use the native picker", visible instanceof DatePickerDialog);
        DatePickerDialog picker = (DatePickerDialog) visible;
        shadowOf(picker).getOnDateSetListenerCallback().onDateSet(picker.getDatePicker(),
                date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth());
        picker.dismiss();
        idle();
    }
    private Bundle savedState() {
        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        return state;
    }
    private ShadowActivity.IntentForResult savePicker(String action, int code, String mime) {
        click(action);
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull("Export must open the Android document picker", started);
        assertEquals(code, started.requestCode);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.getAction());
        assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals(mime, started.intent.getType());
        return started;
    }
    private static void clickClickable(View view) {
        assertNotNull("Missing clickable view", view);
        while (!view.isClickable() && view.getParent() instanceof View) view = (View) view.getParent();
        assertTrue("No clickable parent", view.isClickable());
        assertTrue(view.performClick());
        idle();
    }
    private static View byText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = byText(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private static View byDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = byDescription(((ViewGroup) view).getChildAt(i), description);
            if (found != null) return found;
        }
        return null;
    }
    private static List<String> texts(View root) {
        List<String> values = new ArrayList<>();
        collectTexts(root, values);
        return values;
    }
    private static void collectTexts(View view, List<String> values) {
        if (view instanceof TextView) values.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            collectTexts(((ViewGroup) view).getChildAt(i), values);
    }
    private static String normalized(String value) { return value.replace('\u00a0', ' ').replace('\u202f', ' '); }
    private static void assertContains(View root, String expected) {
        for (String value : texts(root)) if (normalized(value).contains(expected)) return;
        fail("Missing text containing '" + expected + "' in " + texts(root));
    }
    private static void assertNotContains(View root, String text) {
        for (String value : texts(root)) assertFalse("Unexpected text " + text, normalized(value).contains(text));
    }
    private static AlertDialog dialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("No dialog", dialog);
        assertTrue("Dialog already dismissed", dialog.isShowing());
        return dialog;
    }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private ExecutorService worker() throws Exception {
        Field field = MainActivity.class.getDeclaredField("worker");
        field.setAccessible(true);
        return (ExecutorService) field.get(activity);
    }
    private void drainWorker() throws Exception {
        Field progress = MainActivity.class.getDeclaredField("activeProgress");
        progress.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            idle();
            long remaining = deadline - System.nanoTime();
            assertTrue("Document operation must complete within ten seconds", remaining > 0);
            worker().submit(() -> {}).get(remaining, TimeUnit.NANOSECONDS);
            idle();
        } while (progress.get(activity) != null);
    }
}
