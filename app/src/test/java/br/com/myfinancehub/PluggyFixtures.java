package br.com.myfinancehub;
import org.json.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
final class PluggyFixtures {
    static final LocalDate DAY=LocalDate.of(2026,10,20);
    static JSONObject data()throws Exception {
        JSONObject account=new JSONObject().put("externalId","bank-a").put("itemId","item-a").put("name","Banco de teste").put("kind","BANK").put("currency","BRL").put("reportedBalanceCents","987654");
        JSONObject row=new JSONObject().put("externalId","tx-a").put("externalAccountId","bank-a").put("itemId","item-a").put("providerTransactionId",JSONObject.NULL).put("identityKind","id").put("sourceKey",key("item-a","bank-a","id","tx-a")).put("direction","expense").put("status","POSTED").put("amountCents","2590").put("date",DAY.toString()).put("sourceDate",DAY+"T00:00:00Z").put("description","Mercado de teste").put("method","pix");
        return new JSONObject().put("schemaVersion",1).put("provider","pluggy").put("observedAt","2026-10-22T12:00:00Z").put("range",new JSONObject().put("from",DAY.toString()).put("to",DAY.toString())).put("connections",new JSONArray().put(new JSONObject().put("externalId","item-a").put("status","UPDATED").put("providerUpdatedAt","2026-10-21T12:00:00Z"))).put("accounts",new JSONArray().put(account)).put("transactions",new JSONArray().put(row));
    }
    static String key(String... ids)throws Exception {JSONArray array=new JSONArray();for(String id:ids)array.put(id);byte[] bytes=MessageDigest.getInstance("SHA-256").digest(array.toString().getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder("pluggy:");for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    static PluggyClient.Snapshot snapshot()throws Exception{return PluggyClient.parse(data().toString(),DAY,DAY);}
}
