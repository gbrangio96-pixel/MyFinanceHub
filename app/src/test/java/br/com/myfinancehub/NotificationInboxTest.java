package br.com.myfinancehub;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class NotificationInboxTest {
    private static final String APP = "br.com.bank.example";
    private static final long NOW = Instant.parse("2026-10-15T15:00:00Z").toEpochMilli();
    private Context context;
    private NotificationInbox inbox;

    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(NotificationInbox.DATABASE_NAME);
        context.deleteDatabase("myfinancehub.db");
        inbox = new NotificationInbox(context, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @After public void after() {
        inbox.close();
        context.deleteDatabase(NotificationInbox.DATABASE_NAME);
        context.deleteDatabase("myfinancehub.db");
    }

    @Test public void defaultIsOffAndAppSelectionIsRequired() {
        assertFalse(inbox.isEnabled());
        assertTrue(inbox.allowedPackages().isEmpty());
        assertFalse(enqueue("one", NOW));
        inbox.setEnabled(true);
        assertFalse(enqueue("one", NOW));
        inbox.setAllowedPackages(Collections.singleton(APP));
        assertTrue(enqueue("one", NOW));
        inbox.setEnabled(false);
        assertFalse(enqueue("two", NOW));
        assertEquals(1, inbox.pendingCount());
    }

    @Test public void settingsSurviveReopenAndRejectInvalidNamesAtomically() {
        enable();
        try {
            inbox.setAllowedPackages(new HashSet<>(java.util.Arrays.asList(APP, "not a package")));
            fail("Invalid package should be rejected");
        } catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
        assertEquals(Collections.singleton(APP), inbox.allowedPackages());
        inbox.close();
        inbox = new NotificationInbox(context, Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
        assertTrue(inbox.isEnabled());
        assertEquals(Collections.singleton(APP), inbox.allowedPackages());
    }

    @Test public void candidateRoundTripsOnlyMinimalFieldsAndDoesNotChangeFinancialBackup() {
        try (FinanceStore store = new FinanceStore(context)) {
            String original = store.exportBackup();
            enable(); assertTrue(enqueue("one", NOW));
            NotificationInbox.Candidate row = inbox.pending().get(0);
            assertEquals(APP, row.packageName); assertEquals("Banco de teste", row.appLabel);
            assertEquals(2590, row.amountCents); assertEquals("2026-10-15", row.date);
            assertEquals("expense", row.type); assertEquals("1234", row.lastFour);
            assertNotNull(row.description); assertNotNull(row.reviewNote);
            assertTrue(row.sourceKey.matches("notification:[a-f0-9]{64}"));
            assertEquals(row.sourceKey, inbox.sourceKey(row.id));
            assertEquals(original, store.exportBackup());
            assertTrue(store.entries().isEmpty());
        }
    }

    @Test public void exactRedeliveriesDeduplicateButSeparatePostsRemainReviewable() {
        enable();
        assertTrue(enqueue("one", NOW));
        assertFalse(enqueue("one", NOW));
        assertTrue(enqueue("one", NOW + 500));
        assertTrue(enqueue("two", NOW));
        assertEquals(3, inbox.pendingCount());
        long reviewed = inbox.pending().get(0).id;
        inbox.dismiss(reviewed);
        assertEquals(2, inbox.pendingCount());
        assertFalse(enqueue("one", NOW + 500));
        assertFalse(enqueue("two", NOW));
        assertFalse(enqueue("one", NOW));
        assertTrue(inbox.sourceKey(reviewed).startsWith("notification:"));
    }

    @Test public void sameAndroidKeyKeepsDifferentCardsAndMethodsReviewable() {
        enable();
        NotificationParser.Suggestion credit = NotificationParser.parse("Compra aprovada",
                "R$ 25,90 no cartão de crédito final 1234.", NOW, ZoneId.of("UTC"));
        NotificationParser.Suggestion otherCard = NotificationParser.parse("Compra aprovada",
                "R$ 25,90 no cartão de crédito final 5678.", NOW, ZoneId.of("UTC"));
        NotificationParser.Suggestion debit = NotificationParser.parse("Compra aprovada",
                "R$ 25,90 no cartão de débito final 1234.", NOW, ZoneId.of("UTC"));
        assertNotNull(credit); assertNotNull(otherCard); assertNotNull(debit);
        assertEquals(credit.description, otherCard.description);
        assertEquals(credit.description, debit.description);
        assertTrue(inbox.enqueue(APP, "Banco de teste", "reused", NOW, credit));
        assertTrue(inbox.enqueue(APP, "Banco de teste", "reused", NOW, otherCard));
        assertTrue(inbox.enqueue(APP, "Banco de teste", "reused", NOW, debit));
        assertEquals(3, inbox.pendingCount());
        inbox.clearPending();
        assertFalse(inbox.enqueue(APP, "Banco de teste", "reused", NOW, credit));
        assertFalse(inbox.enqueue(APP, "Banco de teste", "reused", NOW, otherCard));
        assertFalse(inbox.enqueue(APP, "Banco de teste", "reused", NOW, debit));
    }

    @Test public void reviewedPayloadIsErasedButMarkerPreventsReappearance() {
        enable(); enqueue("one", NOW);
        NotificationInbox.Candidate row = inbox.pending().get(0);
        inbox.dismiss(row.id);
        try (SQLiteDatabase database = context.openOrCreateDatabase(NotificationInbox.DATABASE_NAME, 0, null);
                Cursor c = database.rawQuery("SELECT description,package_name,amount_cents,source_key FROM candidates", null)) {
            assertTrue(c.moveToFirst());
            assertTrue(c.isNull(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2));
            assertEquals(row.sourceKey, c.getString(3));
        }
        assertFalse(enqueue("one", NOW));
    }

    @Test public void financialStoreKeepsTheSameGeneratedKeyAcrossAccounts() {
        enable(); assertTrue(enqueue("reused", NOW));
        NotificationInbox.Candidate candidate = inbox.pending().get(0);
        try (FinanceStore store = new FinanceStore(context)) {
            Models.Entry entry = new Models.Entry();
            entry.accountId = store.accounts().get(0).id;
            entry.description = candidate.description; entry.date = candidate.date;
            entry.type = candidate.type; entry.method = candidate.method;
            entry.amountCents = candidate.amountCents; entry.sourceKey = candidate.sourceKey;
            store.saveReviewedNotification(entry);
            assertTrue(store.hasNotificationSource(candidate.sourceKey));
            Models.Account otherAccount = new Models.Account(); otherAccount.name = "Outra conta";
            store.saveAccount(otherAccount);
            entry.id = 0; entry.accountId = otherAccount.id;
            try {
                store.saveReviewedNotification(entry);
                fail("The same generated notification key must be rejected in every account");
            } catch (IllegalArgumentException expected) {
                assertEquals("Esta notificação já foi registrada. Atualize a lista.", expected.getMessage());
            }
            inbox.dismiss(candidate.id);
            assertFalse(enqueue("reused", NOW));
            assertTrue(enqueue("reused", NOW + 500));
            assertFalse(store.hasNotificationSource(inbox.pending().get(0).sourceKey));
            assertEquals(1, store.entries().size());
        }
    }

    @Test public void pendingRetentionErasesPayloadAndPreservesRecentDedup() {
        enable();
        long oldPost = NOW - TimeUnit.DAYS.toMillis(29);
        assertTrue(enqueue("one", oldPost));
        NotificationInbox.Candidate candidate = inbox.pending().get(0);
        inbox.close();
        inbox = new NotificationInbox(context, Clock.fixed(Instant.ofEpochMilli(NOW + TimeUnit.DAYS.toMillis(2)), ZoneOffset.UTC));
        assertEquals(0, inbox.pendingCount());
        assertEquals(candidate.sourceKey, inbox.sourceKey(candidate.id));
        assertFalse(enqueue("one", oldPost));
        assertTrue(enqueue("one", NOW + TimeUnit.DAYS.toMillis(2)));
        assertFalse(enqueue("too-old", NOW - TimeUnit.DAYS.toMillis(40)));
    }

    @Test public void pendingCapacityKeepsNewestHundredAndRetainsEvictedMarker() {
        enable();
        for (int n = 0; n < 105; n++) assertTrue(enqueue("id-" + n, NOW + n));
        List<NotificationInbox.Candidate> pending = inbox.pending();
        assertEquals(100, pending.size());
        assertEquals(NOW + 104, pending.get(0).postedAt);
        assertEquals(NOW + 5, pending.get(99).postedAt);
        assertFalse(enqueue("id-0", NOW));
    }

    @Test public void clearPendingPreservesMarkersWhileClearHistoryAllowsExplicitRecapture() {
        enable(); assertTrue(enqueue("one", NOW));
        inbox.clearPending(); assertEquals(0, inbox.pendingCount());
        assertFalse(enqueue("one", NOW));
        inbox.clearHistory();
        assertTrue(inbox.isEnabled()); assertEquals(Collections.singleton(APP), inbox.allowedPackages());
        assertTrue(enqueue("one", NOW));
        inbox.clear(); assertEquals(0, inbox.pendingCount());
    }

    @Test public void reviewedDedupExpiresAfterNinetyDays() {
        enable(); assertTrue(enqueue("one", NOW)); inbox.clearPending();
        inbox.close();
        long later = NOW + TimeUnit.DAYS.toMillis(91);
        inbox = new NotificationInbox(context, Clock.fixed(Instant.ofEpochMilli(later), ZoneOffset.UTC));
        assertEquals(0, inbox.pendingCount());
        try (SQLiteDatabase database = context.openOrCreateDatabase(NotificationInbox.DATABASE_NAME, 0, null);
                Cursor c = database.rawQuery("SELECT COUNT(*) FROM candidates", null)) {
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0));
        }
    }

    @Test public void historyCapacityIsBoundedAndDoesNotRemovePendingSuggestions() {
        enable(); assertTrue(enqueue("pending", NOW));
        try (SQLiteDatabase database = context.openOrCreateDatabase(NotificationInbox.DATABASE_NAME, 0, null)) {
            database.beginTransaction();
            try {
                for (int n = 0; n < 1005; n++) database.execSQL(
                        "INSERT INTO candidates(source_key,captured_at,pending) VALUES(?,?,0)",
                        new Object[]{"test-reviewed-" + n, NOW + n});
                database.setTransactionSuccessful();
            } finally { database.endTransaction(); }
        }
        assertEquals(1, inbox.pendingCount());
        try (SQLiteDatabase database = context.openOrCreateDatabase(NotificationInbox.DATABASE_NAME, 0, null);
                Cursor c = database.rawQuery("SELECT COUNT(*) FROM candidates", null)) {
            assertTrue(c.moveToFirst()); assertEquals(1000, c.getInt(0));
        }
        assertFalse(enqueue("pending", NOW));
    }

    private void enable() { inbox.setEnabled(true); inbox.setAllowedPackages(Collections.singleton(APP)); }

    private boolean enqueue(String key, long postedAt) {
        NotificationParser.Suggestion suggestion = NotificationParser.parse("Compra aprovada",
                "Compra de R$ 25,90 no cartão final 1234.", postedAt, ZoneId.of("UTC"));
        assertNotNull("Test fixture must be recognized by parser", suggestion);
        return inbox.enqueue(APP, "Banco de teste", key, postedAt, suggestion);
    }
}
