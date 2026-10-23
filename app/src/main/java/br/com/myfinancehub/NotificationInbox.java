package br.com.myfinancehub;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only legacy notification archive. New notifications can never enter this store. */
public final class NotificationInbox implements AutoCloseable {
    static final String DATABASE_NAME = "myfinancehub-notifications.db";
    private final Helper helper;

    public NotificationInbox(Context context) { helper = new Helper(context.getApplicationContext()); }

    // Historical rows are no longer expired by a clock when the archive is opened.
    NotificationInbox(Context context, Clock ignored) { this(context); }

    private SQLiteDatabase db() { return helper.getWritableDatabase(); }

    /** A legacy enabled flag or Android permission cannot reactivate this retired source. */
    public synchronized boolean isEnabled() { return false; }

    /** Kept for upgrade compatibility; even an old caller asking to enable capture disables it. */
    public synchronized void setEnabled(boolean ignored) {
        ContentValues values = new ContentValues();
        values.put("name", "enabled"); values.put("value", "0");
        db().insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized Set<String> allowedPackages() {
        Set<String> result = new HashSet<>();
        try (Cursor c = db().query("allowed_packages", new String[]{"package_name"}, null, null, null, null, "package_name")) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return Collections.unmodifiableSet(result);
    }

    /** Preserves the legacy selection API without permitting notification collection. */
    public synchronized void setAllowedPackages(Set<String> packages) {
        if (packages == null) throw new IllegalArgumentException("Selecione os aplicativos permitidos.");
        for (String name : packages) {
            if (!validPackage(name)) throw new IllegalArgumentException("Nome de aplicativo inválido.");
        }
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            database.delete("allowed_packages", null, null);
            for (String name : packages) {
                ContentValues values = new ContentValues(); values.put("package_name", name);
                database.insertOrThrow("allowed_packages", null, values);
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    public synchronized List<Candidate> pending() {
        List<Candidate> result = new ArrayList<>();
        try (Cursor c = db().query("candidates", null, "pending=1", null, null, null, "posted_at DESC, _id DESC")) {
            while (c.moveToNext()) result.add(new Candidate(c));
        }
        return result;
    }

    public synchronized int pendingCount() {
        try (Cursor c = db().rawQuery("SELECT COUNT(*) FROM candidates WHERE pending=1", null)) {
            c.moveToFirst(); return c.getInt(0);
        }
    }

    /** Intentionally inert, including when old settings still say capture was enabled. */
    public synchronized boolean enqueue(String packageName, String appLabel, String notificationKey,
            long postedAt, NotificationParser.Suggestion suggestion) { return false; }

    /** Explicit legacy removal keeps its origin marker; simply viewing never changes rows. */
    public synchronized void dismiss(long id) {
        db().update("candidates", reviewedValues(), "_id=?", new String[]{Long.toString(id)});
    }

    public synchronized String sourceKey(long id) {
        try (Cursor c = db().query("candidates", new String[]{"source_key"}, "_id=?",
                new String[]{Long.toString(id)}, null, null, null)) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    /** Compatibility with explicit removal; no new notification can be captured afterwards. */
    public synchronized void clearPending() { db().update("candidates", reviewedValues(), "pending=1", null); }
    public synchronized void clearHistory() { db().delete("candidates", null, null); }
    public synchronized void clear() { clearHistory(); }

    @Override public synchronized void close() { helper.close(); }

    private static ContentValues reviewedValues() {
        ContentValues values = new ContentValues();
        values.put("pending", 0);
        for (String field : new String[]{"package_name", "app_label", "description", "date", "type", "method", "last_four", "review_note"})
            values.putNull(field);
        values.putNull("amount_cents"); values.putNull("posted_at");
        return values;
    }

    private static boolean validPackage(String name) {
        return name != null && name.length() <= 255 && name.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    public static final class Candidate {
        public final long id, postedAt, amountCents;
        public final String packageName, appLabel, description, date, type, method, lastFour, reviewNote, sourceKey;
        private Candidate(Cursor c) {
            id = c.getLong(c.getColumnIndexOrThrow("_id"));
            postedAt = c.getLong(c.getColumnIndexOrThrow("posted_at"));
            amountCents = c.getLong(c.getColumnIndexOrThrow("amount_cents"));
            packageName = text(c, "package_name"); appLabel = text(c, "app_label");
            description = text(c, "description"); date = text(c, "date"); type = text(c, "type");
            method = text(c, "method"); lastFour = text(c, "last_four");
            reviewNote = text(c, "review_note"); sourceKey = text(c, "source_key");
        }
        private static String text(Cursor c, String name) { return c.getString(c.getColumnIndexOrThrow(name)); }
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context, DATABASE_NAME, null, 1); }
        @Override public void onCreate(SQLiteDatabase database) {
            database.execSQL("CREATE TABLE settings (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
            database.execSQL("CREATE TABLE allowed_packages (package_name TEXT PRIMARY KEY)");
            database.execSQL("CREATE TABLE candidates (_id INTEGER PRIMARY KEY AUTOINCREMENT, source_key TEXT NOT NULL UNIQUE, captured_at INTEGER NOT NULL, pending INTEGER NOT NULL CHECK(pending IN (0,1)), package_name TEXT, app_label TEXT, posted_at INTEGER, description TEXT, date TEXT, type TEXT, method TEXT, last_four TEXT, review_note TEXT, amount_cents INTEGER)");
            database.execSQL("CREATE INDEX candidates_pending ON candidates(pending,posted_at)");
        }
        @Override public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
            throw new IllegalStateException("Versão de armazenamento de notificações não suportada.");
        }
    }
}
