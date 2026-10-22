package br.com.myfinancehub;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Local, optional review queue. Kept separate from financial records and backups. */
public final class NotificationInbox implements AutoCloseable {
    static final String DATABASE_NAME = "myfinancehub-notifications.db";
    private static final long PENDING_AGE = TimeUnit.DAYS.toMillis(30);
    private static final long DEDUP_AGE = TimeUnit.DAYS.toMillis(90);
    private static final int MAX_PENDING = 100;
    private static final int MAX_DEDUP = 1000;
    private final Helper helper;
    private final Clock clock;

    public NotificationInbox(Context context) { this(context, Clock.systemUTC()); }

    NotificationInbox(Context context, Clock clock) {
        helper = new Helper(context.getApplicationContext());
        this.clock = clock;
    }

    private SQLiteDatabase db() { return helper.getWritableDatabase(); }

    public synchronized boolean isEnabled() {
        try (Cursor c = db().query("settings", new String[]{"value"}, "name='enabled'", null, null, null, null)) {
            return c.moveToFirst() && "1".equals(c.getString(0));
        }
    }

    public synchronized void setEnabled(boolean enabled) {
        ContentValues values = new ContentValues();
        values.put("name", "enabled"); values.put("value", enabled ? "1" : "0");
        db().insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized Set<String> allowedPackages() {
        Set<String> result = new HashSet<>();
        try (Cursor c = db().query("allowed_packages", new String[]{"package_name"}, null, null, null, null, "package_name")) {
            while (c.moveToNext()) result.add(c.getString(0));
        }
        return Collections.unmodifiableSet(result);
    }

    /** Atomic replacement; invalid names leave the existing selection intact. */
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
        SQLiteDatabase database = db();
        cleanup(database, clock.millis());
        List<Candidate> result = new ArrayList<>();
        try (Cursor c = database.query("candidates", null, "pending=1", null, null, null, "posted_at DESC, _id DESC")) {
            while (c.moveToNext()) result.add(new Candidate(c));
        }
        return result;
    }

    public synchronized int pendingCount() {
        SQLiteDatabase database = db();
        cleanup(database, clock.millis());
        try (Cursor c = database.rawQuery("SELECT COUNT(*) FROM candidates WHERE pending=1", null)) {
            c.moveToFirst(); return c.getInt(0);
        }
    }

    /** No raw title, notification body, account number or card credentials are persisted. */
    public synchronized boolean enqueue(String packageName, String appLabel, String notificationKey,
            long postedAt, NotificationParser.Suggestion suggestion) {
        if (!isEnabled() || !allowedPackages().contains(packageName) || suggestion == null) return false;
        if (notificationKey == null || notificationKey.isEmpty() || notificationKey.length() > 4096) return false;
        long now = clock.millis();
        if (postedAt <= 0 || postedAt < now - PENDING_AGE || !validSuggestion(suggestion)) return false;
        String sourceKey = transactionKey(packageName, notificationKey, postedAt, suggestion);
        SQLiteDatabase database = db();
        database.beginTransaction();
        try {
            cleanup(database, now);
            ContentValues values = new ContentValues();
            values.put("source_key", sourceKey); values.put("captured_at", now);
            values.put("pending", 1); values.put("posted_at", postedAt);
            values.put("package_name", packageName); values.put("app_label", safeLabel(appLabel, packageName));
            values.put("description", suggestion.description); values.put("date", suggestion.date);
            values.put("type", suggestion.type); values.put("method", suggestion.method);
            values.put("last_four", suggestion.lastFour); values.put("review_note", suggestion.reviewNote);
            values.put("amount_cents", suggestion.amountCents);
            boolean inserted = database.insertWithOnConflict("candidates", null, values,
                    SQLiteDatabase.CONFLICT_IGNORE) != -1;
            cleanup(database, now);
            database.setTransactionSuccessful();
            return inserted;
        } finally { database.endTransaction(); }
    }

    /** Used after either confirmation or rejection; keeps only the dedup marker. */
    public synchronized void dismiss(long id) {
        db().update("candidates", reviewedValues(), "_id=?", new String[]{Long.toString(id)});
    }

    public synchronized String sourceKey(long id) {
        try (Cursor c = db().query("candidates", new String[]{"source_key"}, "_id=?",
                new String[]{Long.toString(id)}, null, null, null)) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    /** Discards pending suggestions while retaining their dedup markers. */
    public synchronized void clearPending() { db().update("candidates", reviewedValues(), "pending=1", null); }

    /** Clears suggestions and dedup history, preserving enabled status and app selection. */
    public synchronized void clearHistory() { db().delete("candidates", null, null); }
    public synchronized void clear() { clearHistory(); }

    @Override public synchronized void close() { helper.close(); }

    private static void cleanup(SQLiteDatabase database, long now) {
        database.update("candidates", reviewedValues(), "pending=1 AND (posted_at<? OR captured_at<?)",
                new String[]{Long.toString(now - PENDING_AGE), Long.toString(now - PENDING_AGE)});
        database.execSQL("UPDATE candidates SET " + reviewedSql()
                + " WHERE _id IN (SELECT _id FROM candidates WHERE pending=1 ORDER BY posted_at DESC, _id DESC LIMIT -1 OFFSET " + MAX_PENDING + ")");
        database.delete("candidates", "pending=0 AND captured_at<?", new String[]{Long.toString(now - DEDUP_AGE)});
        // Pending rows survive history cleanup, even when their posted/capture clocks differ.
        database.execSQL("DELETE FROM candidates WHERE pending=0 AND _id NOT IN "
                + "(SELECT _id FROM candidates ORDER BY pending DESC, captured_at DESC, _id DESC LIMIT " + MAX_DEDUP + ")");
    }

    private static ContentValues reviewedValues() {
        ContentValues values = new ContentValues();
        values.put("pending", 0);
        for (String field : new String[]{"package_name", "app_label", "description", "date", "type", "method", "last_four", "review_note"})
            values.putNull(field);
        values.putNull("amount_cents"); values.putNull("posted_at");
        return values;
    }

    private static String reviewedSql() {
        return "pending=0,package_name=NULL,app_label=NULL,description=NULL,date=NULL,type=NULL,method=NULL,last_four=NULL,review_note=NULL,amount_cents=NULL,posted_at=NULL";
    }

    private static boolean validSuggestion(NotificationParser.Suggestion value) {
        if (value.amountCents <= 0 || !bounded(value.description, 500, false)
                || !bounded(value.date, 10, false) || !bounded(value.reviewNote, 500, true)
                || value.lastFour == null || !value.lastFour.matches("(?:[0-9]{4})?")) return false;
        if (!("income".equals(value.type) || "expense".equals(value.type))) return false;
        if (!("other".equals(value.method) || "pix".equals(value.method)
                || "credit".equals(value.method) || "debit".equals(value.method)
                || "transfer".equals(value.method) || "cash".equals(value.method))) return false;
        try { return LocalDate.parse(value.date).toString().equals(value.date); }
        catch (DateTimeParseException invalid) { return false; }
    }

    private static boolean bounded(String value, int max, boolean optional) {
        return value != null && (optional || !value.trim().isEmpty()) && value.length() <= max && value.indexOf('\0') < 0;
    }

    private static boolean validPackage(String name) {
        return name != null && name.length() <= 255 && name.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    private static String safeLabel(String label, String fallback) {
        if (label == null || label.trim().isEmpty() || label.length() > 120 || label.indexOf('\0') >= 0) return fallback;
        return label.trim();
    }

    private static String transactionKey(String packageName, String notificationKey, long postedAt,
            NotificationParser.Suggestion value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // An Android notification key may be reused for separate payments. Only the
            // same post time and financial fields identify a redelivery of one event.
            // Length-delimited pieces prevent collisions caused by embedded separators.
            for (String part : new String[]{packageName, notificationKey, Long.toString(postedAt),
                    value.date, Long.toString(value.amountCents), value.type, value.description,
                    value.method, value.lastFour}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':'); digest.update(bytes);
            }
            StringBuilder result = new StringBuilder("notification:");
            for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
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
