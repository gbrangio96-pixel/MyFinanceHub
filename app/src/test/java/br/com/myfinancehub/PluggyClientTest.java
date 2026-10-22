package br.com.myfinancehub;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.*;
import java.io.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PluggyClientTest {
    final String token="a".repeat(43);
    final Clock clock=Clock.fixed(Instant.parse("2026-10-22T02:00:00Z"),ZoneOffset.UTC);
    @Test public void fetchUsesOnlyAuthenticatedHttpsAndPreservesExactValuesAndDates()throws Exception {
        String body=PluggyFixtures.data().toString();
        PluggyClient client=new PluggyClient((uri,key)->{assertEquals("https://finance.example/v1/snapshot?from=2026-10-20&to=2026-10-20",uri.toString());assertEquals(token,key);assertFalse(uri.toString().contains(token));return new PluggyClient.Reply(200,body);},clock);
        PluggyClient.Snapshot snapshot=client.fetch("https://finance.example/",token,PluggyFixtures.DAY,PluggyFixtures.DAY);
        assertEquals(987654,snapshot.accounts.get(0).balance);assertEquals(2590,snapshot.transactions.get(0).amount);assertEquals("2026-10-20",snapshot.transactions.get(0).date);
        assertEquals("pix",snapshot.transactions.get(0).method);
    }
    @Test public void endpointRejectsCredentialsPathsQueriesAndNonHttpsBeforeNetwork()throws Exception {
        for(String url:new String[]{"http://finance.example","https://user:pass@finance.example","https://finance.example/a","https://finance.example?token=secret","https://finance.example/#frag","file:///etc/passwd","https://finance.example:70000"})try{PluggyClient.endpoint(url);fail(url);}catch(IllegalArgumentException expected){}
    }
    @Test public void invalidRangesAndTokenNeverReachTransport()throws Exception {
        AtomicInteger calls=new AtomicInteger();PluggyClient client=new PluggyClient((u,t)->{calls.incrementAndGet();return null;},clock);
        for(LocalDate[] range:new LocalDate[][]{{LocalDate.of(2026,1,1),PluggyFixtures.DAY},{PluggyFixtures.DAY,PluggyFixtures.DAY.minusDays(1)},{PluggyFixtures.DAY,LocalDate.of(2026,10,22)}})try{client.fetch("https://finance.example",token,range[0],range[1]);fail();}catch(IllegalArgumentException expected){}
        try{client.fetch("https://finance.example","short",PluggyFixtures.DAY,PluggyFixtures.DAY);fail();}catch(IllegalArgumentException expected){}
        assertEquals(0,calls.get());
    }
    @Test public void pendingIsNeverPromotedToPosted()throws Exception {JSONObject root=PluggyFixtures.data();root.getJSONArray("transactions").getJSONObject(0).put("status","PENDING");assertEquals("PENDING",parse(root).transactions.get(0).status);}
    @Test public void incompleteWrongSchemaAndNonBrlFailWithoutPartialResult()throws Exception {
        for(String field:new String[]{"accounts","transactions","connections","range","observedAt"}){JSONObject root=PluggyFixtures.data();root.remove(field);bad(root);}
        JSONObject root=PluggyFixtures.data();root.put("schemaVersion",2);bad(root);
        root=PluggyFixtures.data();root.getJSONArray("accounts").getJSONObject(0).put("currency","USD");bad(root);
    }
    @Test public void invalidCentsNeverBecomeZeroOrRounded()throws Exception {
        for(Object value:new Object[]{JSONObject.NULL,25.90,"0","-1","25.90","1e3","9007199254740992","9223372036854775808"}){JSONObject root=PluggyFixtures.data();root.getJSONArray("transactions").getJSONObject(0).put("amountCents",value);bad(root);}
    }
    @Test public void invalidReferencesDatesAndDuplicateSourcesFailClosed()throws Exception {
        JSONObject root=PluggyFixtures.data();root.getJSONArray("transactions").getJSONObject(0).put("itemId","outside");bad(root);
        root=PluggyFixtures.data();root.getJSONArray("transactions").getJSONObject(0).put("date","2026-10-19");bad(root);
        root=PluggyFixtures.data();root.getJSONArray("transactions").put(root.getJSONArray("transactions").getJSONObject(0));bad(root);
        root=PluggyFixtures.data();root.getJSONArray("transactions").getJSONObject(0).put("sourceKey","pluggy:"+"a".repeat(64));bad(root);
    }
    @Test public void backendErrorsAndRedirectsExposeNoResponseContent()throws Exception {
        for(int code:new int[]{301,302,307,400,401,502,503,500}){PluggyClient client=new PluggyClient((u,t)->new PluggyClient.Reply(code,"PRIVATE-BANK-DATA "+token),clock);try{client.fetch("https://finance.example",token,PluggyFixtures.DAY,PluggyFixtures.DAY);fail();}catch(IOException ex){assertFalse(ex.getMessage().contains(token));assertFalse(ex.getMessage().contains("PRIVATE"));}}
    }
    @Test public void networkErrorsAreRedacted()throws Exception {PluggyClient client=new PluggyClient((u,t)->{throw new IOException("Meu Pluggy: "+token);},clock);try{client.fetch("https://finance.example",token,PluggyFixtures.DAY,PluggyFixtures.DAY);fail();}catch(IOException ex){assertFalse(ex.getMessage().contains(token));}}
    @Test public void htmlAndOversizedBodiesAreRejected()throws Exception {
        for(PluggyClient.Reply reply:new PluggyClient.Reply[]{new PluggyClient.Reply(200,"{}","text/html"),new PluggyClient.Reply(200,"a".repeat(PluggyClient.MAX_BYTES+1))}){PluggyClient client=new PluggyClient((u,t)->reply,clock);try{client.fetch("https://finance.example",token,PluggyFixtures.DAY,PluggyFixtures.DAY);fail();}catch(IOException expected){}}
    }
    static PluggyClient.Snapshot parse(JSONObject root)throws Exception{return PluggyClient.parse(root.toString(),PluggyFixtures.DAY,PluggyFixtures.DAY);}
    static void bad(JSONObject root)throws Exception {try{parse(root);fail("Malformed snapshot accepted");}catch(IOException expected){}}
}
