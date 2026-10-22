package br.com.myfinancehub;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
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
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real notification review dialogs backed by the separate inbox and financial databases. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR")
public final class NotificationFlowTest {
    private static final String SOURCE_PACKAGE = "com.example.finance";
    private Context context;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private FinanceStore store;
    private NotificationInbox inbox;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("myfinancehub.db");
        context.deleteDatabase("myfinancehub-notifications.db");
        store = new FinanceStore(context);
        inbox = new NotificationInbox(context);
    }

    @After public void after() throws Exception {
        AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
        if (latest != null && latest.isShowing()) latest.dismiss();
        if (controller != null) {
            controller.pause().stop().destroy();
            Field field = MainActivity.class.getDeclaredField("worker");
            field.setAccessible(true);
            assertTrue("Activity must finish closing its database helpers",
                    ((ExecutorService) field.get(activity)).awaitTermination(10, TimeUnit.SECONDS));
        }
        if (store != null) store.close();
        if (inbox != null) inbox.close();
        if (context != null) {
            context.deleteDatabase("myfinancehub.db");
            context.deleteDatabase("myfinancehub-notifications.db");
        }
    }

    @Test public void freshInstallLeavesCaptureOffUntilTheUserConfiguresIt() {
        assertFalse(inbox.isEnabled());
        assertTrue(inbox.allowedPackages().isEmpty());
        start();
        openNotifications();
        View toggle = byDescription(screen(), "Capturar novas notificações");
        assertTrue(toggle instanceof Switch);
        assertFalse(((Switch) toggle).isChecked());
        assertFalse(toggle.isEnabled());
        assertContains(screen(), "CAPTURA PAUSADA");
        assertContains(screen(), "Nenhuma sugestão pendente");
        assertNull("Opening the screen must not request Android access automatically",
                shadowOf(activity).getNextStartedActivity());
        assertTrue(store.entries().isEmpty());
        assertEquals(0, store.totalBalance());
    }

    @Test public void pendingSuggestionIsVisibleFromTransactionsAndDoesNotChangeTheLedger() {
        NotificationInbox.Candidate candidate = enqueuePix("dashboard-alert");
        start();
        click(byDescription(screen(),"Lançamentos"));
        assertContains(screen(), "Revisar notificações (1)");
        click(byDescription(screen(), "Revisar notificações (1)"));
        assertContains(screen(), "Para revisar (1)");
        assertContains(screen(), "PIX recebido");
        assertContains(screen(), "R$ 25,90");
        assertNotNull(byDescription(screen(), "Revisar notificação " + candidate.id));
        assertEquals(1, inbox.pendingCount());
        assertTrue(store.entries().isEmpty());
        assertEquals(0, store.totalBalance());
    }

    @Test public void cancellingWarningAndThenFormKeepsTheSuggestionAndDoesNotSaveMoney() {
        NotificationInbox.Candidate candidate = enqueuePix("cancelled-alert");
        start();
        openNotifications();
        click(byDescription(screen(), "Revisar notificação " + candidate.id));
        clickButton(dialog(), AlertDialog.BUTTON_NEGATIVE);
        assertEquals(1, inbox.pendingCount());
        assertTrue(store.entries().isEmpty());

        AlertDialog form = review(candidate);
        assertContains(form.getWindow().getDecorView(), "Novo lançamento");
        select(form, "Conta", "Principal");
        edit(form, "Descrição").setText("Rascunho que não deve ser gravado");
        clickButton(form, AlertDialog.BUTTON_NEGATIVE);
        assertFalse(form.isShowing());
        assertEquals(candidate.id, inbox.pending().get(0).id);
        assertTrue(store.entries().isEmpty());
        assertEquals(0, store.totalBalance());

        form = review(candidate);
        assertEquals("PIX recebido", edit(form, "Descrição").getText().toString());
        assertEquals("Escolha uma conta", spinner(form, "Conta").getSelectedItem().toString());
        clickButton(form, AlertDialog.BUTTON_NEGATIVE);
    }

    @Test public void confirmingPixRequiresAnExplicitAccountAndPersistsExactlyOneEntry() {
        NotificationInbox.Candidate candidate = enqueuePix("confirmed-alert");
        start();
        openNotifications();
        AlertDialog form = review(candidate);
        assertEquals("Escolha uma conta", spinner(form, "Conta").getSelectedItem().toString());
        assertEquals("25,90", edit(form, "Valor (R$)").getText().toString());
        assertEquals(LocalDate.now().toString(), edit(form, "Data").getText().toString());
        assertEquals("Entrada", spinner(form, "Tipo").getSelectedItem().toString());
        assertTrue(spinner(form, "Forma de pagamento").getSelectedItem().toString().startsWith("PIX"));

        clickButton(form, AlertDialog.BUTTON_POSITIVE);
        AlertDialog error = dialog();
        assertNotSame(form, error);
        assertContains(error.getWindow().getDecorView(), "Escolha a conta deste lançamento antes de salvar");
        assertTrue(form.isShowing());
        assertEquals(1, inbox.pendingCount());
        assertTrue(store.entries().isEmpty());
        assertEquals(0, store.totalBalance());
        clickButton(error, AlertDialog.BUTTON_POSITIVE);

        select(form, "Conta", "Principal");
        clickButton(form, AlertDialog.BUTTON_POSITIVE);
        confirmPending(form);
        assertFalse(form.isShowing());
        assertEquals(1, store.entries().size());
        Models.Entry saved = store.entries().get(0);
        assertEquals("PIX recebido", saved.description);
        assertEquals("income", saved.type);
        assertEquals("pix", saved.method);
        assertEquals(store.accounts().get(0).id, saved.accountId);
        assertEquals(candidate.sourceKey, saved.sourceKey);
        assertEquals(2590, saved.amountCents);
        assertEquals(2590, store.totalBalance());
        assertEquals(0, inbox.pendingCount());
        assertTrue(store.hasNotificationSource(candidate.sourceKey));
        assertContains(screen(), "Nenhuma sugestão pendente");

        controller.recreate();
        activity = controller.get();
        idle();
        assertEquals(1, store.entries().size());
        assertEquals(2590, store.totalBalance());
        assertEquals(0, inbox.pendingCount());
    }

    @Test public void unknownCreditCardRequiresUserSelectionAndDoesNotReduceCashBalance() {
        Models.Card registered = new Models.Card(0, "Cartão cadastrado", "9876", 25, 5);
        store.saveCard(registered);
        NotificationInbox.Candidate candidate = enqueuePurchase("unknown-credit-card");
        start();
        openNotifications();
        AlertDialog form = review(candidate);
        assertEquals("Crédito", spinner(form, "Forma de pagamento").getSelectedItem().toString());
        assertEquals("Selecione um cartão", spinner(form, "Cartão (somente para crédito)").getSelectedItem().toString());
        select(form, "Conta", "Principal");
        clickButton(form, AlertDialog.BUTTON_POSITIVE);
        AlertDialog error = dialog();
        assertNotSame(form, error);
        assertContains(error.getWindow().getDecorView(), "Selecione um cartão cadastrado");
        assertTrue(form.isShowing());
        assertTrue(store.entries().isEmpty());
        assertEquals(1, inbox.pendingCount());
        assertEquals(0, store.totalBalance());
        clickButton(error, AlertDialog.BUTTON_POSITIVE);

        select(form, "Cartão (somente para crédito)", "Cartão cadastrado · 9876");
        clickButton(form, AlertDialog.BUTTON_POSITIVE);
        confirmPending(form);
        assertFalse(form.isShowing());
        assertEquals(1, store.entries().size());
        Models.Entry saved = store.entries().get(0);
        assertEquals("credit", saved.method);
        assertEquals(registered.id, saved.cardId);
        assertEquals(2590, saved.amountCents);
        assertEquals(0, store.totalBalance());
        assertEquals(0, inbox.pendingCount());
    }

    @Test public void aUniqueLastFourMatchSuggestsTheCardButNeverChoosesTheAccount() {
        Models.Card card = new Models.Card(0, "Cartão pessoal", "1234", 25, 5);
        store.saveCard(card);
        NotificationInbox.Candidate candidate = enqueuePurchase("matched-credit-card");
        start();
        openNotifications();
        AlertDialog form = review(candidate);
        assertEquals("Cartão pessoal · 1234", spinner(form, "Cartão (somente para crédito)").getSelectedItem().toString());
        assertEquals("Escolha uma conta", spinner(form, "Conta").getSelectedItem().toString());
        assertTrue(store.entries().isEmpty());
        assertEquals(0, store.totalBalance());
        select(form, "Conta", "Principal");
        clickButton(form, AlertDialog.BUTTON_POSITIVE);
        confirmPending(form);
        assertEquals(card.id, store.entries().get(0).cardId);
        assertEquals(0, inbox.pendingCount());
        assertEquals(0, store.totalBalance());
    }

    @Test public void ambiguousLastFourNeverSelectsAnArbitraryCard() {
        store.saveCard(new Models.Card(0, "Cartão A", "1234", 20, 5));
        store.saveCard(new Models.Card(0, "Cartão B", "1234", 25, 10));
        NotificationInbox.Candidate candidate = enqueuePurchase("ambiguous-credit-card");
        start();
        openNotifications();
        AlertDialog form = review(candidate);
        assertEquals("Selecione um cartão", spinner(form, "Cartão (somente para crédito)").getSelectedItem().toString());
        assertTrue(store.entries().isEmpty());
        assertEquals(1, inbox.pendingCount());
        clickButton(form, AlertDialog.BUTTON_NEGATIVE);
    }

    @Test public void purchaseWithoutCreditOrDebitRequiresAnExplicitPaymentMethod() {
        NotificationInbox.Candidate candidate = enqueue("unknown-payment-method", "Compra aprovada",
                "Compra de R$ 25,90 no cartão final 1234.");
        start();
        openNotifications();
        AlertDialog form = review(candidate);
        assertEquals("Escolha a forma de pagamento", spinner(form, "Forma de pagamento").getSelectedItem().toString());
        select(form, "Conta", "Principal");
        clickButton(form, AlertDialog.BUTTON_POSITIVE);
        AlertDialog error = dialog();
        assertNotSame(form, error);
        assertContains(error.getWindow().getDecorView(), "Escolha a forma de pagamento. Confira se foi crédito ou débito.");
        assertTrue(form.isShowing());
        assertTrue(store.entries().isEmpty());
        assertEquals(1, inbox.pendingCount());
        assertEquals(0, store.totalBalance());
        clickButton(error, AlertDialog.BUTTON_POSITIVE);
        select(form, "Forma de pagamento", "Débito");
        clickButton(form, AlertDialog.BUTTON_POSITIVE);
        confirmPending(form);
        assertFalse(form.isShowing());
        assertEquals(1, store.entries().size());
        assertEquals("debit", store.entries().get(0).method);
        assertEquals(0, store.entries().get(0).cardId);
        assertEquals(-2590, store.totalBalance());
        assertEquals(0, inbox.pendingCount());
    }

    @Test public void discardRemovesOnlyTheSuggestionAndKeepsExistingFinancialData() {
        Models.Entry existing = new Models.Entry(0, store.accounts().get(0).id, 0, 0,
                "Lançamento preservado", LocalDate.now().toString(), "expense", "pix", "", 1000);
        store.saveEntry(existing);
        NotificationInbox.Candidate candidate = enqueuePix("discard-alert");
        String original = store.exportBackup();
        start();
        openNotifications();
        click(byDescription(screen(), "Descartar notificação " + candidate.id));
        assertEquals(0, inbox.pendingCount());
        assertEquals(original, store.exportBackup());
        assertEquals(-1000, store.totalBalance());
        assertContains(screen(), "Nenhuma sugestão pendente");
    }

    @Test public void existingStatementMatchShowsADuplicateWarningBeforeOpeningTheForm() {
        store.saveEntry(new Models.Entry(0, store.accounts().get(0).id, 0, 0,
                "PIX importado do extrato", LocalDate.now().toString(), "income", "pix", "statement:example", 2590));
        NotificationInbox.Candidate candidate = enqueuePix("possible-statement-duplicate");
        start();
        openNotifications();
        click(byDescription(screen(), "Revisar notificação " + candidate.id));
        assertContains(dialog().getWindow().getDecorView(), "Já existe um lançamento com a mesma data, tipo e valor");
        assertEquals(1, store.entries().size());
        assertEquals(1, inbox.pendingCount());
        clickButton(dialog(), AlertDialog.BUTTON_NEGATIVE);
        assertEquals(2590, store.totalBalance());
        assertEquals(1, inbox.pendingCount());
    }

    @Test public void committedNotificationIsRemovedFromTheInboxAfterAnInterruptedReview() {
        NotificationInbox.Candidate candidate = enqueuePix("interrupted-review");
        Models.Entry committed = new Models.Entry(0, store.accounts().get(0).id, 0, 0,
                candidate.description, candidate.date, candidate.type, candidate.method, candidate.sourceKey,
                candidate.amountCents);
        store.saveReviewedNotification(committed);
        assertEquals("Simulate a process stopping between financial commit and inbox cleanup", 1, inbox.pendingCount());
        start();
        openNotifications();
        assertContains(screen(), "Nenhuma sugestão pendente");
        assertNull(byDescription(screen(), "Revisar notificação " + candidate.id));
        assertEquals(0, inbox.pendingCount());
        assertEquals(1, store.entries().size());
        assertEquals(2590, store.totalBalance());

        inbox.clearHistory();
        NotificationInbox.Candidate replay = enqueue("interrupted-review", "PIX recebido",
                "Você recebeu um Pix de R$ 25,90.", candidate.postedAt);
        assertEquals("A redelivery preserves the original post time and source identity",
                candidate.sourceKey, replay.sourceKey);
        click(byText(screen(), "Atualizar lista"));
        assertEquals("Replaying the same source cannot duplicate a saved notification", 0, inbox.pendingCount());
        assertEquals(1, store.entries().size());
        assertEquals(2590, store.totalBalance());
    }

    private NotificationInbox.Candidate enqueuePix(String key) {
        return enqueue(key, "PIX recebido", "Você recebeu um Pix de R$ 25,90.");
    }

    private NotificationInbox.Candidate enqueuePurchase(String key) {
        return enqueue(key, "Compra aprovada", "Compra de R$ 25,90 no cartão de crédito final 1234.");
    }

    private NotificationInbox.Candidate enqueue(String key, String title, String body) {
        return enqueue(key, title, body, System.currentTimeMillis());
    }

    private NotificationInbox.Candidate enqueue(String key, String title, String body, long postedAt) {
        inbox.setAllowedPackages(Collections.singleton(SOURCE_PACKAGE));
        inbox.setEnabled(true);
        NotificationParser.Suggestion suggestion = NotificationParser.parse(title, body, postedAt, ZoneId.systemDefault());
        assertNotNull("Fictitious notification must use a supported, explicit transaction format", suggestion);
        assertTrue(inbox.enqueue(SOURCE_PACKAGE, "Banco de demonstração", key, postedAt, suggestion));
        for (NotificationInbox.Candidate candidate : inbox.pending()) {
            if (candidate.description.equals(suggestion.description) && candidate.postedAt == postedAt) return candidate;
        }
        throw new AssertionError("Enqueued suggestion was not persisted");
    }

    private void start() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        idle();
    }

    private void openNotifications() {
        click(byDescription(screen(), "Ajustes"));
        click(byDescription(screen(), "Notificações de transações"));
        assertContains(screen(), "LEITURA OPCIONAL E LOCAL");
    }

    private AlertDialog review(NotificationInbox.Candidate candidate) {
        click(byDescription(screen(), "Revisar notificação " + candidate.id));
        AlertDialog warning = dialog();
        assertEquals("Conferir dados", warning.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        clickButton(warning, AlertDialog.BUTTON_POSITIVE);
        AlertDialog form = dialog();
        assertNotSame(warning, form);
        return form;
    }

    private View screen() { return activity.findViewById(android.R.id.content); }

    private static AlertDialog dialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("No dialog", dialog);
        assertTrue("Latest dialog has already been dismissed", dialog.isShowing());
        return dialog;
    }

    private void confirmPending(AlertDialog form) {
        AlertDialog confirmation=dialog();assertNotSame(form,confirmation);
        assertEquals("Confirmar e salvar",confirmation.getButton(-1).getText().toString());
        assertTrue(form.isShowing());assertTrue(store.entries().isEmpty());assertEquals(1,inbox.pendingCount());
        clickButton(confirmation,AlertDialog.BUTTON_NEGATIVE);
        assertTrue(form.isShowing());assertTrue(store.entries().isEmpty());assertEquals(1,inbox.pendingCount());
        clickButton(form,AlertDialog.BUTTON_POSITIVE);clickButton(dialog(),AlertDialog.BUTTON_POSITIVE);
    }

    private static void clickButton(AlertDialog dialog, int button) {
        assertNotNull(dialog.getButton(button));
        assertTrue(dialog.getButton(button).performClick());
        idle();
    }

    private static void click(View view) {
        assertNotNull("Missing clickable view", view);
        while (!view.isClickable() && view.getParent() instanceof View) view = (View) view.getParent();
        assertTrue("No clickable parent", view.isClickable());
        assertTrue(view.performClick());
        idle();
    }

    private static EditText edit(AlertDialog dialog, String label) {
        View view = byDescription(dialog.getWindow().getDecorView(), label);
        assertTrue("Missing EditText " + label, view instanceof EditText);
        return (EditText) view;
    }

    private static Spinner spinner(AlertDialog dialog, String label) {
        View view = byDescription(dialog.getWindow().getDecorView(), label);
        assertTrue("Missing Spinner " + label, view instanceof Spinner);
        return (Spinner) view;
    }

    private static void select(AlertDialog dialog, String label, String item) {
        Spinner spinner = spinner(dialog, label);
        for (int i = 0; i < spinner.getCount(); i++) {
            if (item.equals(spinner.getItemAtPosition(i).toString())) {
                spinner.setSelection(i);
                idle();
                return;
            }
        }
        fail("Missing option " + item + " in " + label);
    }

    private static View byDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = byDescription(((ViewGroup) view).getChildAt(i), description);
            if (found != null) return found;
        }
        return null;
    }

    private static View byText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = byText(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private static void assertContains(View root, String expected) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        for (String text : texts) if (text.replace('\u00a0', ' ').replace('\u202f', ' ').contains(expected)) return;
        fail("Missing text containing '" + expected + "' in " + texts);
    }

    private static void collectTexts(View view, List<String> texts) {
        if (view instanceof TextView) texts.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            collectTexts(((ViewGroup) view).getChildAt(i), texts);
        }
    }

    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
}
