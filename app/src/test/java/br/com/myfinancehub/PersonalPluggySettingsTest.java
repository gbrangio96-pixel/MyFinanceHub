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
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public final class PersonalPluggySettingsTest {
    private static final String CLIENT="personal-fixture-client",SECRET="personal-fixture-secret";
    private final List<String> items=Arrays.asList("item-a","item-b");
    private Context context;private SharedPreferences preferences;private PersonalPluggySettings settings;
    @Before public void before()throws Exception {
        context=RuntimeEnvironment.getApplication();preferences=context.getSharedPreferences("personal_pluggy_private",0);preferences.edit().clear().commit();
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);javax.crypto.SecretKey key=generator.generateKey();
        settings=new PersonalPluggySettings(context,new PluggySettings.Vault(){
            public String seal(String value,String purpose)throws Exception {assertEquals(PersonalPluggySettings.PURPOSE,purpose);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(cipher.getIV())+":"+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));}
            public String open(String value,String purpose)throws Exception {assertEquals(PersonalPluggySettings.PURPOSE,purpose);String[] parts=value.split(":");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);}
        });
    }
    @After public void after(){preferences.edit().clear().commit();}
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
}
