package br.com.myfinancehub;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public final class PluggySnapshotCacheTest {
    private Context context;
    private SharedPreferences preferences;
    @Before public void before(){
        context=RuntimeEnvironment.getApplication();
        preferences=context.getSharedPreferences("personal_pluggy_private",Context.MODE_PRIVATE);
        preferences.edit().clear().commit();PluggySnapshotCache.clear(context);
        context.deleteDatabase("myfinancehub.db");
    }
    @After public void after(){
        PluggySnapshotCache.clear(context);preferences.edit().clear().commit();
        context.deleteDatabase("myfinancehub.db");
    }
    private String configure(String ciphertext){
        preferences.edit().putString("credentials",ciphertext).commit();
        return new PersonalPluggySettings(context).revision();
    }
    @Test public void missingConfigurationAndMissingResponsesCannotBecomeAProviderSnapshot()throws Exception {
        PluggyClient.Snapshot snapshot=PluggyFixtures.snapshot();
        assertNull(PluggySnapshotCache.get(context));
        assertFalse(PluggySnapshotCache.publish(context,"",snapshot));
        assertFalse(PluggySnapshotCache.publish(context,null,snapshot));
        String revision=configure("fixture-ciphertext");
        assertFalse(PluggySnapshotCache.publish(context,revision,null));
        assertNull(PluggySnapshotCache.get(context));assertFalse(PluggySnapshotCache.isRecent(context,30_000));
    }
    @Test public void completeResponseRemainsAvailableWhileFreshnessExpiresAtExactlyThirtySeconds()throws Exception {
        String revision=configure("fixture-ciphertext");PluggyClient.Snapshot snapshot=PluggyFixtures.snapshot();
        assertTrue(PluggySnapshotCache.publish(context,revision,snapshot));
        assertSame(snapshot,PluggySnapshotCache.get(context));assertTrue(PluggySnapshotCache.isRecent(context,30_000));
        ShadowSystemClock.advanceBy(Duration.ofMillis(29_999));assertTrue(PluggySnapshotCache.isRecent(context,30_000));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));assertFalse(PluggySnapshotCache.isRecent(context,30_000));
        assertSame("An older response may be labelled with its original observation time",snapshot,PluggySnapshotCache.get(context));
        assertFalse(PluggySnapshotCache.isRecent(context,0));assertFalse(PluggySnapshotCache.isRecent(context,-1));
    }
    @Test public void replacingCredentialsHidesThePreviousAccountsBeforeAReplacementResponseArrives()throws Exception {
        String oldRevision=configure("first-fixture-ciphertext");PluggyClient.Snapshot previous=PluggyFixtures.snapshot();
        assertTrue(PluggySnapshotCache.publish(context,oldRevision,previous));
        String newRevision=configure("second-fixture-ciphertext");assertNotEquals(oldRevision,newRevision);
        assertNull(PluggySnapshotCache.get(context));assertFalse(PluggySnapshotCache.isRecent(context,30_000));
        assertFalse("A late old request cannot reintroduce the previous accounts",PluggySnapshotCache.publish(context,oldRevision,previous));
        PluggyClient.Snapshot replacement=PluggyFixtures.snapshot();assertTrue(PluggySnapshotCache.publish(context,newRevision,replacement));
        assertSame(replacement,PluggySnapshotCache.get(context));
    }
    @Test public void staleRequestCannotOverwriteANewerConfigurationResponse()throws Exception {
        String oldRevision=configure("first-fixture-ciphertext");PluggyClient.Snapshot old=PluggyFixtures.snapshot();
        String newRevision=configure("second-fixture-ciphertext");PluggyClient.Snapshot current=PluggyFixtures.snapshot();
        assertTrue(PluggySnapshotCache.publish(context,newRevision,current));
        assertFalse(PluggySnapshotCache.publish(context,oldRevision,old));
        assertSame(current,PluggySnapshotCache.get(context));
    }
    @Test public void removingConfigurationRejectsLateResponsesAndClearsVisibleProviderData()throws Exception {
        String revision=configure("fixture-ciphertext");PluggyClient.Snapshot snapshot=PluggyFixtures.snapshot();
        assertTrue(PluggySnapshotCache.publish(context,revision,snapshot));preferences.edit().clear().commit();
        assertNull(PluggySnapshotCache.get(context));assertFalse(PluggySnapshotCache.publish(context,revision,snapshot));
        assertFalse(PluggySnapshotCache.isRecent(context,30_000));
    }
    @Test public void providerResponsesNeverPersistOrChangeTheExistingFinancialLedgerOrBackup()throws Exception {
        String revision=configure("fixture-ciphertext");Map<String,?> privateBefore=new HashMap<>(preferences.getAll());
        try(FinanceStore finance=new FinanceStore(context)){
            Models.Entry legacy=new Models.Entry(0,finance.accounts().get(0).id,0,0,"Movimentação local anterior","2026-10-20","expense","cash","legacy-fixture",1234);
            finance.saveEntry(legacy);String before=finance.exportBackup();
            assertTrue(PluggySnapshotCache.publish(context,revision,PluggyFixtures.snapshot()));
            assertEquals(privateBefore,preferences.getAll());assertEquals(before,finance.exportBackup());
            assertFalse(finance.exportBackup().contains("Mercado de teste"));assertEquals(-1234,finance.totalBalance());
            PluggySnapshotCache.clear(context);assertNull(PluggySnapshotCache.get(context));
            assertEquals(privateBefore,preferences.getAll());assertEquals(before,finance.exportBackup());
        }
    }
}
