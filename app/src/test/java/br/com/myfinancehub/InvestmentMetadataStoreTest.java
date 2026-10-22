package br.com.myfinancehub;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Collections;
import static org.junit.Assert.*;

/** Investment organization must preserve money, source reports and historical quote metadata. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public final class InvestmentMetadataStoreTest {
    private static final String QUOTE_AT="2026-10-20T12:00:00Z";
    private static final String HASH="a".repeat(64);
    private Context context;
    private FinanceStore store;

    @Before public void before(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("myfinancehub.db");store=new FinanceStore(context);}
    @After public void after(){store.close();context.deleteDatabase("myfinancehub.db");}

    @Test public void newMetadataRoundTripsInDatabaseAndBackupWithoutChangingMoneyOrB3()throws Exception {
        seedLedgerAndB3();Models.Investment investment=holding();investment.liquidity="at_maturity";investment.maturityDate="2030-12-31";store.saveInvestment(investment);
        Models.Investment saved=first();assertEquals("at_maturity",saved.liquidity);assertEquals("2030-12-31",saved.maturityDate);assertEquals(600,saved.investedCents);assertEquals(700,saved.currentCents);
        String backup=store.exportBackup();assertEquals(4,new JSONObject(backup).getInt("version"));
        store.deleteInvestment(investment.id);store.restoreBackup(backup);assertEquals(backup,store.exportBackup());assertEquals(investment.id,first().id);
        assertEquals(-1234,store.totalBalance());assertEquals(123,store.b3Snapshot().totalCents());
    }

    @Test public void quoteUpdateKeepsLiquidityMaturityKindAndAppliedValue()throws Exception {
        seedLedgerAndB3();Models.Investment investment=holding();investment.liquidity="daily";investment.maturityDate="2031-01-15";investment.autoQuote=true;store.saveInvestment(investment);
        String b3=new JSONObject(store.exportBackup()).getJSONObject("b3").toString();
        assertTrue(store.applyInvestmentQuote(investment.id,investment.ticker,investment.quantity,"10.5",QUOTE_AT,"brapi"));
        Models.Investment updated=first();assertEquals(2100,updated.currentCents);assertEquals(600,updated.investedCents);assertEquals("Ações",updated.kind);assertEquals("daily",updated.liquidity);assertEquals("2031-01-15",updated.maturityDate);assertEquals("10.5",updated.quotePrice);assertEquals(QUOTE_AT,updated.quoteAt);
        assertEquals(-1234,store.totalBalance());assertEquals(b3,new JSONObject(store.exportBackup()).getJSONObject("b3").toString());
    }

    @Test public void invalidMetadataDoesNotModifyExistingInvestment()throws Exception {
        Models.Investment investment=holding();String before=store.exportBackup();
        for(String liquidity:new String[]{"automatic","",null}){
            Models.Investment edited=first();edited.liquidity=liquidity;invalid(()->store.saveInvestment(edited));assertEquals(before,store.exportBackup());
        }
        for(String date:new String[]{"2026-02-30","2026-1-01","2026-01-01T00:00:00Z","0000-01-01"}){
            Models.Investment edited=first();edited.maturityDate=date;invalid(()->store.saveInvestment(edited));assertEquals(before,store.exportBackup());
        }
        Models.Investment matured=first();matured.maturityDate="2020-01-01";store.saveInvestment(matured);assertEquals("2020-01-01",first().maturityDate);
        Models.Investment cleared=first();cleared.maturityDate="";store.saveInvestment(cleared);assertEquals("",first().maturityDate);assertEquals(investment.currentCents,first().currentCents);
    }

    @Test public void versionThreeMigrationIsAdditiveAndPreservesEveryExistingExportedValue()throws Exception {
        seedLedgerAndB3();Models.Investment investment=holding();investment.autoQuote=true;store.saveInvestment(investment);
        assertTrue(store.applyInvestmentQuote(investment.id,investment.ticker,investment.quantity,"10.5",QUOTE_AT,"brapi"));String before=store.exportBackup();store.close();
        try(SQLiteDatabase legacy=context.openOrCreateDatabase("myfinancehub.db",Context.MODE_PRIVATE,null)){
            legacy.execSQL("CREATE TABLE investments_legacy (_id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,ticker TEXT NOT NULL,kind TEXT NOT NULL,invested_cents INTEGER NOT NULL CHECK(invested_cents>=0),current_cents INTEGER NOT NULL CHECK(current_cents>=0),quantity TEXT NOT NULL DEFAULT '',auto_quote INTEGER NOT NULL DEFAULT 0 CHECK(auto_quote IN (0,1)),quote_price TEXT NOT NULL DEFAULT '',quote_at TEXT NOT NULL DEFAULT '',quote_source TEXT NOT NULL DEFAULT '')");
            legacy.execSQL("INSERT INTO investments_legacy SELECT _id,name,ticker,kind,invested_cents,current_cents,quantity,auto_quote,quote_price,quote_at,quote_source FROM investments");
            legacy.execSQL("DROP TABLE investments");legacy.execSQL("ALTER TABLE investments_legacy RENAME TO investments");legacy.setVersion(3);
        }
        store=new FinanceStore(context);assertEquals(before,store.exportBackup());assertEquals("unknown",first().liquidity);assertEquals("",first().maturityDate);assertEquals("10.5",first().quotePrice);assertEquals(QUOTE_AT,first().quoteAt);assertTrue(first().autoQuote);
        assertEquals(-1234,store.totalBalance());assertEquals(123,store.b3Snapshot().totalCents());
        try(SQLiteDatabase migrated=context.openOrCreateDatabase("myfinancehub.db",Context.MODE_PRIVATE,null)){assertEquals(4,migrated.getVersion());}
        Models.Investment next=new Models.Investment(0,"Nova posição","","",100,100);store.saveInvestment(next);assertTrue(next.id>investment.id);
    }

    @Test public void oldBackupsRestoreUnknownMetadataAndKeepVersionThreeQuotes()throws Exception {
        seedLedgerAndB3();Models.Investment investment=holding();investment.liquidity="over30";investment.maturityDate="2035-10-20";investment.autoQuote=true;store.saveInvestment(investment);store.applyInvestmentQuote(investment.id,investment.ticker,investment.quantity,"10.5",QUOTE_AT,"brapi");
        String original=store.exportBackup();
        for(int version:new int[]{1,2,3}){
            JSONObject legacy=new JSONObject(original);legacy.put("version",version);JSONObject row=legacy.getJSONArray("investments").getJSONObject(0);row.remove("liquidity");row.remove("maturityDate");
            if(version<3)for(String field:new String[]{"quantity","autoQuote","quotePrice","quoteAt","quoteSource"})row.remove(field);
            if(version==1)legacy.remove("b3");store.restoreBackup(legacy.toString());Models.Investment restored=first();
            assertEquals("unknown",restored.liquidity);assertEquals("",restored.maturityDate);assertEquals(600,restored.investedCents);assertEquals(2100,restored.currentCents);assertEquals(-1234,store.totalBalance());
            if(version==3){assertEquals("2",restored.quantity);assertEquals("10.5",restored.quotePrice);assertEquals(QUOTE_AT,restored.quoteAt);assertTrue(restored.autoQuote);}else{assertEquals("",restored.quantity);assertEquals("",restored.quoteAt);assertFalse(restored.autoQuote);}
            if(version==1)assertNull(store.b3Snapshot());else assertEquals(123,store.b3Snapshot().totalCents());
        }
    }

    @Test public void malformedVersionFourBackupLeavesAllCurrentRecordsUntouched()throws Exception {
        seedLedgerAndB3();Models.Investment investment=holding();investment.liquidity="up_to30";investment.maturityDate="2030-01-01";store.saveInvestment(investment);String before=store.exportBackup();
        for(Object liquidity:new Object[]{"immediate",42,JSONObject.NULL,false}){
            JSONObject invalid=new JSONObject(before);invalid.getJSONArray("investments").getJSONObject(0).put("liquidity",liquidity);invalid(()->store.restoreBackup(invalid.toString()));assertEquals(before,store.exportBackup());
        }
        for(Object date:new Object[]{"2027-02-29",42,JSONObject.NULL,false}){
            JSONObject invalid=new JSONObject(before);invalid.getJSONArray("investments").getJSONObject(0).put("maturityDate",date);invalid(()->store.restoreBackup(invalid.toString()));assertEquals(before,store.exportBackup());
        }
    }

    private Models.Investment holding(){Models.Investment investment=new Models.Investment(0,"Posição fictícia","DEMO3","Ações",600,700);investment.quantity="2";store.saveInvestment(investment);return investment;}
    private Models.Investment first(){return store.investments().get(0);}
    private void seedLedgerAndB3(){long account=store.accounts().get(0).id;store.saveEntry(new Models.Entry(0,account,0,0,"Despesa preservada","2026-01-01","expense","pix","fixture-expense",1234));store.replaceB3Snapshot(Collections.singletonList(new B3Portfolio.Position("Posição B3","Corretora","","B3XX3","","Ações","2",123L)),"2026-01-01",HASH);}
    private static void invalid(Runnable operation){try{operation.run();fail("Invalid investment metadata accepted");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
}
