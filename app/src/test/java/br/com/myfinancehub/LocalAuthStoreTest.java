package br.com.myfinancehub;

import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.*;
import java.util.Base64;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public final class LocalAuthStoreTest {
    private Context context;private MutableClock clock;private LocalAuthStore store;
    private static final String PASSWORD="MinhaSenha123@",NEW_PASSWORD="OutraSenha456!";
    @Before public void before(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("local_auth",0).edit().clear().commit();clock=new MutableClock();store=new LocalAuthStore(context,clock);}
    @After public void after(){context.getSharedPreferences("local_auth",0).edit().clear().commit();}

    @Test public void accountNormalizesUsernameAndVerifiesPasswordWithoutPersistingSecrets()throws Exception {
        assertFalse(store.configured());String recovery=store.create("  Meu.Usuario  ",PASSWORD.toCharArray());
        assertTrue(store.configured());assertEquals("meu.usuario",store.username());assertTrue(store.verify("MEU.USUARIO",PASSWORD.toCharArray()));
        assertFalse(store.verify("outro.usuario",PASSWORD.toCharArray()));assertFalse(store.verify("meu.usuario","SenhaErrada123!".toCharArray()));
        for(Object value:context.getSharedPreferences("local_auth",0).getAll().values()){assertFalse(value.toString().contains(PASSWORD));assertFalse(value.toString().contains(recovery));assertFalse(value.toString().contains(recovery.replace("-","")));}
        assertEquals(32,Base64.getDecoder().decode(context.getSharedPreferences("local_auth",0).getString("password_salt","")).length);
        assertEquals(32,Base64.getDecoder().decode(context.getSharedPreferences("local_auth",0).getString("password_hash","")).length);
    }
    @Test public void weakPasswordCannotCreateOrOverwriteAccount()throws Exception {
        try{store.create("usuario","fraca".toCharArray());fail();}catch(IllegalArgumentException expected){}
        assertFalse(store.configured());store.create("usuario",PASSWORD.toCharArray());
        try{store.create("invasor",NEW_PASSWORD.toCharArray());fail();}catch(IllegalStateException expected){}
        assertTrue(store.verify("usuario",PASSWORD.toCharArray()));
    }
    @Test public void saltsAndRecoveryCodesAreIndependentAcrossCreations()throws Exception {
        String firstCode=store.create("usuario",PASSWORD.toCharArray());Map<String,?> first=context.getSharedPreferences("local_auth",0).getAll();
        context.getSharedPreferences("local_auth",0).edit().clear().commit();String secondCode=store.create("usuario",PASSWORD.toCharArray());Map<String,?> second=context.getSharedPreferences("local_auth",0).getAll();
        assertNotEquals(firstCode,secondCode);assertNotEquals(first.get("password_salt"),second.get("password_salt"));assertNotEquals(first.get("password_hash"),second.get("password_hash"));assertNotEquals(second.get("password_salt"),second.get("recovery_salt"));
    }
    @Test public void fiveFailuresPersistThirtySecondLockAndSuccessfulLoginClearsIt()throws Exception {
        store.create("usuario",PASSWORD.toCharArray());for(int attempt=0;attempt<5;attempt++)assertFalse(store.verify("usuario","errada".toCharArray()));
        LocalAuthStore reopened=new LocalAuthStore(context,clock);locked(reopened);clock.advanceSeconds(29);locked(reopened);clock.advanceSeconds(1);assertTrue(reopened.verify("usuario",PASSWORD.toCharArray()));
        for(int attempt=0;attempt<4;attempt++)assertFalse(reopened.verify("usuario","errada".toCharArray()));assertTrue(reopened.verify("usuario",PASSWORD.toCharArray()));
    }
    @Test public void repeatedFailureAfterLockExpiresIncreasesBackoff()throws Exception {
        store.create("usuario",PASSWORD.toCharArray());for(int attempt=0;attempt<5;attempt++)store.verify("usuario","errada".toCharArray());clock.advanceSeconds(30);assertFalse(store.verify("usuario","errada".toCharArray()));clock.advanceSeconds(30);locked(store);clock.advanceSeconds(30);assertTrue(store.verify("usuario",PASSWORD.toCharArray()));
    }
    @Test public void recoveryRotatesCodeAndInvalidatesPreviousPasswordWithoutTouchingFinancialRecords()throws Exception {
        context.deleteDatabase("myfinancehub.db");try(FinanceStore finance=new FinanceStore(context)){
            String backup=finance.exportBackup();String originalCode=store.create("usuario",PASSWORD.toCharArray());
            try{store.resetPassword("usuario","CODIGO-INCORRETO",NEW_PASSWORD.toCharArray());fail();}catch(IllegalArgumentException expected){}
            String newCode=store.resetPassword("USUARIO",originalCode,NEW_PASSWORD.toCharArray());assertNotEquals(originalCode,newCode);assertFalse(store.verify("usuario",PASSWORD.toCharArray()));assertTrue(store.verify("usuario",NEW_PASSWORD.toCharArray()));
            try{store.resetPassword("usuario",originalCode,PASSWORD.toCharArray());fail();}catch(IllegalArgumentException expected){}
            store.resetPassword("usuario",newCode,PASSWORD.toCharArray());assertTrue(store.verify("usuario",PASSWORD.toCharArray()));assertEquals(backup,finance.exportBackup());
        }finally{context.deleteDatabase("myfinancehub.db");}
    }
    @Test public void passwordChangeRequiresCurrentPasswordAndValidReplacement()throws Exception {
        store.create("usuario",PASSWORD.toCharArray());try{store.changePassword("errada".toCharArray(),NEW_PASSWORD.toCharArray());fail();}catch(IllegalArgumentException expected){}
        try{store.changePassword(PASSWORD.toCharArray(),"fraca".toCharArray());fail();}catch(IllegalArgumentException expected){}
        assertTrue(store.verify("usuario",PASSWORD.toCharArray()));store.changePassword(PASSWORD.toCharArray(),NEW_PASSWORD.toCharArray());assertFalse(store.verify("usuario",PASSWORD.toCharArray()));assertTrue(store.verify("usuario",NEW_PASSWORD.toCharArray()));
    }
    private void locked(LocalAuthStore value)throws Exception {try{value.verify("usuario",PASSWORD.toCharArray());fail("Persistent lock must reject even the correct password temporarily");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("Aguarde"));}}
    private static final class MutableClock extends Clock {
        private Instant instant=Instant.parse("2026-10-22T12:00:00Z");
        void advanceSeconds(long seconds){instant=instant.plusSeconds(seconds);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return instant;}
    }
}
