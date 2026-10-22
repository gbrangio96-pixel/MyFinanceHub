package br.com.myfinancehub;

import android.content.*;
import org.json.*;
import java.util.*;

/** Personal API credentials are entered by their owner and encrypted only on this device. */
public final class PersonalPluggySettings {
    static final String PURPOSE="MyFinanceHub:personal-pluggy:v1";
    private final SharedPreferences prefs;private final PluggySettings.Vault vault;
    public static final class Credentials {
        public final String clientId,clientSecret;public final List<String> itemIds;
        Credentials(String id,String secret,List<String> items){clientId=id;clientSecret=secret;itemIds=Collections.unmodifiableList(new ArrayList<>(items));}
    }
    public PersonalPluggySettings(Context context){this(context,new PluggySettings.AndroidVault());}
    PersonalPluggySettings(Context context,PluggySettings.Vault vault){prefs=context.getSharedPreferences("personal_pluggy_private",Context.MODE_PRIVATE);this.vault=vault;}
    public boolean configured(){return !prefs.getString("credentials","").isEmpty();}
    public void save(String id,String secret,List<String> items)throws Exception {
        PluggyPersonalClient.validateCredentials(id,secret,items);
        JSONArray ids=new JSONArray();for(String item:items)ids.put(item);
        String encrypted;try{encrypted=vault.seal(new JSONObject().put("clientId",id).put("clientSecret",secret).put("itemIds",ids).toString(),PURPOSE);}catch(Exception ex){throw new IllegalStateException("Não foi possível proteger as credenciais no aparelho. A configuração anterior foi mantida.");}
        if(!prefs.edit().putString("credentials",encrypted).commit())throw new IllegalStateException("Não foi possível salvar a conexão.");
    }
    public Credentials load()throws Exception {
        try{JSONObject values=new JSONObject(vault.open(prefs.getString("credentials",""),PURPOSE));String id=values.getString("clientId"),secret=values.getString("clientSecret");List<String> items=new ArrayList<>();JSONArray ids=values.getJSONArray("itemIds");for(int i=0;i<ids.length();i++)items.add(ids.getString(i));PluggyPersonalClient.validateCredentials(id,secret,items);return new Credentials(id,secret,items);}
        catch(Exception ex){throw new IllegalStateException("Configure sua conexão pessoal. Se a chave do aparelho mudou, informe as credenciais novamente.");}
    }
    public boolean consultOnEntry(){return configured()&&prefs.getBoolean("consult_on_entry",true);}
    public void setConsultOnEntry(boolean enabled){prefs.edit().putBoolean("consult_on_entry",enabled).apply();}
    public void clear(){if(!prefs.edit().clear().commit())throw new IllegalStateException("Não foi possível remover a configuração.");}
}
