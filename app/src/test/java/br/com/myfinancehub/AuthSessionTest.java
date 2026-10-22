package br.com.myfinancehub;

import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=FinanceApplication.class)
public final class AuthSessionTest {
    private Context context;private FakeTime time;
    static final class FakeTime implements AuthSession.TimeSource {
        long elapsed=1000;int boot=4;
        public long elapsedRealtime(){return elapsed;}
        public int bootCount(){return boot;}
        void advance(long millis){elapsed+=millis;}
    }
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("myfinancehub.db");context.getSharedPreferences("auth_session",0).edit().clear().commit();time=new FakeTime();AuthSession.initialize(context,time);}
    @After public void after(){AuthSession.lock();context.deleteDatabase("myfinancehub.db");AuthSession.initialize(context);}
    @Test public void backgroundReturnBeforeTenMinutesDoesNotNeedAnotherLogin(){
        AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();
        time.advance(AuthSession.BACKGROUND_GRACE_MILLIS-1);assertTrue(AuthSession.isUnlocked());
        AuthSession.onForeground();assertTrue(AuthSession.isUnlocked());
    }
    @Test public void deadlineExpiresAtTenMinutesAndCannotBeExtendedByReturning(){
        AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();
        time.advance(AuthSession.BACKGROUND_GRACE_MILLIS);assertFalse(AuthSession.isUnlocked());
        AuthSession.onForeground();assertFalse(AuthSession.isUnlocked());
    }
    @Test public void activeUseDoesNotExpireAndNextBackgroundStartsANewGracePeriod(){
        AuthSession.onForeground();AuthSession.unlock();time.advance(20*60*1000);assertTrue(AuthSession.isUnlocked());
        AuthSession.onBackground();time.advance(AuthSession.BACKGROUND_GRACE_MILLIS-1);assertTrue(AuthSession.isUnlocked());
        time.advance(1);assertFalse(AuthSession.isUnlocked());
    }
    @Test public void repeatedBackgroundEventsDoNotRenewAnInactiveLease(){
        AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();time.advance(5*60*1000);
        AuthSession.onBackground();time.advance(5*60*1000);assertFalse(AuthSession.isUnlocked());
    }
    @Test public void restoredProcessRetainsOnlyTheRemainingGracePeriod(){
        AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();time.advance(6*60*1000);
        AuthSession.initialize(context,time);assertTrue(AuthSession.isUnlocked());
        time.advance(4*60*1000);assertFalse(AuthSession.isUnlocked());
    }
    @Test public void manualLockInvalidatesBothMemoryAndPersistedLease(){
        AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();AuthSession.lock();
        assertFalse(AuthSession.isUnlocked());AuthSession.initialize(context,time);assertFalse(AuthSession.isUnlocked());
    }
    @Test public void rebootElapsedRollbackAndMalformedLeaseCannotRestoreAuthentication(){
        AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();time.boot++;
        AuthSession.initialize(context,time);assertFalse(AuthSession.isUnlocked());
        AuthSession.unlock();time.elapsed--;AuthSession.initialize(context,time);assertFalse(AuthSession.isUnlocked());
        context.getSharedPreferences("auth_session",0).edit().putInt("version",1).putInt("boot_count",time.boot).putLong("started_elapsed",time.elapsed).putLong("expires_elapsed",Long.MAX_VALUE).commit();
        AuthSession.initialize(context,time);assertFalse(AuthSession.isUnlocked());
    }
    @Test public void bootCountUnavailableKeepsInProcessGraceButNeverRestoresIt(){
        time.boot=-1;AuthSession.initialize(context,time);AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();
        time.advance(1000);assertTrue(AuthSession.isUnlocked());AuthSession.initialize(context,time);assertFalse(AuthSession.isUnlocked());
    }
    @Test public void backgroundLeaseHasNoCredentialsAndNeverEntersFinancialBackup(){
        AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();
        assertFalse(context.getSharedPreferences("auth_session",0).getAll().containsKey("password"));
        try(FinanceStore finance=new FinanceStore(context)){String backup=finance.exportBackup();assertFalse(backup.contains("started_elapsed"));assertFalse(backup.contains("expires_elapsed"));}
    }
}
