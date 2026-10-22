package br.com.myfinancehub;
import android.content.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PluggySettingsTest {
    Context context;PluggySettings settings;final String token="a".repeat(43);
    @Before public void before()throws Exception {context=RuntimeEnvironment.getApplication();context.getSharedPreferences("pluggy_private",0).edit().clear().commit();KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);javax.crypto.SecretKey key=generator.generateKey();
        settings=new PluggySettings(context,new PluggySettings.Vault(){public String seal(String t,String o)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);c.updateAAD(o.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(c.getIV())+":"+Base64.getEncoder().encodeToString(c.doFinal(t.getBytes(StandardCharsets.UTF_8)));}public String open(String t,String o)throws Exception{String[] parts=t.split(":");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));c.updateAAD(o.getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);}});
    }
    @After public void after(){context.getSharedPreferences("pluggy_private",0).edit().clear().commit();}
    @Test public void storesCiphertextAndRoundTripsToken()throws Exception {assertFalse(settings.configured());settings.save("https://finance.example",token);assertTrue(settings.configured());assertEquals(token,settings.token());for(Object value:context.getSharedPreferences("pluggy_private",0).getAll().values())assertFalse(value.toString().contains(token));}
    @Test public void originTamperingCannotReuseToken()throws Exception {settings.save("https://finance.example",token);context.getSharedPreferences("pluggy_private",0).edit().putString("origin","https://other.example").commit();try{settings.token();fail();}catch(IllegalStateException expected){}}
    @Test public void failedEncryptionPreservesPreviousConfiguration()throws Exception {settings.save("https://finance.example",token);PluggySettings broken=new PluggySettings(context,new PluggySettings.Vault(){public String seal(String t,String o)throws Exception{throw new Exception(token);}public String open(String t,String o){return "";}});try{broken.save("https://other.example",token);fail();}catch(IllegalStateException ex){assertFalse(ex.getMessage().contains(token));}assertEquals("https://finance.example",settings.origin());assertEquals(token,settings.token());}
    @Test public void disconnectDoesNotDeleteFinancialDataAndBackupExcludesToken()throws Exception {settings.save("https://finance.example",token);try(FinanceStore store=new FinanceStore(context)){String backup=store.exportBackup();assertFalse(backup.contains(token));assertFalse(backup.contains("ciphertext"));settings.clear();assertFalse(settings.configured());assertEquals(backup,store.exportBackup());}}
}
