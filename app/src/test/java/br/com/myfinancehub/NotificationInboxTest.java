package br.com.myfinancehub;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public final class NotificationInboxTest {
    private static final long NOW=Instant.parse("2026-10-15T15:00:00Z").toEpochMilli();
    private Context context;private NotificationInbox inbox;
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(NotificationInbox.DATABASE_NAME);context.deleteDatabase("myfinancehub.db");inbox=new NotificationInbox(context);}
    @After public void after(){inbox.close();context.deleteDatabase(NotificationInbox.DATABASE_NAME);context.deleteDatabase("myfinancehub.db");}

    @Test public void captureCannotBeEnabledEvenWhenLegacyCallersSelectApps(){
        assertFalse(inbox.isEnabled());inbox.setAllowedPackages(Collections.singleton(NotificationLegacyFixtures.APP));inbox.setEnabled(true);
        assertFalse(inbox.isEnabled());assertFalse(enqueue("new",NOW));assertEquals(0,inbox.pendingCount());
        try(SQLiteDatabase database=context.openOrCreateDatabase(NotificationInbox.DATABASE_NAME,0,null);Cursor row=database.rawQuery("SELECT value FROM settings WHERE name='enabled'",null)){assertTrue(row.moveToFirst());assertEquals("0",row.getString(0));}
    }
    @Test public void oldEnabledFlagCannotCaptureAndDoesNotEraseTheLegacyArchive(){
        NotificationInbox.Candidate old=seed("old",NOW);NotificationLegacyFixtures.legacyEnabled(context,true);inbox.setAllowedPackages(Collections.singleton(NotificationLegacyFixtures.APP));
        assertFalse(inbox.isEnabled());assertFalse(enqueue("new",NOW+1000));assertEquals(1,inbox.pendingCount());assertEquals(old.id,inbox.pending().get(0).id);
        inbox.close();inbox=new NotificationInbox(context);assertFalse(inbox.isEnabled());assertFalse(enqueue("new",NOW+1000));assertEquals(old.sourceKey,inbox.pending().get(0).sourceKey);
    }
    @Test public void legacySelectionSurvivesReopenWithoutEnablingCaptureAndRejectsInvalidNamesAtomically(){
        inbox.setAllowedPackages(Collections.singleton(NotificationLegacyFixtures.APP));
        try{inbox.setAllowedPackages(new HashSet<>(Arrays.asList(NotificationLegacyFixtures.APP,"not a package")));fail();}catch(IllegalArgumentException expected){assertFalse(expected.getMessage().isEmpty());}
        inbox.close();inbox=new NotificationInbox(context);assertFalse(inbox.isEnabled());assertEquals(Collections.singleton(NotificationLegacyFixtures.APP),inbox.allowedPackages());
    }
    @Test public void archiveRoundTripsMinimalFieldsWithoutChangingFinancialBackup(){
        try(FinanceStore store=new FinanceStore(context)){
            String original=store.exportBackup();NotificationInbox.Candidate row=seed("legacy",NOW);
            assertEquals(NotificationLegacyFixtures.APP,row.packageName);assertEquals(NotificationLegacyFixtures.LABEL,row.appLabel);assertEquals(2590,row.amountCents);assertEquals("2026-10-15",row.date);assertEquals("expense",row.type);assertEquals("1234",row.lastFour);
            assertNotNull(row.description);assertNotNull(row.reviewNote);assertTrue(row.sourceKey.matches("notification:[a-f0-9]{64}"));assertEquals(row.sourceKey,inbox.sourceKey(row.id));
            assertEquals(original,store.exportBackup());assertTrue(store.entries().isEmpty());
        }
    }
    @Test public void readingOldArchiveDoesNotExpirePayloadsOrDedupMarkers(){
        long oldTime=NOW-TimeUnit.DAYS.toMillis(365);NotificationInbox.Candidate old=seed("old",oldTime);
        try(SQLiteDatabase database=context.openOrCreateDatabase(NotificationInbox.DATABASE_NAME,0,null)){database.execSQL("INSERT INTO candidates(source_key,captured_at,pending) VALUES(?,?,0)",new Object[]{"notification:"+"b".repeat(64),oldTime});}
        inbox.close();inbox=new NotificationInbox(context,Clock.fixed(Instant.ofEpochMilli(NOW+TimeUnit.DAYS.toMillis(400)),ZoneOffset.UTC));
        assertEquals(1,inbox.pendingCount());assertEquals(old.description,inbox.pending().get(0).description);assertEquals(old.amountCents,inbox.pending().get(0).amountCents);assertEquals(old.sourceKey,inbox.sourceKey(old.id));
        try(SQLiteDatabase database=context.openOrCreateDatabase(NotificationInbox.DATABASE_NAME,0,null);Cursor rows=database.rawQuery("SELECT COUNT(*) FROM candidates",null)){assertTrue(rows.moveToFirst());assertEquals(2,rows.getInt(0));}
    }
    @Test public void explicitlyDismissingALegacyRowErasesPayloadButKeepsItsOrigin(){
        NotificationInbox.Candidate old=seed("old",NOW);inbox.dismiss(old.id);assertEquals(0,inbox.pendingCount());assertEquals(old.sourceKey,inbox.sourceKey(old.id));
        try(SQLiteDatabase database=context.openOrCreateDatabase(NotificationInbox.DATABASE_NAME,0,null);Cursor rows=database.rawQuery("SELECT description,package_name,amount_cents FROM candidates WHERE _id="+old.id,null)){assertTrue(rows.moveToFirst());assertTrue(rows.isNull(0));assertTrue(rows.isNull(1));assertTrue(rows.isNull(2));}
        inbox.setEnabled(true);assertFalse(enqueue("new",NOW+1000));assertFalse(inbox.isEnabled());
    }
    @Test public void explicitArchiveRemovalNeverReactivatesCaptureOrTouchesFinances(){
        try(FinanceStore store=new FinanceStore(context)){
            String backup=store.exportBackup();NotificationInbox.Candidate old=seed("old",NOW);inbox.clearPending();assertEquals(0,inbox.pendingCount());assertEquals(old.sourceKey,inbox.sourceKey(old.id));
            inbox.clearHistory();inbox.setEnabled(true);assertFalse(enqueue("new",NOW+1000));assertFalse(inbox.isEnabled());assertEquals(backup,store.exportBackup());
        }
    }
    @Test public void historicalConfirmedOriginsRemainValidAndCannotBeDuplicatedAcrossAccounts(){
        NotificationInbox.Candidate old=seed("legacy-confirmed",NOW);
        try(FinanceStore store=new FinanceStore(context)){
            Models.Entry entry=new Models.Entry(0,store.accounts().get(0).id,0,0,old.description,old.date,old.type,old.method,old.sourceKey,old.amountCents);store.saveReviewedNotification(entry);
            String backup=store.exportBackup();Models.Account another=new Models.Account();another.name="Outra conta";store.saveAccount(another);entry.id=0;entry.accountId=another.id;
            try{store.saveReviewedNotification(entry);fail("An old confirmed origin must remain unique");}catch(IllegalArgumentException expected){assertEquals("Esta notificação já foi registrada. Atualize a lista.",expected.getMessage());}
            assertTrue(store.hasNotificationSource(old.sourceKey));assertEquals(1,store.entries().size());assertFalse(enqueue("new",NOW+1000));assertEquals(1,inbox.pendingCount());assertTrue(backup.contains(old.sourceKey));
        }
    }
    private NotificationInbox.Candidate seed(String key,long postedAt){return NotificationLegacyFixtures.seed(context,key,postedAt,suggestion(postedAt));}
    private boolean enqueue(String key,long postedAt){return inbox.enqueue(NotificationLegacyFixtures.APP,NotificationLegacyFixtures.LABEL,key,postedAt,suggestion(postedAt));}
    private NotificationParser.Suggestion suggestion(long postedAt){NotificationParser.Suggestion s=NotificationParser.parse("Compra aprovada","Compra de R$ 25,90 no cartão final 1234.",postedAt,ZoneOffset.UTC);assertNotNull(s);return s;}
}
