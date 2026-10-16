package br.com.myfinancehub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import org.robolectric.fakes.RoboCursor;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Uses the actual document picker result, preview and SQLite report replacement. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR")
public final class B3ActivityTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String CSV = "Produto;Instituição;Código de Negociação;Quantidade;Valor Atualizado\r\n"
            + "Ação fictícia;Corretora fictícia;DEMO3;12,345;1.234,56\r\n";
    private Context context;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private FinanceStore store;
    private int documentSequence;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("myfinancehub.db");
        context.deleteDatabase("myfinancehub-notifications.db");
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        store = new FinanceStore(context);
        idle();
    }

    @After public void after() throws Exception {
        AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
        if (latest != null && latest.isShowing()) latest.dismiss();
        if (controller != null) {
            controller.pause().stop().destroy();
            Field field = MainActivity.class.getDeclaredField("worker");
            field.setAccessible(true);
            assertTrue("Activity database helpers must finish closing",
                    ((ExecutorService) field.get(activity)).awaitTermination(10, TimeUnit.SECONDS));
        }
        if (store != null) store.close();
        context.deleteDatabase("myfinancehub.db");
        context.deleteDatabase("myfinancehub-notifications.db");
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void reportIsAccessibleFromWalletAndDashboardWithoutInventedHoldings() {
        openB3();
        assertVisible("Posição B3");
        assertVisible("Importar posição B3");
        assertVisible("Como baixar na B3");
        assertNull(store.b3Snapshot());
        assertTrue(store.investments().isEmpty());
        assertEquals(0, store.totalBalance());

        tab("Painel");
        clickClickable(byDescription(screen(), "Posição B3"));
        assertVisible("Posição B3");
        assertVisible("Importar posição B3");
        assertNull(store.b3Snapshot());
    }

    @Test public void downloadGuideOpensOnlyTheOfficialInvestorWebsite() {
        openB3();
        clickText(screen(), "Como baixar na B3");
        AlertDialog guide = dialog();
        assertEquals("Abrir site oficial", guide.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        guide.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        assertEquals("https://www.investidor.b3.com.br/", started.getDataString());
        assertNull("The guide must not create a local custody report", store.b3Snapshot());
    }

    @Test public void pickerPreviewRequiresAnExplicitPositionDateBeforeWritingAnyData() throws Exception {
        seedManualMoney();
        AtomicBoolean closed = deliverCsv(CSV);
        assertTrue("The selected document stream must be closed", closed.get());
        AlertDialog preview = dialog();
        assertContains(preview.getWindow().getDecorView(), "Revisar posição B3");
        assertContains(preview.getWindow().getDecorView(), "Ação fictícia");
        assertContains(preview.getWindow().getDecorView(), "1.234,56");
        assertEquals("", date(preview).getText().toString());
        assertNull("Preview must not persist holdings", store.b3Snapshot());

        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue("Missing valuation date must keep the preview open", preview.isShowing());
        assertNull(store.b3Snapshot());
        AlertDialog validation = ShadowAlertDialog.getLatestAlertDialog();
        if (validation != preview && validation != null && validation.isShowing()) {
            validation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
        }
        date(preview).setText(displayDate());
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();

        B3Portfolio.Snapshot report = store.b3Snapshot();
        assertNotNull(report);
        assertEquals(LocalDate.now().toString(), report.asOfDate);
        assertEquals(1, report.positions.size());
        assertEquals("DEMO3", report.positions.get(0).ticker);
        assertEquals("12.345", report.positions.get(0).quantity);
        assertEquals(Long.valueOf(123456), report.positions.get(0).currentCents);
        assertEquals(123456, report.totalCents());
        assertEquals(0, report.unvaluedCount());
        assertVisible("Posição B3");
        assertContains(screen(), "Ação fictícia");
        assertManualMoney();
        tab("Painel");
        assertContains(screen(), "R$ 987,65");
        assertContains(screen(), "R$ 4.321,00");
        assertManualMoney();
    }

    @Test public void importingAgainReplacesTheReportInsteadOfAddingItsValues() throws Exception {
        importCsv(CSV);
        String firstHash = store.b3Snapshot().sourceHash;
        importCsv(CSV);
        assertEquals(firstHash, store.b3Snapshot().sourceHash);
        assertEquals(1, store.b3Snapshot().positions.size());
        assertEquals(123456, store.b3Snapshot().totalCents());

        importCsv(CSV.replace("12,345;1.234,56", "2;200,00"));
        assertNotEquals(firstHash, store.b3Snapshot().sourceHash);
        assertEquals(1, store.b3Snapshot().positions.size());
        assertEquals("2", store.b3Snapshot().positions.get(0).quantity);
        assertEquals(20000, store.b3Snapshot().totalCents());
        assertTrue(store.investments().isEmpty());
        assertEquals(0, store.totalBalance());
    }

    @Test public void entirelyUnvaluedReportShowsUnknownRatherThanAZeroValuation() throws Exception {
        deliverCsv(CSV.replace("1.234,56", ""));
        AlertDialog preview = dialog();
        assertContains(preview.getWindow().getDecorView(), "Valor atual não informado");
        assertNotContains(preview.getWindow().getDecorView(), "R$ 0,00");
        date(preview).setText(displayDate());
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();

        B3Portfolio.Snapshot report = store.b3Snapshot();
        assertEquals(1, report.positions.size());
        assertNull(report.positions.get(0).currentCents);
        assertEquals(1, report.unvaluedCount());
        assertVisible("Posição B3");
        assertContains(screen(), "Não informado");
        assertContains(screen(), "Valor não informado");
        assertNotContains(screen(), "R$ 0,00");
    }

    @Test public void anOlderPositionRequiresASecondConfirmationAndCancellationKeepsTheReview() throws Exception {
        seedManualMoney();
        seedReport();
        String original = store.exportBackup();
        LocalDate older = LocalDate.now().minusDays(2);
        String olderDisplay = older.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String datedCsv = "Produto;Instituição;Código de Negociação;Quantidade;Valor Atualizado;Data da posição\n"
                + "Ação fictícia;Corretora fictícia;DEMO3;12,345;1.234,56;" + olderDisplay + "\n";
        deliverCsv(datedCsv);
        AlertDialog preview = dialog();
        assertEquals(olderDisplay, date(preview).getText().toString());
        assertFalse("A date read from the report must not be silently edited", date(preview).isEnabled());
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();

        AlertDialog warning = dialog();
        assertNotSame(preview, warning);
        assertContains(warning.getWindow().getDecorView(), "Substituir por posição anterior?");
        assertEquals(original, store.exportBackup());
        warning.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertTrue("Cancelling the replacement warning must retain the review", preview.isShowing());
        assertEquals(original, store.exportBackup());

        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        warning = dialog();
        assertEquals("Substituir", warning.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        warning.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();
        assertFalse(preview.isShowing());
        assertEquals(older.toString(), store.b3Snapshot().asOfDate);
        assertEquals(1, store.b3Snapshot().positions.size());
        assertEquals("DEMO3", store.b3Snapshot().positions.get(0).ticker);
        assertManualMoney();
    }

    @Test public void cancelledPreviewAndMalformedFilePreserveThePreviousReportAndManualMoney() throws Exception {
        seedManualMoney();
        seedReport();
        String original = store.exportBackup();
        deliverCsv(CSV);
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertEquals("Cancelling a review must not replace existing positions", original, store.exportBackup());

        deliverCsv("data;descrição;valor\n01/01/2026;Extrato bancário fictício;-10,00\n");
        assertTrue("An invalid custody file should produce a visible error", dialog().isShowing());
        assertEquals("A parser error must leave the old report intact", original, store.exportBackup());
        assertEquals("OLD3", store.b3Snapshot().positions.get(0).ticker);
        assertManualMoney();
    }

    @Test public void pickerCancellationDoesNotReadTheDocumentOrModifyTheExistingReport() throws Exception {
        seedReport();
        String original = store.exportBackup();
        Uri uri = Uri.parse("content://b3-tests.documents/document/cancelled");
        AtomicInteger opened = new AtomicInteger();
        shadowOf(activity.getContentResolver()).registerInputStreamSupplier(uri, () -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_8));
        });
        shadowOf(activity).receiveResult(launchPicker(), Activity.RESULT_CANCELED, new Intent().setData(uri));
        drainWorker();
        shadowOf(activity).receiveResult(launchPicker(), Activity.RESULT_OK, new Intent());
        drainWorker();
        assertEquals(0, opened.get());
        assertEquals(original, store.exportBackup());
    }

    @Test public void removingTheB3ReportRequiresConfirmationAndLeavesManualInvestmentsUntouched() throws Exception {
        seedManualMoney();
        seedReport();
        openB3();
        clickText(screen(), "Remover relatório B3");
        assertContains(dialog().getWindow().getDecorView(), "Remover posição B3?");
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertNotNull(store.b3Snapshot());
        assertManualMoney();

        clickText(screen(), "Remover relatório B3");
        assertEquals("Remover", dialog().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();
        assertNull(store.b3Snapshot());
        assertVisible("Importar posição B3");
        assertManualMoney();
    }

    @Test public void hiddenValuesHideTheReportedAmountAndQuantityWithoutChangingStoredData() {
        seedReport();
        openB3();
        assertContains(screen(), "1.234,56");
        assertContains(screen(), "12,345");
        String original = store.exportBackup();
        clickClickable(byDescription(screen(), "Ocultar valores"));
        assertNotNull(byDescription(screen(), "Mostrar valores"));
        assertNotContains(screen(), "1.234,56");
        assertNotContains(screen(), "12,345");
        assertNotContains(screen(), "12.345");
        assertContains(screen(), "Ação fictícia");
        assertEquals(original, store.exportBackup());
        clickClickable(byDescription(screen(), "Detalhes de Ação fictícia"));
        assertNotContains(dialog().getWindow().getDecorView(), "1.234,56");
        assertNotContains(dialog().getWindow().getDecorView(), "12,345");
        assertNotContains(dialog().getWindow().getDecorView(), "12.345");
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        clickClickable(byDescription(screen(), "Mostrar valores"));
        assertContains(screen(), "1.234,56");
        assertContains(screen(), "12,345");
        assertEquals(original, store.exportBackup());
    }

    @Test @Config(sdk = 35, qualifiers = "pt-rBR-notnight")
    public void reportSurvivesLightDarkRecreationWithoutChangingItsValuation() throws Exception {
        seedReport();
        openB3();
        String original = store.exportBackup();
        assertFalse(AppAppearance.isDark(activity));
        AppAppearance.setMode(activity, AppAppearance.Mode.DARK);
        controller.recreate();
        activity = controller.get();
        idle();
        assertTrue(AppAppearance.isDark(activity));
        assertVisible("Posição B3");
        assertContains(screen(), "Ação fictícia");
        assertContains(screen(), "1.234,56");
        assertEquals(original, store.exportBackup());
        AppAppearance.setMode(activity, AppAppearance.Mode.LIGHT);
        controller.recreate();
        activity = controller.get();
        idle();
        assertFalse(AppAppearance.isDark(activity));
        assertVisible("Posição B3");
        assertEquals(original, store.exportBackup());
    }

    private void seedManualMoney() {
        Models.Account account = store.accounts().get(0);
        account.openingCents = 98765;
        store.saveAccount(account);
        store.saveInvestment(new Models.Investment(0, "Investimento manual preservado", "MANUAL", "Renda fixa", 400000, 432100));
    }
    private void assertManualMoney() {
        assertEquals(98765, store.totalBalance());
        assertTrue(store.entries().isEmpty());
        assertEquals(1, store.investments().size());
        assertEquals("Investimento manual preservado", store.investments().get(0).name);
        assertEquals(400000, store.investments().get(0).investedCents);
        assertEquals(432100, store.investments().get(0).currentCents);
    }
    private void seedReport() {
        B3Portfolio.Position row = new B3Portfolio.Position("Ação fictícia", "Corretora fictícia", "", "OLD3", "", "", "12.345", 123456L);
        store.replaceB3Snapshot(Collections.singletonList(row), LocalDate.now().toString(), HASH);
    }
    private void openB3() {
        tab("Carteira");
        clickClickable(byDescription(screen(), "Abrir posição B3"));
        assertVisible("Posição B3");
    }
    private Intent launchPicker() {
        openB3();
        clickText(screen(), "Importar posição B3");
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull("B3 import must use Android's document picker", started);
        assertEquals(45, started.requestCode);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.getAction());
        assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        return started.intent;
    }
    private AtomicBoolean deliverCsv(String contents) throws Exception {
        Uri uri = Uri.parse("content://b3-tests.documents/document/position-" + (++documentSequence));
        AtomicBoolean closed = new AtomicBoolean();
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        shadowOf(activity.getContentResolver()).registerInputStreamSupplier(uri, () -> new ByteArrayInputStream(bytes) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        });
        RoboCursor cursor = new RoboCursor();
        cursor.setColumnNames(Collections.singletonList(OpenableColumns.DISPLAY_NAME));
        cursor.setResults(new Object[][] {{"posicao.csv"}});
        shadowOf(activity.getContentResolver()).setCursor(uri, cursor);
        shadowOf(activity).receiveResult(launchPicker(), Activity.RESULT_OK,
                new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        drainWorker();
        return closed;
    }
    private void importCsv(String contents) throws Exception {
        deliverCsv(contents);
        AlertDialog preview = dialog();
        date(preview).setText(displayDate());
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();
        assertFalse("Confirmed preview must close", preview.isShowing());
    }
    private static String displayDate() { return LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")); }
    private static EditText date(AlertDialog preview) {
        View field = byDescription(preview.getWindow().getDecorView(), "Data da posição (dd/MM/aaaa)");
        assertTrue("The review must expose its position date", field instanceof EditText);
        return (EditText) field;
    }
    private View screen() { return activity.findViewById(android.R.id.content); }
    private void tab(String name) { clickClickable(byDescription(screen(), name)); }
    private void assertVisible(String text) { assertNotNull("Missing text: " + text, byText(screen(), text)); }
    private static void assertContains(View root, String text) {
        List<String> texts = texts(root);
        for (String value : texts) if (normalized(value).contains(text)) return;
        fail("Missing text containing '" + text + "' in " + texts);
    }
    private static void assertNotContains(View root, String text) {
        for (String value : texts(root)) assertFalse("Sensitive text remains visible: " + text, normalized(value).contains(text));
    }
    private static String normalized(String value) { return value.replace('\u00a0', ' ').replace('\u202f', ' '); }
    private static List<String> texts(View root) {
        List<String> values = new ArrayList<>();
        collectTexts(root, values);
        return values;
    }
    private static void collectTexts(View view, List<String> values) {
        if (view instanceof TextView) values.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectTexts(((ViewGroup) view).getChildAt(i), values);
    }
    private static View byText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View match = byText(((ViewGroup) view).getChildAt(i), text);
            if (match != null) return match;
        }
        return null;
    }
    private static View byDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View match = byDescription(((ViewGroup) view).getChildAt(i), description);
            if (match != null) return match;
        }
        return null;
    }
    private static void clickText(View root, String text) {
        View view = byText(root, text);
        assertNotNull("Missing clickable text " + text, view);
        clickClickable(view);
    }
    private static void clickClickable(View view) {
        assertNotNull("Missing clickable view", view);
        while (!view.isClickable() && view.getParent() instanceof View) view = (View) view.getParent();
        assertTrue("No clickable parent", view.isClickable());
        assertTrue(view.performClick());
        idle();
    }
    private static AlertDialog dialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("No dialog", dialog);
        assertTrue("Dialog already dismissed", dialog.isShowing());
        return dialog;
    }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private void drainWorker() throws Exception {
        Field worker = MainActivity.class.getDeclaredField("worker");
        worker.setAccessible(true);
        ExecutorService executor = (ExecutorService) worker.get(activity);
        Field progress = MainActivity.class.getDeclaredField("activeProgress");
        progress.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            idle();
            long remaining = deadline - System.nanoTime();
            assertTrue("Document operation must complete within ten seconds", remaining > 0);
            executor.submit(() -> {}).get(remaining, TimeUnit.NANOSECONDS);
            idle();
        } while (progress.get(activity) != null);
    }
}
