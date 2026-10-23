package br.com.myfinancehub;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public final class PersonalPluggySettingsTest {
    private static final String CLIENT="personal-fixture-client",SECRET="personal-fixture-secret";
    private final List<String> items=Arrays.asList("item-a","item-b");
    private Context context;private SharedPreferences preferences;private PersonalPluggySettings settings;
    @Before public void before()throws Exception {
        context=RuntimeEnvironment.getApplication();preferences=context.getSharedPreferences("personal_pluggy_private",0);preferences.edit().clear().commit();PluggySnapshotCache.clear(context);
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);javax.crypto.SecretKey key=generator.generateKey();
        settings=new PersonalPluggySettings(context,new PluggySettings.Vault(){
            public String seal(String value,String purpose)throws Exception {assertEquals(PersonalPluggySettings.PURPOSE,purpose);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(cipher.getIV())+":"+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
            public String open(String value,String purpose)throws Exception {assertEquals(PersonalPluggySettings.PURPOSE,purpose);String[] parts=value.split(":");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);}
        });
    }
    @After public void after(){PluggySnapshotCache.clear(context);preferences.edit().clear().commit();}
    @Test public void storesOnlyCiphertextAndRestoresPersonalConfiguration()throws Exception {
        assertFalse(settings.configured());settings.save(CLIENT,SECRET,items);assertTrue(settings.configured());
        PersonalPluggySettings.Credentials restored=settings.load();assertEquals(CLIENT,restored.clientId);assertEquals(SECRET,restored.clientSecret);assertEquals(items,restored.itemIds);
        for(Object value:preferences.getAll().values()){assertFalse(value.toString().contains(CLIENT));assertFalse(value.toString().contains(SECRET));for(String item:items)assertFalse(value.toString().contains(item));}
        try{restored.itemIds.add("outside");fail("Caller must not mutate the saved item selection");}catch(UnsupportedOperationException expected){}
    }
    @Test public void invalidCredentialsAndItemsPreservePreviousEncryptedConfiguration()throws Exception {
        settings.save(CLIENT,SECRET,items);Map<String,?> before=preferences.getAll();
        for(String invalid:new String[]{""," whitespace ","control\nsecret"}){try{settings.save(CLIENT,invalid,items);fail();}catch(IllegalArgumentException expected){}assertEquals(before,preferences.getAll());}
        for(List<String> invalid:Arrays.asList(Collections.<String>emptyList(),Arrays.asList("same","same"),Arrays.asList("../outside"),Arrays.asList("a","b","c","d","e","f"))){try{settings.save(CLIENT,SECRET,invalid);fail();}catch(IllegalArgumentException expected){}assertEquals(before,preferences.getAll());}
        assertEquals(SECRET,settings.load().clientSecret);
    }
    @Test public void failedEncryptionKeepsTheWorkingConfigurationAndRedactsSecrets()throws Exception {
        settings.save(CLIENT,SECRET,items);Map<String,?> before=preferences.getAll();
        PersonalPluggySettings broken=new PersonalPluggySettings(context,new PluggySettings.Vault(){public String seal(String v,String p)throws Exception{throw new Exception(SECRET);}public String open(String v,String p){return "";}});
        try{broken.save("replacement",SECRET,items);fail();}catch(IllegalStateException expected){assertFalse(expected.getMessage().contains(SECRET));}
        assertEquals(before,preferences.getAll());assertEquals(CLIENT,settings.load().clientId);
    }
    @Test public void failedDecryptionIsRedactedAndDoesNotReplaceStoredCiphertext()throws Exception {
        settings.save(CLIENT,SECRET,items);Map<String,?> before=preferences.getAll();
        PersonalPluggySettings broken=new PersonalPluggySettings(context,new PluggySettings.Vault(){public String seal(String v,String p){return "";}public String open(String v,String p)throws Exception{throw new Exception(CLIENT+" "+SECRET);}});
        try{broken.load();fail();}catch(IllegalStateException expected){assertFalse(expected.getMessage().contains(SECRET));assertFalse(expected.getMessage().contains(CLIENT));}
        assertEquals(before,preferences.getAll());assertTrue(settings.configured());
    }
    @Test public void financialBackupExcludesConfigurationAndDisconnectPreservesLedger()throws Exception {
        context.deleteDatabase("myfinancehub.db");try(FinanceStore finance=new FinanceStore(context)){
            Models.Entry entry=new Models.Entry(0,finance.accounts().get(0).id,0,0,"Lançamento preservado","2026-10-20","expense","cash","",1234);finance.saveEntry(entry);String before=finance.exportBackup();
            settings.save(CLIENT,SECRET,items);String backup=finance.exportBackup();assertEquals(before,backup);assertFalse(backup.contains(CLIENT));assertFalse(backup.contains(SECRET));assertFalse(backup.contains("personal_pluggy_private"));
            settings.clear();assertFalse(settings.configured());assertEquals(before,finance.exportBackup());assertEquals(-1234,finance.totalBalance());
        }finally{context.deleteDatabase("myfinancehub.db");}
    }
    @Test public void revisionIdentifiesOnlyTheEncryptedConfigurationWithoutOpeningTheVault()throws Exception {
        assertEquals("",settings.revision());settings.save(CLIENT,SECRET,items);String first=settings.revision();
        assertTrue(first.matches("[0-9a-f]{64}"));assertEquals(first,settings.revision());
        PersonalPluggySettings sealedOnly=new PersonalPluggySettings(context,new PluggySettings.Vault(){
            public String seal(String value,String purpose){throw new AssertionError("Not used");}
            public String open(String value,String purpose){throw new AssertionError("Revision must not decrypt credentials");}
        });
        assertTrue(sealedOnly.configured());assertEquals(first,sealedOnly.revision());
        settings.setConsultOnEntry(false);assertEquals("An entry preference must not change account ownership",first,settings.revision());
        settings.save("replacement",SECRET,items);assertNotEquals(first,settings.revision());settings.clear();assertEquals("",settings.revision());
    }
    @Test public void successfulConfigurationChangesDiscardSnapshotButFailedChangesPreserveIt()throws Exception {
        settings.save(CLIENT,SECRET,items);PluggyClient.Snapshot snapshot=PluggyFixtures.snapshot();
        assertTrue(PluggySnapshotCache.publish(context,settings.revision(),snapshot));
        try{settings.save(CLIENT,"",items);fail();}catch(IllegalArgumentException expected){}
        assertSame(snapshot,PluggySnapshotCache.get(context));
        PersonalPluggySettings broken=new PersonalPluggySettings(context,new PluggySettings.Vault(){public String seal(String v,String p)throws Exception{throw new Exception("fixture-failure");}public String open(String v,String p){return "";}});
        try{broken.save("replacement",SECRET,items);fail();}catch(IllegalStateException expected){}
        assertSame(snapshot,PluggySnapshotCache.get(context));
        settings.save("replacement",SECRET,items);assertNull(PluggySnapshotCache.get(context));
        assertTrue(PluggySnapshotCache.publish(context,settings.revision(),snapshot));settings.clear();assertNull(PluggySnapshotCache.get(context));
    }
    @Test public void failedPreferenceWritesRestoreThePreviousConnectionAndSnapshot()throws Exception {
        settings.save(CLIENT,SECRET,items);Map<String,?> previous=preferences.getAll();PluggyClient.Snapshot snapshot=PluggyFixtures.snapshot();assertTrue(PluggySnapshotCache.publish(context,settings.revision(),snapshot));
        try{settingsFailingNextCommit().save("replacement","replacement-secret",items);fail();}catch(IllegalStateException expected){}
        assertEquals(previous,preferences.getAll());assertSame(snapshot,PluggySnapshotCache.get(context));assertEquals(CLIENT,settings.load().clientId);
        try{settingsFailingNextCommit().clear();fail();}catch(IllegalStateException expected){}
        assertEquals(previous,preferences.getAll());assertSame(snapshot,PluggySnapshotCache.get(context));assertEquals(SECRET,settings.load().clientSecret);
    }
    private PersonalPluggySettings settingsFailingNextCommit(){
        AtomicBoolean fail=new AtomicBoolean(true);android.content.SharedPreferences original=preferences;
        android.content.SharedPreferences failing=(android.content.SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{android.content.SharedPreferences.class},(proxy,method,args)->{
            if(!method.getName().equals("edit"))return method.invoke(original,args);
            android.content.SharedPreferences.Editor delegate=original.edit();
            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{android.content.SharedPreferences.Editor.class},(editor,operation,arguments)->{
                Object result=operation.invoke(delegate,arguments);
                if(operation.getName().equals("commit")&&fail.getAndSet(false))return false;
                return result instanceof android.content.SharedPreferences.Editor?editor:result;
            });
        });
        android.content.Context wrapper=new android.content.ContextWrapper(context){@Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return failing;}};
        return new PersonalPluggySettings(wrapper,new PluggySettings.Vault(){public String seal(String value,String purpose){return "replacement-ciphertext";}public String open(String value,String purpose){throw new AssertionError("Not used");}});
    }
}
