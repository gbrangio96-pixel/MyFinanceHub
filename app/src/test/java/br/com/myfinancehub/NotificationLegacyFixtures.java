package br.com.myfinancehub;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Seeds pre-upgrade archive rows directly; production capture remains permanently disabled. */
final class NotificationLegacyFixtures {
    static final String APP="com.example.finance", LABEL="Banco de demonstração";
    private NotificationLegacyFixtures(){}

    static void legacyEnabled(Context context,boolean enabled){
        initialize(context);
        try(SQLiteDatabase database=context.openOrCreateDatabase(NotificationInbox.DATABASE_NAME,0,null)){
            ContentValues setting=new ContentValues();setting.put("name","enabled");setting.put("value",enabled?"1":"0");
            database.insertWithOnConflict("settings",null,setting,SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    static NotificationInbox.Candidate seed(Context context,String key,long postedAt,NotificationParser.Suggestion suggestion){
        initialize(context);
        String source=source(key,postedAt,suggestion);
        try(SQLiteDatabase database=context.openOrCreateDatabase(NotificationInbox.DATABASE_NAME,0,null)){
            ContentValues values=new ContentValues();values.put("source_key",source);values.put("captured_at",postedAt);
            values.put("pending",1);values.put("package_name",APP);values.put("app_label",LABEL);values.put("posted_at",postedAt);
            values.put("description",suggestion.description);values.put("date",suggestion.date);values.put("type",suggestion.type);
            values.put("method",suggestion.method);values.put("last_four",suggestion.lastFour);values.put("review_note",suggestion.reviewNote);
            values.put("amount_cents",suggestion.amountCents);
            database.insertWithOnConflict("candidates",null,values,SQLiteDatabase.CONFLICT_IGNORE);
        }
        try(NotificationInbox inbox=new NotificationInbox(context)){
            for(NotificationInbox.Candidate candidate:inbox.pending())if(source.equals(candidate.sourceKey))return candidate;
        }
        throw new AssertionError("Legacy pending fixture is missing");
    }

    private static void initialize(Context context){try(NotificationInbox inbox=new NotificationInbox(context)){inbox.pendingCount();}}
    private static String source(String key,long postedAt,NotificationParser.Suggestion suggestion){
        try{
            byte[] hash=MessageDigest.getInstance("SHA-256").digest((key+"\u0000"+postedAt+"\u0000"+suggestion.description+"\u0000"+suggestion.amountCents).getBytes(StandardCharsets.UTF_8));
            StringBuilder value=new StringBuilder("notification:");for(byte b:hash)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
