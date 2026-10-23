package br.com.myfinancehub;

import android.content.*;
import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Personal API credentials are entered by their owner and encrypted only on this device. */
public final class PersonalPluggySettings {
    static final String PURPOSE="MyFinanceHub:personal-pluggy:v1";
    private final Context application;private final SharedPreferences prefs;private final PluggySettings.Vault vault;
    public static final class Credentials {
        public final String clientId,clientSecret;public final List<String> itemIds;
        Credentials(String id,String secret,List<String> items){clientId=id;clientSecret=secret;itemIds=Collections.unmodifiableList(new ArrayList<>(items));}
    }
    public PersonalPluggySettings(Context context){this(context,new PluggySettings.AndroidVault());}
    PersonalPluggySettings(Context context,PluggySettings.Vault vault){application=context.getApplicationContext();prefs=context.getSharedPreferences("personal_pluggy_private",Context.MODE_PRIVATE);this.vault=vault;}
    public boolean configured(){return !encryptedCredentials().isEmpty();}
    /** Opaque identity for the encrypted configuration; no decryption or credential access. */
    public String revision(){
        String encrypted=encryptedCredentials();if(encrypted.isEmpty())return "";
        try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(encrypted.getBytes(StandardCharsets.UTF_8));StringBuilder value=new StringBuilder();for(byte b:hash)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("Não foi possível identificar a configuração Meu Pluggy.");}
    }
    private String encryptedCredentials(){try{String value=prefs.getString("credentials","");return value==null?"":value;}catch(ClassCastException invalid){return "";}}
    public void save(String id,String secret,List<String> items)throws Exception {
        PluggyPersonalClient.validateCredentials(id,secret,items);
        JSONArray ids=new JSONArray();for(String item:items)ids.put(item);
        String encrypted;try{encrypted=vault.seal(new JSONObject().put("clientId",id).put("clientSecret",secret).put("itemIds",ids).toString(),PURPOSE);}catch(Exception ex){throw new IllegalStateException("Não foi possível proteger as credenciais no aparelho. A configuração anterior foi mantida.");}
        Map<String,?> previous=prefs.getAll();
        if(!prefs.edit().putString("credentials",encrypted).commit()){restore(previous);throw new IllegalStateException("Não foi possível salvar a conexão.");}
        PluggySnapshotCache.clear(application);
    }
    public Credentials load()throws Exception {
        try{JSONObject values=new JSONObject(vault.open(prefs.getString("credentials",""),PURPOSE));String id=values.getString("clientId"),secret=values.getString("clientSecret");List<String> items=new ArrayList<>();JSONArray ids=values.getJSONArray("itemIds");for(int i=0;i<ids.length();i++)items.add(ids.getString(i));PluggyPersonalClient.validateCredentials(id,secret,items);return new Credentials(id,secret,items);}
        catch(Exception ex){throw new IllegalStateException("Configure sua conexão pessoal. Se a chave do aparelho mudou, informe as credenciais novamente.");}
    }
    public boolean consultOnEntry(){return configured()&&prefs.getBoolean("consult_on_entry",true);}
    public void setConsultOnEntry(boolean enabled){prefs.edit().putBoolean("consult_on_entry",enabled).apply();}
    public void clear(){Map<String,?> previous=prefs.getAll();if(!prefs.edit().clear().commit()){restore(previous);throw new IllegalStateException("Não foi possível remover a configuração.");}PluggySnapshotCache.clear(application);}
    // SharedPreferences changes its in-memory map even when a synchronous disk write fails.
    // Restore that map before reporting failure so a working in-process connection stays coherent.
    @android.annotation.SuppressLint("ApplySharedPref")
    @SuppressWarnings("unchecked")
    private void restore(Map<String,?> previous){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:previous.entrySet()){
        String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);
    }edit.commit();}
}
