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
import org.robolectric.fakes.RoboCursor;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
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

/** Exercise the actual dialogs and SQLite integration, without inserting sample data in the app. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "pt-rBR")
public final class MainActivityTest {
    private Context context;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private FinanceStore store;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
        context.deleteDatabase("myfinancehub.db");
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
            assertTrue("Activity must finish closing its database helpers",
                    ((ExecutorService) field.get(activity)).awaitTermination(10, TimeUnit.SECONDS));
        }
        if (store != null) store.close();
        context.deleteDatabase("myfinancehub.db");
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void emptyInstallNavigatesAllFiveTabsWithoutInventedMoney() {
        assertVisible("Seu dinheiro");
        assertVisible("SALDO NAS CONTAS");
        assertVisible("Investimentos");
        assertVisible("VALOR ATUAL");
        assertNull(byDescription(screen(), "Importar extrato"));
        assertNull(byText(screen(), "+  Novo lançamento"));
        assertNull(byText(screen(), "↗  Entradas"));
        assertNull(byText(screen(), "↘  Saídas"));
        assertNull(byText(screen(), "Últimos lançamentos"));
        assertEquals(0, store.totalBalance());
        assertTrue(store.entries().isEmpty());
        assertTrue(store.cards().isEmpty());
        assertTrue(store.investments().isEmpty());

        tab("Lançamentos");
        assertVisible("Seus lançamentos");
        assertVisible("Nenhum lançamento encontrado");
        tab("Relatórios");
        assertVisible("RESULTADO DO PERÍODO");
        assertVisible("Gastos por categoria");
        tab("Carteira");
        assertVisible("Sua carteira");
        assertVisible("Principal");
        assertVisible("Cada cartão no seu lugar");
        openCategories();
        assertVisible("+ Nova categoria");
        assertNotNull(byDescription(screen(), "Buscar categoria"));
        tab("Ajustes");
        assertVisible("Organize sua rotina");
        assertVisible("Gerenciar categorias  →");
        tab("Painel");
        assertVisible("Seu dinheiro");
        assertTrue(store.entries().isEmpty());
    }

    @Test @Config(sdk = 35, qualifiers = "pt-rBR-notnight")
    public void android15OpensAndNavigatesInLightAndDarkModesWithoutChangingFinancialData() {
        String original = store.exportBackup();
        assertFalse(AppAppearance.isDark(activity));
        assertVisible("Seu dinheiro");
        assertVisible("SALDO NAS CONTAS");
        tab("Lançamentos");
        assertVisible("Seus lançamentos");
        assertNotNull(byDescription(screen(), "Novo lançamento"));
        assertNotNull(byDescription(screen(), "Importar extrato"));

        AppAppearance.setMode(activity, AppAppearance.Mode.DARK);
        controller.recreate();
        activity = controller.get();
        idle();
        assertTrue(AppAppearance.isDark(activity));
        assertVisible("Seus lançamentos");
        tab("Painel");
        assertVisible("Seu dinheiro");
        assertVisible("Investimentos");
        tab("Lançamentos");
        assertVisible("Seus lançamentos");
        assertNotNull(byDescription(screen(), "Importar extrato"));
        assertEquals(original, store.exportBackup());
        assertTrue(store.entries().isEmpty());
        assertEquals(0, store.totalBalance());
    }

    @Test public void categoryAndEntryCrudThroughDialogsPreservesUncategorizedEntry() {
        openCategories();
        clickText(screen(), "+ Nova categoria");
        AlertDialog categoryDialog = dialog();
        edit(categoryDialog, "Nome").setText("Cafeteria");
        save(categoryDialog);
        Models.Category category = category("Cafeteria");
        assertEquals("expense", category.type);

        tab("Lançamentos");
        clickText(screen(), "+  Novo lançamento");
        AlertDialog entryDialog = dialog();
        assertEquals(LocalDate.now().toString(), edit(entryDialog, "Data").getText().toString());
        edit(entryDialog, "Descrição").setText("Café da tarde");
        edit(entryDialog, "Valor (R$)").setText("12,34");
        select(entryDialog, "Categoria", "Cafeteria");
        selectPrefix(entryDialog, "Forma de pagamento", "PIX");
        save(entryDialog);
        assertEquals(1, store.entries().size());
        Models.Entry entry = store.entries().get(0);
        assertEquals(1234, entry.amountCents);
        assertEquals(category.id, entry.categoryId);
        assertEquals("pix", entry.method);
        assertEquals(-1234, store.totalBalance());

        tab("Lançamentos");
        clickText(screen(), "Café da tarde");
        entryDialog = dialog();
        edit(entryDialog, "Descrição").setText("Café e lanche");
        edit(entryDialog, "Valor (R$)").setText("20,55");
        save(entryDialog);
        assertEquals(entry.id, store.entries().get(0).id);
        assertEquals(2055, store.entries().get(0).amountCents);
        assertEquals(-2055, store.totalBalance());
        assertVisible("Café e lanche");

        openCategories();
        clickClickable(byDescription(screen(), "Editar categoria Cafeteria"));
        categoryDialog = dialog();
        edit(categoryDialog, "Nome").setText("Café");
        save(categoryDialog);
        assertEquals(category.id, category("Café").id);
        assertEquals(category.id, store.entries().get(0).categoryId);

        clickClickable(byDescription(screen(), "Excluir categoria Café"));
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertEquals(1, store.entries().size());
        assertEquals(0, store.entries().get(0).categoryId);
        assertEquals(-2055, store.totalBalance());

        tab("Lançamentos");
        assertContains(screen(), "Sem categoria");
        clickText(screen(), "Café e lanche");
        dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        idle();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(store.entries().isEmpty());
        assertEquals(0, store.totalBalance());
        assertVisible("Nenhum lançamento encontrado");
    }

    @Test public void transactionsKeepCreationAndImportTogetherWithoutNavigatingToSettings() {
        assertVisible("Seu dinheiro");
        assertNull(byDescription(screen(), "Importar extrato"));
        tab("Lançamentos");
        assertNotNull(byDescription(screen(), "Novo lançamento"));
        clickClickable(byDescription(screen(), "Importar extrato"));
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull("Import should be accessible directly from transactions", started);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.getAction());
        assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals("*/*", started.intent.getType());
        assertTrue(store.entries().isEmpty());
        assertEquals(0, store.totalBalance());
    }

    @Test public void categorySearchLeavesDirectEditAndDeleteControlsForMatchingCategory() {
        store.saveCategory(new Models.Category(0, "Cuidados do pet", "expense"));
        store.saveCategory(new Models.Category(0, "Material escolar", "expense"));
        openCategories();
        View search = byDescription(screen(), "Buscar categoria");
        assertTrue(search instanceof EditText);
        ((EditText) search).setText("cuidados");
        idle();
        assertVisible("Cuidados do pet");
        assertNull(byText(screen(), "Material escolar"));
        clickClickable(byDescription(screen(), "Editar categoria Cuidados do pet"));
        AlertDialog form = dialog();
        edit(form, "Nome").setText("Pet e veterinário");
        save(form);
        assertNotNull(category("Pet e veterinário"));
        // Clear the current search because renaming can remove a row from its results.
        ((EditText) byDescription(screen(), "Buscar categoria")).setText("");
        idle();
        clickClickable(byDescription(screen(), "Excluir categoria Pet e veterinário"));
        AlertDialog confirmation = dialog();
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertNotNull(category("Pet e veterinário"));
        assertNotNull(category("Material escolar"));
    }

    @Test public void addingCategoryWithinEntryPreservesTheDraftAndSelectsTheNewCategory() {
        tab("Lançamentos");
        clickText(screen(), "+  Novo lançamento");
        AlertDialog entryForm = dialog();
        String date = LocalDate.now().minusDays(2).toString();
        edit(entryForm, "Descrição").setText("Pet shop");
        edit(entryForm, "Valor (R$)").setText("42,35");
        edit(entryForm, "Data").setText(date);
        selectPrefix(entryForm, "Forma de pagamento", "PIX");

        clickText(entryForm.getWindow().getDecorView(), "+ Nova categoria");
        AlertDialog categoryForm = dialog();
        assertNotSame(entryForm, categoryForm);
        edit(categoryForm, "Nome").setText("Cuidados do pet");
        save(categoryForm);

        assertTrue("Creating a category must leave the entry draft open", entryForm.isShowing());
        assertEquals("Pet shop", edit(entryForm, "Descrição").getText().toString());
        assertEquals("42,35", edit(entryForm, "Valor (R$)").getText().toString());
        assertEquals(date, edit(entryForm, "Data").getText().toString());
        Spinner categorySpinner = (Spinner) byDescription(entryForm.getWindow().getDecorView(), "Categoria");
        assertEquals("Cuidados do pet", categorySpinner.getSelectedItem().toString());
        assertTrue(store.entries().isEmpty());
        save(entryForm);

        assertEquals(1, store.entries().size());
        Models.Entry entry = store.entries().get(0);
        assertEquals("Pet shop", entry.description);
        assertEquals(4235, entry.amountCents);
        assertEquals(date, entry.date);
        assertEquals("pix", entry.method);
        assertEquals(category("Cuidados do pet").id, entry.categoryId);
        assertEquals(-4235, store.totalBalance());
    }

    @Test public void expenseRequiresReviewAndExplicitConfirmationAndBackPreservesDraft() {
        tab("Lançamentos");clickText(screen(), "+  Novo lançamento");
        AlertDialog form=dialog();edit(form,"Descrição").setText("Revisão obrigatória");edit(form,"Valor (R$)").setText("12,34");
        form.getButton(-1).performClick();idle();
        AlertDialog confirmation=dialog();assertTrue(form.isShowing());assertTrue(store.entries().isEmpty());
        assertContains(confirmation.getWindow().getDecorView(),"Revisão obrigatória");
        assertContains(confirmation.getWindow().getDecorView(),"R$ 12,34");
        confirmation.getButton(-2).performClick();idle();
        assertTrue(form.isShowing());assertTrue(store.entries().isEmpty());
        assertEquals("12,34",edit(form,"Valor (R$)").getText().toString());
        edit(form,"Valor (R$)").setText("56,78");form.getButton(-1).performClick();idle();
        assertTrue(store.entries().isEmpty());confirmation=dialog();
        assertContains(confirmation.getWindow().getDecorView(),"R$ 56,78");
        confirmation.getButton(-1).performClick();idle();assertEquals(1,store.entries().size());
        assertEquals(5678,store.entries().get(0).amountCents);
        assertEquals(-5678,store.totalBalance());
    }

    @Test public void screenOffRevokesAnExpenseConfirmationWithoutChangingSavedData() {
        String before=store.exportBackup();
        tab("Lançamentos");clickText(screen(), "+  Novo lançamento");
        AlertDialog form=dialog();edit(form,"Descrição").setText("Despesa antes do bloqueio");edit(form,"Valor (R$)").setText("12,34");
        form.getButton(-1).performClick();idle();AlertDialog confirmation=dialog();
        assertEquals("Confirmar e salvar",confirmation.getButton(-1).getText().toString());
        assertTrue(store.entries().isEmpty());assertTrue(AuthSession.isUnlocked());
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));idle();
        assertFalse(AuthSession.isUnlocked());assertTrue(activity.isFinishing());
        assertEquals(View.INVISIBLE,screen().getVisibility());
        // Retain the old button to exercise the save guard even before Android removes
        // the finishing Activity's dialog windows.
        confirmation.getButton(-1).performClick();idle();
        assertTrue(store.entries().isEmpty());assertEquals(before,store.exportBackup());
        assertFalse(confirmation.isShowing());assertFalse(form.isShowing());
    }

    @Test public void invalidEntryDoesNotPersistAndCanBeCorrectedWithoutLosingForm() {
        tab("Lançamentos");
        clickText(screen(), "+  Novo lançamento");
        AlertDialog form = dialog();
        edit(form, "Descrição").setText("Conta de luz");
        edit(form, "Valor (R$)").setText("0,00");
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        AlertDialog error = dialog();
        assertNotSame(form, error);
        assertTrue(form.isShowing());
        assertTrue(store.entries().isEmpty());
        error.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("Conta de luz", edit(form, "Descrição").getText().toString());
        edit(form, "Valor (R$)").setText("75,90");
        save(form);
        assertEquals(1, store.entries().size());
        assertEquals(7590, store.entries().get(0).amountCents);
        assertEquals(-7590, store.totalBalance());
    }

    @Test public void creditIdentifiesCardAndSurvivesActivityRecreationWithoutReducingCash() {
        tab("Carteira");
        clickText(screen(), "Principal");
        AlertDialog account = dialog();
        edit(account, "Saldo inicial (R$)").setText("1000,00");
        save(account);
        clickText(screen(), "+ Adicionar cartão");
        AlertDialog card = dialog();
        edit(card, "Nome (ex.: Meu cartão)").setText("Cartão pessoal");
        edit(card, "Últimos 4 dígitos").setText("1234");
        edit(card, "Dia do fechamento (1 a 31)").setText("25");
        edit(card, "Dia do vencimento (1 a 31)").setText("5");
        save(card);
        tab("Lançamentos");
        clickText(screen(), "+  Novo lançamento");
        AlertDialog entry = dialog();
        edit(entry, "Descrição").setText("Compra no crédito");
        edit(entry, "Valor (R$)").setText("55,90");
        select(entry, "Forma de pagamento", "Crédito");
        entry.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue("Credit form should remain open without a card", entry.isShowing());
        assertTrue(store.entries().isEmpty());
        assertContains(dialog().getWindow().getDecorView(), "Selecione um cartão");
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        selectPrefix(entry, "Cartão (somente para crédito)", "Cartão pessoal");
        save(entry);
        assertEquals(100000, store.totalBalance());
        assertEquals("credit", store.entries().get(0).method);
        assertEquals(store.cards().get(0).id, store.entries().get(0).cardId);

        tab("Carteira");
        assertContains(screen(), "R$ 55,90");
        controller.recreate();
        activity = controller.get();
        idle();
        assertVisible("Sua carteira");
        assertVisible("Cartão pessoal");
        assertContains(screen(), "R$ 55,90");
        assertEquals(1, store.entries().size());
        assertEquals(100000, store.totalBalance());

        tab("Lançamentos");
        clickText(screen(), "+  Novo lançamento");
        AlertDialog payment = dialog();
        edit(payment, "Descrição").setText("Fatura quitada");
        edit(payment, "Valor (R$)").setText("55,90");
        select(payment, "Forma de pagamento", "Pagamento de fatura");
        save(payment);
        assertEquals(2, store.entries().size());
        assertEquals(94410, store.totalBalance());
        tab("Lançamentos");
        View metricLabel = byText(screen(), "↘  Saídas");
        assertNotNull(metricLabel);
        assertContains((View) metricLabel.getParent(), "R$ 55,90");
        tab("Painel");
        assertContains(screen(), "R$ 944,10");
    }

    @Test public void dashboardShowsCashAndInvestmentPositionsWithoutAddingThemToTheSameBalance() {
        Models.Account account = store.accounts().get(0);
        account.openingCents = 123456;
        store.saveAccount(account);
        store.saveInvestment(new Models.Investment(0, "Reserva", "", "Renda fixa", 500000, 525000));
        store.saveInvestment(new Models.Investment(0, "Posição manual", "DEMO", "Ações", 100000, 95000));
        tab("Carteira");
        tab("Painel");
        assertVisible("Seu dinheiro");
        assertContains(screen(), "R$ 1.234,56");
        assertContains(screen(), "R$ 6.200,00");
        assertNotNull(byText(screen(), "Total aplicado"));
        assertNotNull(byText(screen(), "Rentabilidade simples"));
        assertContains((View) byText(screen(), "Total aplicado").getParent(), "R$ 6.000,00");
        assertContains((View) byText(screen(), "Rentabilidade simples").getParent(), "R$ 200,00");
        assertContains((View) byText(screen(), "Rentabilidade simples").getParent(), "3,33%");
        assertEquals(123456, store.totalBalance());
        assertTrue(store.entries().isEmpty());
        assertNull(byText(screen(), "Últimos lançamentos"));
        assertNull(byText(screen(), "Para onde foi?"));
        clickClickable(byDescription(screen(), "Ver carteira"));
        assertVisible("Sua carteira");
        assertVisible("Reserva");
        assertVisible("Posição manual · DEMO");
    }

    @Test public void importingHistoricalStatementOpensItsLatestMonthAndClearsOldSearchAndTypeFilter() throws Exception {
        YearMonth historical = YearMonth.now().minusMonths(8);
        String previousDate = historical.minusMonths(1).atDay(2).toString();
        String latestDate = historical.atDay(12).toString();
        tab("Lançamentos");
        clickText(screen(), "Entradas");
        ((EditText) byDescription(screen(), "Buscar descrição")).setText("não corresponde ao extrato");
        String source = "data;descricao;valor\n"
                + previousDate + ";Compra do mês anterior;-12,34\n"
                + latestDate + ";Compra histórica visível;-45,67\n";
        Method options = MainActivity.class.getDeclaredMethod("importOptions", String.class, String.class);
        options.setAccessible(true);
        options.invoke(activity, source, "historico.csv");
        idle();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();
        assertContains(dialog().getWindow().getDecorView(), "2 lançamentos encontrados");
        assertTrue(store.entries().isEmpty());
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();
        assertContains(dialog().getWindow().getDecorView(), "2 lançamentos importados");
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertVisible("Seus lançamentos");
        assertVisible("Compra histórica visível");
        assertNull(byText(screen(), "Compra do mês anterior"));
        assertEquals("", ((EditText) byDescription(screen(), "Buscar descrição")).getText().toString());
        assertEquals(2, store.entries().size());
        clickClickable(byDescription(screen(), "Mês anterior"));
        assertVisible("Compra do mês anterior");
        assertEquals(-5801, store.totalBalance());
    }

    @Test public void appearanceCanBeChangedInSettingsAndTheSavedChoiceIsSelectedAfterRecreation() {
        tab("Ajustes");
        clickClickable(byDescription(screen(), "Aparência"));
        AlertDialog appearance = dialog();
        assertContains(appearance.getWindow().getDecorView(), "Aparência");
        assertNotNull(appearance.getListView());
        assertEquals(3, appearance.getListView().getCount());
        assertEquals("Seguir o sistema", appearance.getListView().getItemAtPosition(0).toString());
        assertEquals("Claro", appearance.getListView().getItemAtPosition(1).toString());
        assertEquals("Escuro", appearance.getListView().getItemAtPosition(2).toString());
        assertEquals(0, appearance.getListView().getCheckedItemPosition());
        appearance.getListView().performItemClick(
                appearance.getListView().getAdapter().getView(2, null, appearance.getListView()), 2,
                appearance.getListView().getItemIdAtPosition(2));
        idle();
        assertEquals(AppAppearance.Mode.DARK, AppAppearance.getMode(context));
        assertFalse(appearance.isShowing());

        controller.recreate();
        activity = controller.get();
        idle();
        assertVisible("Organize sua rotina");
        assertTrue(AppAppearance.isDark(activity));
        clickClickable(byDescription(screen(), "Aparência"));
        assertEquals(2, dialog().getListView().getCheckedItemPosition());
    }

    @Test public void changingAppearancePreservesTransactionPageMonthFiltersSearchAndHiddenAmounts() {
        long accountId = store.accounts().get(0).id;
        String previousDate = YearMonth.now().minusMonths(1).atDay(2).toString();
        store.saveEntry(new Models.Entry(0, accountId, 0, 0, "Busca preservada compra", previousDate,
                "expense", "pix", "", 1234));
        store.saveEntry(new Models.Entry(0, accountId, 0, 0, "Busca preservada entrada", previousDate,
                "income", "pix", "", 4321));
        store.saveEntry(new Models.Entry(0, accountId, 0, 0, "Busca preservada outro mês", LocalDate.now().toString(),
                "expense", "pix", "", 2222));
        String original = store.exportBackup();
        tab("Lançamentos");
        clickClickable(byDescription(screen(), "Mês anterior"));
        clickText(screen(), "Saídas");
        ((EditText) byDescription(screen(), "Buscar descrição")).setText("Busca preservada");
        clickClickable(byDescription(screen(), "Ocultar valores"));
        assertVisible("Busca preservada compra");
        assertNull(byText(screen(), "Busca preservada entrada"));
        assertNull(byText(screen(), "Busca preservada outro mês"));

        AppAppearance.setMode(context, AppAppearance.Mode.DARK);
        controller.recreate();
        activity = controller.get();
        idle();
        assertVisible("Seus lançamentos");
        assertEquals("Busca preservada", ((EditText) byDescription(screen(), "Buscar descrição")).getText().toString());
        assertVisible("Busca preservada compra");
        assertNull(byText(screen(), "Busca preservada entrada"));
        assertNull(byText(screen(), "Busca preservada outro mês"));
        assertNotNull(byDescription(screen(), "Mostrar valores"));
        assertContains(screen(), "R$ ••••");
        assertEquals(original, store.exportBackup());
        assertEquals(AppAppearance.Mode.DARK, AppAppearance.getMode(activity));
    }

    @Test public void statementPreviewIncludesEveryExpenseBeyondTheFormerSampleWithoutSaving() throws Exception {
        StringBuilder source=new StringBuilder("data;descricao;valor\n");
        for(int i=1;i<=20;i++)source.append(LocalDate.now()).append(";Despesa ").append(i).append(";-1,00\n");
        Method options=MainActivity.class.getDeclaredMethod("importOptions",String.class,String.class);options.setAccessible(true);options.invoke(activity,source.toString(),"vinte.csv");idle();
        dialog().getButton(-1).performClick();drainWorker();AlertDialog preview=dialog();
        android.widget.ListView list=(android.widget.ListView)byDescription(preview.getWindow().getDecorView(),"Todos os lançamentos da importação");
        assertNotNull(list);assertEquals(21,list.getAdapter().getCount());
        assertTrue(list.getAdapter().getItem(20).toString().contains("Despesa 20"));
        assertTrue(store.entries().isEmpty());preview.getButton(-2).performClick();idle();assertTrue(store.entries().isEmpty());
    }

    @Test public void statementPreviewRequiresConfirmationAndReimportDoesNotDuplicate() throws Exception {
        String source = "data;descricao;valor;categoria\n"
                + LocalDate.now() + ";Mercado local;-45,67;Alimentação\n"
                + LocalDate.now() + ";Salário mensal;2000,00;Salário\n";
        Method options = MainActivity.class.getDeclaredMethod("importOptions", String.class, String.class);
        options.setAccessible(true);
        options.invoke(activity, source, "conta.csv");
        idle();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();
        AlertDialog preview = dialog();
        assertContains(preview.getWindow().getDecorView(), "2 lançamentos encontrados");
        assertImportContains(preview,"Mercado local");
        assertTrue(store.entries().isEmpty());
        preview.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertTrue(store.entries().isEmpty());

        options.invoke(activity, source, "conta.csv");
        idle();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        drainWorker();
        assertEquals(2, store.entries().size());
        assertEquals(195433, store.totalBalance());
        for (Models.Entry e : store.entries()) assertTrue(e.categoryId > 0);
        assertContains(dialog().getWindow().getDecorView(), "2 lançamentos importados");
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();

        options.invoke(activity, source, "conta.csv");
        idle();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        drainWorker();
        assertEquals(2, store.entries().size());
        assertEquals(195433, store.totalBalance());
        assertContains(dialog().getWindow().getDecorView(), "0 lançamentos importados. 2 duplicatas ignoradas.");
    }

    @Test public void transfersMoveCashBetweenAccountsWithoutBecomingIncomeOrSpending() {
        Models.Account primary = store.accounts().get(0);
        primary.openingCents = 100000;
        store.saveAccount(primary);
        Models.Account savings = new Models.Account(0, "Reserva", 0);
        store.saveAccount(savings);
        long transferCategory = category("Transferências").id;
        String today = LocalDate.now().toString();
        store.saveEntry(new Models.Entry(0, primary.id, transferCategory, 0,
                "Envio para Reserva", today, "expense", "transfer", "", 20000));
        store.saveEntry(new Models.Entry(0, savings.id, transferCategory, 0,
                "Recebimento na Reserva", today, "income", "transfer", "", 20000));
        assertEquals(80000, store.accountBalance(primary.id));
        assertEquals(20000, store.accountBalance(savings.id));
        assertEquals(100000, store.totalBalance());

        // Navigation reloads the persisted records, as it does after a saved form.
        tab("Carteira");
        assertContains(screen(), "R$ 800,00");
        assertContains(screen(), "R$ 200,00");
        tab("Lançamentos");
        View incomeLabel = byText(screen(), "↗  Entradas");
        View expenseLabel = byText(screen(), "↘  Saídas");
        assertNotNull(incomeLabel);
        assertNotNull(expenseLabel);
        assertContains((View) incomeLabel.getParent(), "R$ 0,00");
        assertContains((View) expenseLabel.getParent(), "R$ 0,00");
        tab("Relatórios");
        assertVisible("Nenhuma despesa no período");
        assertContains((View) byText(screen(), "Entradas").getParent(), "R$ 0,00");
        assertContains((View) byText(screen(), "Despesas").getParent(), "R$ 0,00");
        tab("Lançamentos");
        assertVisible("Envio para Reserva");
        assertVisible("Recebimento na Reserva");
    }

    @Test public void documentPickerReadsUtf8BomFromContentUriAndImportsOnlyAfterConfirmation() throws Exception {
        Uri uri = Uri.parse("content://finance-tests.documents/document/utf8-statement");
        String source = "\uFEFFdata;descrição;valor;categoria\r\n"
                + LocalDate.now() + ";Café e pão;-12,34;Alimentação\r\n";
        AtomicBoolean closed = registerDocument(uri, source.getBytes(StandardCharsets.UTF_8), "extrato-acente.csv");

        Intent picker = launchStatementPicker();
        shadowOf(activity).receiveResult(picker, Activity.RESULT_OK,
                new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        drainWorker();
        assertTrue("The document stream must close after reading", closed.get());
        assertContains(dialog().getWindow().getDecorView(), "extrato-acente.csv");
        assertTrue(store.entries().isEmpty());

        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();
        assertImportContains(dialog(),"Café e pão");
        assertImportContains(dialog(),"R$ 12,34");
        assertImportContains(dialog(),"Alimentação");
        assertTrue("Reading and preview must not modify the database", store.entries().isEmpty());

        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        drainWorker();
        assertEquals(1, store.entries().size());
        Models.Entry imported = store.entries().get(0);
        assertEquals("Café e pão", imported.description);
        assertEquals(1234, imported.amountCents);
        assertEquals("expense", imported.type);
        assertEquals(category("Alimentação").id, imported.categoryId);
        assertEquals(-1234, store.totalBalance());
    }

    @Test public void documentPickerDecodesWindows1252WithoutMetadataAndPreviewCanBeCancelled() throws Exception {
        Uri uri = Uri.parse("content://finance-tests.documents/document/windows1252-statement");
        String source = "data;descrição;valor\n"
                + LocalDate.now() + ";Farmácia São João;-28,90\n";
        AtomicBoolean closed = registerDocument(uri, source.getBytes(Charset.forName("windows-1252")), null);

        shadowOf(activity).receiveResult(launchStatementPicker(), Activity.RESULT_OK, new Intent().setData(uri));
        drainWorker();
        assertTrue(closed.get());
        assertContains(dialog().getWindow().getDecorView(), "extrato");
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        drainWorker();
        assertImportContains(dialog(),"Farmácia São João");
        assertImportContains(dialog(),"R$ 28,90");
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertTrue(store.entries().isEmpty());
        assertEquals(0, store.totalBalance());
    }

    @Test public void cancelledPickerAndResultWithoutUriNeverOpenAStreamOrModifyData() throws Exception {
        Uri uri = Uri.parse("content://finance-tests.documents/document/cancelled-statement");
        AtomicInteger opened = new AtomicInteger();
        shadowOf(activity.getContentResolver()).registerInputStreamSupplier(uri, () -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(new byte[0]);
        });
        String original = store.exportBackup();
        shadowOf(activity).receiveResult(launchStatementPicker(), Activity.RESULT_CANCELED, new Intent().setData(uri));
        drainWorker();
        shadowOf(activity).receiveResult(launchStatementPicker(), Activity.RESULT_OK, new Intent());
        drainWorker();
        assertEquals(0, opened.get());
        assertEquals(original, store.exportBackup());
        assertNull("Cancelled/empty picker results should not display an error", ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test public void contentUriReadFailureShowsErrorClosesStreamAndPreservesExistingData() throws Exception {
        Models.Account account = store.accounts().get(0);
        store.saveEntry(new Models.Entry(0, account.id, 0, 0, "Dado preservado", LocalDate.now().toString(),
                "expense", "pix", "", 321));
        String original = store.exportBackup();
        Uri uri = Uri.parse("content://finance-tests.documents/document/unreadable-statement");
        AtomicBoolean closed = new AtomicBoolean();
        shadowOf(activity.getContentResolver()).registerInputStreamSupplier(uri, () -> new InputStream() {
            @Override public int read() throws IOException { throw new IOException("Falha de leitura simulada"); }
            @Override public void close() { closed.set(true); }
        });

        shadowOf(activity).receiveResult(launchStatementPicker(), Activity.RESULT_OK, new Intent().setData(uri));
        drainWorker();
        assertContains(dialog().getWindow().getDecorView(), "Falha de leitura simulada");
        assertTrue("I/O failures must still close the input stream", closed.get());
        assertEquals(original, store.exportBackup());
        assertEquals(-321, store.totalBalance());
        assertFalse(activity.isFinishing());
        assertFalse(activity.isDestroyed());
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertVisible("Seus lançamentos");
        assertVisible("Dado preservado");
    }

    private Intent launchStatementPicker() {
        tab("Lançamentos");
        clickText(screen(), "Importar extrato");
        ShadowActivity.IntentForResult started = shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull("The import button must open Android's document picker", started);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.getAction());
        assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals("*/*", started.intent.getType());
        return started.intent;
    }

    private AtomicBoolean registerDocument(Uri uri, byte[] bytes, String filename) {
        AtomicBoolean closed = new AtomicBoolean();
        shadowOf(activity.getContentResolver()).registerInputStreamSupplier(uri, () -> new ByteArrayInputStream(bytes) {
            @Override public void close() throws IOException { closed.set(true); super.close(); }
        });
        if (filename != null) {
            RoboCursor cursor = new RoboCursor();
            cursor.setColumnNames(java.util.Collections.singletonList(OpenableColumns.DISPLAY_NAME));
            cursor.setResults(new Object[][] {{filename}});
            shadowOf(activity.getContentResolver()).setCursor(uri, cursor);
        }
        return closed;
    }

    private static void assertImportContains(AlertDialog preview,String text) {
        android.widget.ListView list=(android.widget.ListView)byDescription(preview.getWindow().getDecorView(),"Todos os lançamentos da importação");
        assertNotNull(list);
        for(int i=1;i<list.getAdapter().getCount();i++)if(list.getAdapter().getItem(i).toString().replace('\u00a0',' ').replace('\u202f',' ').contains(text))return;
        fail("Missing import row containing "+text);
    }

    private View screen() { return activity.findViewById(android.R.id.content); }
    private void tab(String name) { clickClickable(byDescription(screen(), name)); idle(); }
    private void openCategories() {
        tab("Lançamentos");
        clickClickable(byDescription(screen(), "Gerenciar categorias"));
        assertVisible("Categorias");
    }
    private void assertVisible(String text) { assertNotNull("Missing text: " + text, byText(screen(), text)); }
    private static void assertContains(View root, String text) {
        List<String> texts = new ArrayList<>(); collectTexts(root, texts);
        for (String value : texts) if (value.replace('\u00a0', ' ').replace('\u202f', ' ').contains(text)) return;
        fail("Missing text containing '" + text + "' in " + texts);
    }
    private static void collectTexts(View view, List<String> texts) {
        if (view instanceof TextView) texts.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collectTexts(((ViewGroup) view).getChildAt(i), texts);
    }
    private static View byText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View match = byText(((ViewGroup) view).getChildAt(i), text); if (match != null) return match;
        }
        return null;
    }
    private static View byDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View match = byDescription(((ViewGroup) view).getChildAt(i), description); if (match != null) return match;
        }
        return null;
    }
    private static void clickText(View root, String text) {
        View view = byText(root, text); assertNotNull("Missing clickable text " + text, view);
        clickClickable(view); idle();
    }
    private static void clickClickable(View view) {
        assertNotNull(view);
        while (!view.isClickable() && view.getParent() instanceof View) view = (View) view.getParent();
        assertTrue("No clickable parent", view.isClickable()); assertTrue(view.performClick()); idle();
    }
    private static AlertDialog dialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("No dialog", dialog); assertTrue("Dialog already dismissed", dialog.isShowing());
        return dialog;
    }
    private static EditText edit(AlertDialog dialog, String label) {
        View view = byDescription(dialog.getWindow().getDecorView(), label);
        assertTrue("Missing EditText " + label, view instanceof EditText); return (EditText) view;
    }
    private static void select(AlertDialog dialog, String label, String item) {
        selectItem(dialog, label, item, false);
    }
    private static void selectPrefix(AlertDialog dialog, String label, String prefix) {
        selectItem(dialog, label, prefix, true);
    }
    private static void selectItem(AlertDialog dialog, String label, String value, boolean prefix) {
        View view = byDescription(dialog.getWindow().getDecorView(), label);
        assertTrue("Missing Spinner " + label, view instanceof Spinner);
        Spinner spinner = (Spinner) view;
        for (int i = 0; i < spinner.getCount(); i++) {
            String item = spinner.getItemAtPosition(i).toString();
            if (prefix ? item.startsWith(value) : item.equals(value)) { spinner.setSelection(i); idle(); return; }
        }
        fail("Missing option " + value + " in " + label);
    }
    private Models.Category category(String name) {
        for (Models.Category category : store.categories()) if (category.name.equals(name)) return category;
        throw new AssertionError("Missing stored category " + name);
    }
    private static void save(AlertDialog dialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
        AlertDialog confirmation=ShadowAlertDialog.getLatestAlertDialog();
        if(confirmation!=dialog && confirmation.isShowing() && "Confirmar e salvar".contentEquals(confirmation.getButton(-1).getText())) {
            confirmation.getButton(-1).performClick();idle();
        }
        assertFalse("Form did not save", dialog.isShowing());
    }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private void drainWorker() throws Exception {
        Field field = MainActivity.class.getDeclaredField("worker"); field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(activity);
        Field progress = MainActivity.class.getDeclaredField("activeProgress"); progress.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            // AlertDialog dispatches a standard button listener through the main handler. Drain
            // that click before placing a marker behind the work it submits, then run its UI result.
            idle();
            long remaining = deadline - System.nanoTime();
            assertTrue("Document operation must complete within ten seconds", remaining > 0);
            executor.submit(() -> {}).get(remaining, TimeUnit.NANOSECONDS);
            idle();
        } while (progress.get(activity) != null);
    }
}
