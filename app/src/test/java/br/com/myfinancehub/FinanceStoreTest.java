package br.com.myfinancehub;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class FinanceStoreTest {
    private Context context;
    private FinanceStore store;
    private long account;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("myfinancehub.db");
        store = new FinanceStore(context);
        account = store.accounts().get(0).id;
    }

    @After public void after() { store.close(); context.deleteDatabase("myfinancehub.db"); }

    @Test public void freshDatabaseHasCategoriesAndNoInventedFinances() {
        assertEquals(1, store.accounts().size()); assertEquals("Principal", store.accounts().get(0).name);
        assertFalse(store.categories().isEmpty()); assertEquals(0, store.totalBalance());
        assertTrue(store.entries().isEmpty()); assertTrue(store.cards().isEmpty()); assertTrue(store.investments().isEmpty());
    }

    @Test public void crudPreservesReferencesAndCountsOnlyCashMovements() {
        Models.Account a = store.accounts().get(0); a.openingCents = 10000; store.saveAccount(a);
        Models.Category category = new Models.Category(0, "Teste", "both"); store.saveCategory(category);
        Models.Card card = new Models.Card(0, "Cartão", "1234", 25, 5); store.saveCard(card);
        Models.Entry income = entry("salary", "income", "pix", 50000); income.categoryId = category.id; store.saveEntry(income);
        Models.Entry expense = entry("rent", "expense", "debit", 20000); store.saveEntry(expense);
        Models.Entry credit = entry("shop", "expense", "credit", 30000); credit.cardId = card.id; store.saveEntry(credit);
        Models.Entry refund = entry("refund", "income", "credit", 1000); refund.cardId = card.id; store.saveEntry(refund);
        assertEquals(40000, store.accountBalance(account));
        expense.amountCents = 15000; store.saveEntry(expense); assertEquals(45000, store.totalBalance());
        store.deleteCategory(category.id); store.deleteCard(card.id);
        for (Models.Entry e : store.entries()) { assertEquals(0, e.categoryId); assertEquals(0, e.cardId); }
        assertEquals(45000, store.totalBalance());
        expectInvalid(() -> store.deleteAccount(account));
        for (Models.Entry e : store.entries()) store.deleteEntry(e.id);
        store.deleteAccount(account); assertTrue(store.accounts().isEmpty());
    }

    @Test public void importIsDeduplicatedPerAccountAndIdempotent() {
        Models.Entry a = entry("one", "expense", "pix", 200);
        Models.Entry duplicate = entry("one", "expense", "pix", 200);
        assertEquals(1, store.importEntries(Arrays.asList(a, duplicate)));
        assertEquals(0, store.importEntries(Arrays.asList(a, duplicate)));
        Models.Account second = new Models.Account(0, "Segunda", 0); store.saveAccount(second);
        Models.Entry b = entry("one", "expense", "pix", 200); b.accountId = second.id;
        assertEquals(1, store.importEntries(Arrays.asList(b))); assertEquals(2, store.entries().size());
    }

    @Test public void failedImportRollsBackEarlierRows() {
        Models.Entry valid = entry("valid", "expense", "pix", 200);
        Models.Entry invalid = entry("bad", "expense", "pix", 200); invalid.date = "2025-02-29";
        expectInvalid(() -> store.importEntries(Arrays.asList(valid, invalid)));
        assertTrue(store.entries().isEmpty()); assertEquals(0, valid.id);
    }

    @Test public void reviewedNotificationCannotBeRepeatedInTheSameOrAnotherAccount() {
        Models.Entry original = notification('a', "expense", "pix", 200);
        long id = store.saveReviewedNotification(original);
        assertEquals(id, original.id); assertTrue(id > 0);
        assertTrue(store.hasNotificationSource(original.sourceKey));

        Models.Entry sameAccount = notification('a', "expense", "pix", 200);
        expectRegisteredNotification(() -> store.saveReviewedNotification(sameAccount));
        Models.Account second = new Models.Account(0, "Segunda", 0); store.saveAccount(second);
        Models.Entry otherAccount = notification('a', "expense", "pix", 200); otherAccount.accountId = second.id;
        expectRegisteredNotification(() -> store.saveReviewedNotification(otherAccount));

        assertEquals(0, sameAccount.id); assertEquals(0, otherAccount.id);
        assertEquals(1, store.entries().size()); assertEquals(-200, store.accountBalance(account));
        assertEquals(0, store.accountBalance(second.id));
    }

    @Test public void differentNotificationKeysAllowOtherwiseIdenticalEntries() {
        Models.Entry first = notification('a', "expense", "debit", 300);
        Models.Entry second = notification('b', "expense", "debit", 300);
        first.description = "Mesmo estabelecimento"; second.description = first.description;
        store.saveReviewedNotification(first); store.saveReviewedNotification(second);
        assertEquals(2, store.entries().size()); assertEquals(-600, store.totalBalance());
        assertNotEquals(first.id, second.id);
    }

    @Test public void notificationNeverAutomaticallyLinksSimilarManualOrImportedEntries() {
        Models.Entry manual = entry("", "expense", "pix", 300);
        Models.Entry imported = entry("statement:123", "expense", "pix", 300);
        Models.Entry notification = notification('a', "expense", "pix", 300);
        manual.description = "Mesmo pagamento"; imported.description = manual.description;
        notification.description = manual.description;
        store.saveEntry(manual); assertEquals(1, store.importEntries(Arrays.asList(imported)));
        assertFalse(store.hasNotificationSource(notification.sourceKey));
        store.saveReviewedNotification(notification);
        assertEquals(3, store.entries().size()); assertEquals(-900, store.totalBalance());
        assertEquals(0, store.importEntries(Arrays.asList(imported)));
        assertTrue(store.hasNotificationSource(notification.sourceKey));
    }

    @Test public void notificationWritesRejectInvalidKeysAndExistingEntryIds() {
        String validKey = notification('a', "expense", "pix", 100).sourceKey;
        String before = store.exportBackup();
        String[] invalidKeys = {null, "", "statement:123", "notification:", validKey + "a",
                validKey.substring(0, validKey.length() - 1), validKey.replace('a', 'g'), " " + validKey,
                validKey + " ", validKey.replace("notification:", "Notification:")};
        for (String key : invalidKeys) {
            Models.Entry candidate = entry(key, "expense", "pix", 100);
            expectInvalid(() -> store.saveReviewedNotification(candidate));
            assertFalse(store.hasNotificationSource(key)); assertEquals(0, candidate.id);
        }
        expectInvalid(() -> store.saveReviewedNotification(null));
        Models.Entry notNew = notification('b', "expense", "pix", 100); notNew.id = 25;
        expectInvalid(() -> store.saveReviewedNotification(notNew)); assertEquals(25, notNew.id);
        assertEquals(before, store.exportBackup());
    }

    @Test public void notificationDigestLetterCaseCannotBypassDuplicateCheck() {
        Models.Entry original = notification('a', "expense", "pix", 100);
        store.saveReviewedNotification(original);
        Models.Entry uppercase = notification('A', "expense", "pix", 100);
        assertTrue(store.hasNotificationSource(uppercase.sourceKey));
        expectRegisteredNotification(() -> store.saveReviewedNotification(uppercase));
        assertEquals(1, store.entries().size()); assertEquals(0, uppercase.id);
    }

    @Test public void invalidNotificationReferencesAndCategoryTypesLeaveNoRecordedKey() {
        Models.Entry candidate = notification('a', "expense", "pix", 100);
        String before = store.exportBackup();
        candidate.accountId = 99999;
        expectInvalid(() -> store.saveReviewedNotification(candidate)); candidate.accountId = account;
        candidate.cardId = 99999;
        expectInvalid(() -> store.saveReviewedNotification(candidate)); candidate.cardId = 0;
        candidate.categoryId = 99999;
        expectInvalid(() -> store.saveReviewedNotification(candidate)); candidate.categoryId = 0;
        assertEquals(before, store.exportBackup()); assertEquals(0, candidate.id);
        assertFalse(store.hasNotificationSource(candidate.sourceKey));

        Models.Category income = new Models.Category(0, "Só receitas", "income"); store.saveCategory(income);
        candidate.categoryId = income.id;
        expectInvalid(() -> store.saveReviewedNotification(candidate));
        assertTrue(store.entries().isEmpty()); assertEquals(0, candidate.id);
        assertFalse(store.hasNotificationSource(candidate.sourceKey));
        candidate.categoryId = 0; store.saveReviewedNotification(candidate);
        assertTrue(store.hasNotificationSource(candidate.sourceKey)); assertEquals(-100, store.totalBalance());
    }

    @Test public void reviewedNotificationPreservesCreditAndBillPaymentRules() {
        Models.Account a = store.accounts().get(0); a.openingCents = 10000; store.saveAccount(a);
        Models.Card card = new Models.Card(0, "Meu cartão", "1234", 15, 25); store.saveCard(card);
        Models.Entry purchase = notification('a', "expense", "credit", 2000); purchase.cardId = card.id;
        Models.Entry refund = notification('b', "income", "credit", 100); refund.cardId = card.id;
        store.saveReviewedNotification(purchase); store.saveReviewedNotification(refund);
        assertEquals(10000, store.totalBalance());
        Models.Entry invalidBill = notification('c', "income", "card_payment", 2000);
        expectInvalid(() -> store.saveReviewedNotification(invalidBill));
        assertFalse(store.hasNotificationSource(invalidBill.sourceKey)); assertEquals(0, invalidBill.id);
        Models.Entry bill = notification('c', "expense", "card_payment", 2000);
        store.saveReviewedNotification(bill);
        assertEquals(8000, store.totalBalance()); assertEquals(3, store.entries().size());
    }

    @Test public void notificationOverflowRollsBackInsertedRowAndAllowsAValidRetry() {
        Models.Account a = store.accounts().get(0); a.openingCents = Long.MAX_VALUE; store.saveAccount(a);
        Models.Entry candidate = notification('a', "income", "pix", 1);
        String before = store.exportBackup();
        expectInvalid(() -> store.saveReviewedNotification(candidate));
        assertEquals(before, store.exportBackup()); assertEquals(0, candidate.id);
        assertFalse(store.hasNotificationSource(candidate.sourceKey));
        a.openingCents = Long.MAX_VALUE - 1; store.saveAccount(a);
        store.saveReviewedNotification(candidate);
        assertTrue(candidate.id > 0); assertEquals(Long.MAX_VALUE, store.accountBalance(account));
    }

    @Test public void backupRestoresNotificationKeysAndPreventsDuplicatesAcrossAccounts() {
        Models.Account second = new Models.Account(0, "Segunda", 0); store.saveAccount(second);
        Models.Entry original = notification('a', "expense", "pix", 200);
        store.saveReviewedNotification(original); String backup = store.exportBackup();
        store.deleteEntry(original.id); assertFalse(store.hasNotificationSource(original.sourceKey));
        store.restoreBackup(backup); assertEquals(backup, store.exportBackup());
        assertTrue(store.hasNotificationSource(original.sourceKey));
        Models.Entry duplicate = notification('a', "expense", "pix", 200); duplicate.accountId = second.id;
        expectRegisteredNotification(() -> store.saveReviewedNotification(duplicate));
        assertEquals(backup, store.exportBackup()); assertEquals(0, duplicate.id);
    }

    @Test public void validBackupRoundTripsIdsAndCompleteRecords() {
        Models.Card card = new Models.Card(0, "Meu cartão", "9876", 31, 8); store.saveCard(card);
        Models.Entry e = entry("unique", "expense", "credit", 23456); e.cardId = card.id; store.saveEntry(e);
        Models.Investment investment = new Models.Investment(0, "Tesouro", "", "Renda fixa", 10000, 11000); store.saveInvestment(investment);
        String backup = store.exportBackup();
        store.deleteEntry(e.id); store.deleteInvestment(investment.id); store.restoreBackup(backup);
        assertEquals(backup, store.exportBackup());
        assertEquals(e.id, store.entries().get(0).id); assertEquals(0, store.totalBalance());
    }

    @Test public void invalidRestoreLeavesExistingDataIntact() throws Exception {
        store.saveEntry(entry("keep", "expense", "pix", 123));
        String original = store.exportBackup();
        JSONObject invalidRef = new JSONObject(original);
        invalidRef.getJSONArray("entries").getJSONObject(0).put("accountId", 99999);
        expectInvalid(() -> store.restoreBackup(invalidRef.toString()));
        assertEquals(original, store.exportBackup());
        JSONObject duplicate = new JSONObject(original);
        JSONArray entries = duplicate.getJSONArray("entries"); entries.put(new JSONObject(entries.getJSONObject(0).toString()));
        expectInvalid(() -> store.restoreBackup(duplicate.toString()));
        assertEquals(original, store.exportBackup());
        expectInvalid(() -> store.restoreBackup(original + " garbage"));
        expectInvalid(() -> store.restoreBackup("{}")); assertEquals(original, store.exportBackup());

        // This backup passes syntax/reference checks and fails after replacement begins.
        JSONObject overflow = new JSONObject(original);
        JSONObject large = overflow.getJSONArray("entries").getJSONObject(0);
        large.put("amountCents", Long.MAX_VALUE);
        JSONObject extra = new JSONObject(large.toString());
        extra.put("id", large.getLong("id") + 1).put("amountCents", 1).put("sourceKey", "new-source");
        overflow.getJSONArray("entries").put(extra);
        expectInvalid(() -> store.restoreBackup(overflow.toString()));
        assertEquals(original, store.exportBackup());
    }

    @Test public void validatesTypesDatesAmountsAndCategoryChanges() {
        Models.Category c = new Models.Category(0, "Personalizada", "income"); store.saveCategory(c);
        Models.Entry e = entry("category", "expense", "cash", 100); e.categoryId = c.id;
        expectInvalid(() -> store.saveEntry(e));
        e.type = "income"; store.saveEntry(e); c.type = "expense";
        expectInvalid(() -> store.saveCategory(c));
        Models.Entry leap = entry("leap", "expense", "cash", 100); leap.date = "2024-02-29"; store.saveEntry(leap);
        leap.date = "2024-02-30"; expectInvalid(() -> store.saveEntry(leap));
        leap.date = "2024-02-29"; leap.amountCents = 0; expectInvalid(() -> store.saveEntry(leap));
    }

    @Test public void rejectsUnicodeDuplicateNamesAndInvalidCardData() {
        store.saveCard(new Models.Card(0, "Crédito", "1234", 1, 31));
        expectInvalid(() -> store.saveCard(new Models.Card(0, "CRÉDITO", "5678", 10, 20)));
        expectInvalid(() -> store.saveCard(new Models.Card(0, "Outro", "1234567890123456", 10, 20)));
        expectInvalid(() -> store.saveCard(new Models.Card(0, "Outro", "1234", 0, 20)));
    }

    @Test public void csvQuotesDelimitersAndUsesExplicitType() {
        Models.Entry e = entry("csv", "expense", "pix", 1234); e.description = "Café; \"comida\""; store.saveEntry(e);
        String csv = store.exportCsv();
        assertTrue(csv.startsWith("date;description;type;amount;account;category;method;card\r\n"));
        assertTrue(csv.contains("\"Café; \"\"comida\"\"\";\"expense\";\"12,34\""));
        e.description = "=HYPERLINK(\"http://example.test\")"; store.saveEntry(e);
        assertTrue(store.exportCsv().contains("\"'=HYPERLINK("));
        assertTrue(store.exportBackup().contains("=HYPERLINK("));
    }

    @Test public void refusesSilentBalanceOverflow() {
        Models.Account a = store.accounts().get(0); a.openingCents = Long.MAX_VALUE; store.saveAccount(a);
        expectInvalid(() -> store.saveEntry(entry("overflow", "income", "pix", 1)));
        assertTrue(store.entries().isEmpty()); assertEquals(Long.MAX_VALUE, store.accountBalance(account));
    }

    @Test public void cardPurchaseAndBillPaymentAffectCashOnlyOnce() {
        store.saveEntry(entry("purchase", "expense", "credit", 10000));
        store.saveEntry(entry("bill", "expense", "card_payment", 10000));
        assertEquals(-10000, store.totalBalance());
        assertEquals(2, store.entries().size());
    }

    @Test public void rejectsCardPaymentIncomeAtEveryWriteBoundary() throws Exception {
        Models.Entry invalid = entry("invalid-bill", "income", "card_payment", 100);
        expectInvalid(() -> store.saveEntry(invalid));
        expectInvalid(() -> store.importEntries(Arrays.asList(invalid)));
        assertTrue(store.entries().isEmpty());
        store.saveEntry(entry("valid-bill", "expense", "card_payment", 100));
        String original = store.exportBackup();
        JSONObject backup = new JSONObject(original);
        backup.getJSONArray("entries").getJSONObject(0).put("type", "income");
        expectInvalid(() -> store.restoreBackup(backup.toString()));
        assertEquals(original, store.exportBackup());
    }

    @Test public void rejectsOverflowAcrossOppositeDirectionsAndInvestments() {
        store.saveEntry(entry("large", "expense", "credit", Long.MAX_VALUE));
        expectInvalid(() -> store.saveEntry(entry("opposite", "income", "credit", 1)));
        expectInvalid(() -> store.saveInvestment(new Models.Investment(0, "Excesso", "", "", 1, 0)));
        assertEquals(1, store.entries().size()); assertTrue(store.investments().isEmpty());
    }

    @Test public void rejectsOversizedBackupsWithoutChanges() {
        String before = store.exportBackup();
        char[] chars = new char[8 * 1024 * 1024 + 1]; Arrays.fill(chars, ' ');
        expectInvalid(() -> store.restoreBackup(new String(chars)));
        assertEquals(before, store.exportBackup());
    }

    private Models.Entry entry(String source, String type, String method, long cents) {
        return new Models.Entry(0, account, 0, 0, "Lançamento " + source, "2026-10-15", type, method, source, cents);
    }

    private Models.Entry notification(char hex, String type, String method, long cents) {
        char[] digest = new char[64]; Arrays.fill(digest, hex);
        return entry("notification:" + new String(digest), type, method, cents);
    }

    private static void expectRegisteredNotification(Runnable operation) {
        try { operation.run(); fail("Expected notification duplicate to be rejected"); }
        catch (IllegalArgumentException expected) {
            assertEquals("Esta notificação já foi registrada. Atualize a lista.", expected.getMessage());
        }
    }

    private static void expectInvalid(Runnable operation) {
        try { operation.run(); fail("Expected validation to reject operation"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }
}
