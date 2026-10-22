package br.com.myfinancehub;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PluggyStoreTest {
    Context context;FinanceStore store;
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("myfinancehub.db");store=new FinanceStore(context);}
    @After public void after(){store.close();context.deleteDatabase("myfinancehub.db");}
    Models.Entry entry()throws Exception {PluggyClient.Transaction t=PluggyFixtures.snapshot().transactions.get(0);return new Models.Entry(0,store.accounts().get(0).id,0,0,t.description,t.date,t.direction,t.method,t.sourceKey,t.amount);}
    @Test public void sameProviderSourceCannotBeSavedAgainUnderAnotherLocalAccount()throws Exception {Models.Entry first=entry();store.saveReviewedPluggy(first);Models.Account account=new Models.Account(0,"Outra",0);store.saveAccount(account);Models.Entry second=entry();second.accountId=account.id;String backup=store.exportBackup();try{store.saveReviewedPluggy(second);fail();}catch(IllegalArgumentException expected){}assertEquals(backup,store.exportBackup());assertEquals(-2590,store.totalBalance());}
    @Test public void invalidReferenceRollsBackSourceAndRecordTogether()throws Exception {Models.Entry entry=entry();entry.accountId=9999;String backup=store.exportBackup();try{store.saveReviewedPluggy(entry);fail();}catch(IllegalArgumentException expected){}assertEquals(backup,store.exportBackup());assertFalse(store.hasPluggySource(entry.sourceKey));entry.accountId=store.accounts().get(0).id;store.saveReviewedPluggy(entry);assertEquals(1,store.entries().size());}
    @Test public void originSurvivesBackupAndRestoreAndStillPreventsRepeating()throws Exception {Models.Entry entry=entry();store.saveReviewedPluggy(entry);String backup=store.exportBackup();store.restoreBackup(backup);assertTrue(store.hasPluggySource(entry.sourceKey));try{store.saveReviewedPluggy(entry());fail();}catch(IllegalArgumentException expected){}assertEquals(backup,store.exportBackup());}
    @Test public void unrelatedCsvOrNotificationOriginsAreNotDeletedOrSilentlyMatched()throws Exception {Models.Entry csv=entry();csv.sourceKey="csv-existing";store.saveEntry(csv);String key=entry().sourceKey;assertFalse(store.hasPluggySource(key));store.saveReviewedPluggy(entry());assertEquals(2,store.entries().size());assertEquals(-5180,store.totalBalance());}
}
