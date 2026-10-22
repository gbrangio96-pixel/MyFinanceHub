package br.com.myfinancehub;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Only an authenticated ciphertext is persisted. Provider credentials never belong on Android. */
public final class PluggySettings {
    interface Vault {String seal(String token,String origin)throws Exception;String open(String ciphertext,String origin)throws Exception;}
    private final SharedPreferences prefs;private final Vault vault;
    public PluggySettings(Context context){this(context,new AndroidVault());}
    PluggySettings(Context context,Vault vault){this.prefs=context.getSharedPreferences("pluggy_private",Context.MODE_PRIVATE);this.vault=vault;}
    public String origin(){return prefs.getString("origin","");}
    public boolean configured(){return !origin().isEmpty()&&!prefs.getString("ciphertext","").isEmpty();}
    public String token()throws Exception {try{return PluggyClient.token(vault.open(prefs.getString("ciphertext",""),PluggyClient.endpoint(origin())));}catch(Exception ex){throw new IllegalStateException("Não foi possível abrir o token protegido. Informe-o novamente ou desconecte.");}}
    public void save(String origin,String token)throws Exception {
        String normalized=PluggyClient.endpoint(origin);String valid=PluggyClient.token(token);
        String encrypted;try{encrypted=vault.seal(valid,normalized);}catch(Exception ex){throw new IllegalStateException("Não foi possível proteger o token no aparelho. A configuração anterior foi mantida.");}
        if(!prefs.edit().putString("origin",normalized).putString("ciphertext",encrypted).commit())throw new IllegalStateException("Não foi possível salvar a conexão.");
    }
    public void clear(){if(!prefs.edit().clear().commit())throw new IllegalStateException("Não foi possível remover a conexão.");}
    static final class AndroidVault implements Vault {
        private static final String ALIAS="myfinancehub.pluggy.v1";
        private synchronized javax.crypto.SecretKey key()throws Exception {
            KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
            if(!store.containsAlias(ALIAS)){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());generator.generateKey();}
            return (javax.crypto.SecretKey)store.getKey(ALIAS,null);
        }
        public String seal(String token,String origin)throws Exception {Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(origin.getBytes(StandardCharsets.UTF_8));return Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}
        public String open(String value,String origin)throws Exception {String[] parts=value.split(":",-1);if(parts.length!=2)throw new IllegalArgumentException();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));cipher.updateAAD(origin.getBytes(StandardCharsets.UTF_8));return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);}
    }
}
