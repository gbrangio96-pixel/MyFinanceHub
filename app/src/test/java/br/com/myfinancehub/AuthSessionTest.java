package br.com.myfinancehub;

import android.app.KeyguardManager;
import android.content.*;
import android.os.PowerManager;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=FinanceApplication.class)
public final class AuthSessionTest {
    private Context context;private FakeTime time;
    static final class FakeTime implements AuthSession.TimeSource {
        long elapsed=1000;int boot=4;
        public long elapsedRealtime(){return elapsed;}
        public int bootCount(){return boot;}
        void advance(long millis){elapsed+=millis;}
    }
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("myfinancehub.db");context.getSharedPreferences("auth_session",0).edit().clear().commit();deviceAvailable();time=new FakeTime();AuthSession.initialize(context,time);}
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
    @Test public void newProcessRequiresAuthenticationEvenBeforeTheGraceExpires(){
        AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();time.advance(6*60*1000);
        assertTrue(AuthSession.isUnlocked());
        // A new process cannot observe whether the phone locked while it was absent.
        AuthSession.initialize(context,time);assertFalse(AuthSession.isUnlocked());
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
    @Test public void previousVersionPersistedGrantIsDiscardedEvenWhenStillWithinItsDeadline(){
        context.getSharedPreferences("auth_session",0).edit().putInt("version",1).putInt("boot_count",time.boot)
                .putLong("started_elapsed",time.elapsed).putLong("expires_elapsed",time.elapsed+AuthSession.BACKGROUND_GRACE_MILLIS).commit();
        AuthSession.initialize(context,time);assertFalse(AuthSession.isUnlocked());
        assertTrue(context.getSharedPreferences("auth_session",0).getAll().isEmpty());
    }
    @Test public void bootCountUnavailableKeepsInProcessGraceButNeverRestoresIt(){
        time.boot=-1;AuthSession.initialize(context,time);AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();
        time.advance(1000);assertTrue(AuthSession.isUnlocked());AuthSession.initialize(context,time);assertFalse(AuthSession.isUnlocked());
    }
    @Test public void backgroundLeaseHasNoCredentialsAndNeverEntersFinancialBackup(){
        AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();
        assertTrue("No authentication grant may survive a process restart",context.getSharedPreferences("auth_session",0).getAll().isEmpty());
        try(FinanceStore finance=new FinanceStore(context)){String backup=finance.exportBackup();assertFalse(backup.contains("started_elapsed"));assertFalse(backup.contains("expires_elapsed"));}
    }
    @Test public void screenOffLocksImmediatelyBeforeTheGraceAndScreenOnCannotRestoreAccess(){
        AuthSession.onForeground();AuthSession.unlock();AuthSession.onBackground();time.advance(1000);
        assertTrue(AuthSession.isUnlocked());long attempt=AuthSession.generation();
        // Even phones with a delayed keyguard lock must lose the app session on screen-off.
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));idle();
        assertFalse(AuthSession.isUnlocked());assertFalse(AuthSession.unlockIfCurrent(attempt));
        context.sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));context.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));idle();
        AuthSession.onForeground();assertFalse(AuthSession.isUnlocked());
    }
    @Test public void deviceLockedStateInvalidatesAccessWithoutAReceivedBroadcast(){
        AuthSession.onForeground();AuthSession.unlock();long attempt=AuthSession.generation();
        shadowOf((KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).setIsDeviceLocked(true);
        assertFalse(AuthSession.isUnlocked());assertFalse(AuthSession.unlockIfCurrent(attempt));
        deviceAvailable();assertFalse(AuthSession.isUnlocked());assertFalse(AuthSession.unlockIfCurrent(attempt));
        assertTrue(AuthSession.unlockIfCurrent(AuthSession.generation()));
    }
    @Test public void keyguardAndNonInteractiveScreenIndependentlyRefuseAuthentication(){
        shadowOf((KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).setKeyguardLocked(true);
        assertFalse(AuthSession.unlockIfCurrent(AuthSession.generation()));AuthSession.unlock();assertFalse(AuthSession.isUnlocked());
        deviceAvailable();AuthSession.unlock();assertTrue(AuthSession.isUnlocked());
        shadowOf((PowerManager)context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(false);
        assertFalse(AuthSession.isUnlocked());assertFalse(AuthSession.unlockIfCurrent(AuthSession.generation()));
        deviceAvailable();assertFalse(AuthSession.isUnlocked());
    }
    @Test public void manualLockAndTimeoutInvalidatePendingAuthenticationAttempts(){
        AuthSession.onForeground();AuthSession.unlock();long beforeLock=AuthSession.generation();AuthSession.lock();
        assertFalse(AuthSession.unlockIfCurrent(beforeLock));assertTrue(AuthSession.unlockIfCurrent(AuthSession.generation()));
        AuthSession.onBackground();long beforeExpiry=AuthSession.generation();time.advance(AuthSession.BACKGROUND_GRACE_MILLIS);
        assertFalse(AuthSession.isUnlocked());assertFalse(AuthSession.unlockIfCurrent(beforeExpiry));
    }
    @Test public void tenMinutesOutsideALockedLoginInvalidatesItsPendingAuthentication(){
        AuthSession.onForeground();long attempt=AuthSession.generation();AuthSession.onBackground();
        time.advance(AuthSession.BACKGROUND_GRACE_MILLIS-1);assertEquals(attempt,AuthSession.generation());
        time.advance(1);
        // The login was already locked: expiry must still reject a password result queued earlier.
        assertFalse(AuthSession.unlockIfCurrent(attempt));long expiredGeneration=AuthSession.generation();assertNotEquals(attempt,expiredGeneration);
        assertFalse(AuthSession.isUnlocked());assertEquals("Expiry must invalidate once",expiredGeneration,AuthSession.generation());
        AuthSession.onForeground();assertFalse(AuthSession.unlockIfCurrent(attempt));assertTrue(AuthSession.unlockIfCurrent(AuthSession.generation()));
    }
    private void deviceAvailable(){shadowOf((PowerManager)context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(true);shadowOf((KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).setIsDeviceLocked(false);shadowOf((KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).setKeyguardLocked(false);}
    private static void idle(){shadowOf(android.os.Looper.getMainLooper()).idle();}
}
