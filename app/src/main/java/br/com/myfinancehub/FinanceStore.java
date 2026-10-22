package br.com.myfinancehub;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import br.com.myfinancehub.Models.Account;
import br.com.myfinancehub.Models.Card;
import br.com.myfinancehub.Models.Category;
import br.com.myfinancehub.Models.Entry;
import br.com.myfinancehub.Models.Investment;

/**
 * Local SQLite repository. All public operations are synchronized and use exact cents.
 * The database is in Android private app storage; no service or credential is needed.
 * Validation errors use IllegalArgumentException with a message suitable for a form.
 */
public final class FinanceStore implements AutoCloseable {
    private final Helper helper;
    private static final int BACKUP_VERSION = 4;
    private static final int MAX_BACKUP_BYTES = 8 * 1024 * 1024;
    private static final BigInteger MAX_CENTS = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger MIN_CENTS = BigInteger.valueOf(Long.MIN_VALUE);

    public FinanceStore(Context context) {
        helper = new Helper(context.getApplicationContext());
    }

    private SQLiteDatabase db() { return helper.getWritableDatabase(); }

    /** Categories are sorted by name. Deleting a category preserves its entries. */
    public synchronized List<Category> categories() {
        List<Category> result = new ArrayList<>();
        try (Cursor c = db().query("categories", null, null, null, null, null, "name COLLATE NOCASE, _id")) {
            while (c.moveToNext()) result.add(new Category(number(c, "_id"), string(c, "name"), string(c, "type")));
        }
        return result;
    }

    /** Inserts id=0 or updates an existing category; names must be unique. */
    public synchronized long saveCategory(Category category) {
        ContentValues values = categoryValues(category);
        validateUniqueName("categories", values.getAsString("name"), category.id);
        if (category.id != 0 && !"both".equals(category.type)
                && exists("entries", "category_id=? AND type<>?", category.id, category.type))
            throw invalid("Esta categoria possui lançamentos de outro tipo. Selecione Receitas e despesas.");
        category.id = save("categories", category.id, values);
        return category.id;
    }

    public synchronized void deleteCategory(long id) { delete("categories", id); }

    /** Accounts contain opening balances only; current balances also include non-credit entries. */
    public synchronized List<Account> accounts() {
        List<Account> result = new ArrayList<>();
        try (Cursor c = db().query("accounts", null, null, null, null, null, "name COLLATE NOCASE, _id")) {
            while (c.moveToNext()) result.add(new Account(number(c, "_id"), string(c, "name"), number(c, "opening_cents")));
        }
        return result;
    }

    public synchronized long saveAccount(Account account) {
        ContentValues values = accountValues(account);
        validateUniqueName("accounts", values.getAsString("name"), account.id);
        account.id = save("accounts", account.id, values);
        return account.id;
    }

    /** Refuses to delete an account with entries. Move or delete those entries first. */
    public synchronized void deleteAccount(long id) {
        if (exists("entries", "account_id=?", id))
            throw invalid("A conta possui lançamentos. Mova ou exclua os lançamentos antes de excluir a conta.");
        delete("accounts", id);
    }

    /** Returns opening balance plus income minus expenses, excluding all credit-card entries. */
    public synchronized long accountBalance(long accountId) {
        BigInteger amount;
        try (Cursor c = db().query("accounts", new String[]{"opening_cents"}, "_id=?",
                args(accountId), null, null, null)) {
            if (!c.moveToFirst()) throw invalid("Conta inexistente.");
            amount = BigInteger.valueOf(c.getLong(0));
        }
        try (Cursor c = db().query("entries", new String[]{"amount_cents", "type"},
                "account_id=? AND method<>'credit'", args(accountId), null, null, null)) {
            while (c.moveToNext()) amount = amount.add(BigInteger.valueOf(
                    "income".equals(c.getString(1)) ? c.getLong(0) : -c.getLong(0)));
        }
        return checkedCents(amount);
    }

    /** Sum of account balances; does not count investments or unpaid card charges as cash. */
    public synchronized long totalBalance() {
        BigInteger total = BigInteger.ZERO;
        for (Account account : accounts()) total = total.add(BigInteger.valueOf(accountBalance(account.id)));
        return checkedCents(total);
    }

    public synchronized List<Card> cards() {
        List<Card> result = new ArrayList<>();
        try (Cursor c = db().query("cards", null, null, null, null, null, "name COLLATE NOCASE, _id")) {
            while (c.moveToNext()) result.add(new Card(number(c, "_id"), string(c, "name"),
                    string(c, "last_four"), (int) number(c, "closing_day"), (int) number(c, "due_day")));
        }
        return result;
    }

    /** Stores only a label and optional last four digits, never card credentials. Days are 1..31. */
    public synchronized long saveCard(Card card) {
        ContentValues values = cardValues(card);
        validateUniqueName("cards", values.getAsString("name"), card.id);
        card.id = save("cards", card.id, values);
        return card.id;
    }

    /** Deletion clears card links but preserves entry amounts, methods, and cash treatment. */
    public synchronized void deleteCard(long id) { delete("cards", id); }

    /** Newest calendar dates first; all amounts are positive, with income/expense carrying direction. */
    public synchronized List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        try (Cursor c = db().query("entries", null, null, null, null, null, "date DESC, _id DESC")) {
            while (c.moveToNext()) result.add(new Entry(number(c, "_id"), number(c, "account_id"),
                    number(c, "category_id"), number(c, "card_id"), string(c, "description"),
                    string(c, "date"), string(c, "type"), string(c, "method"),
                    string(c, "source_key"), number(c, "amount_cents")));
        }
        return result;
    }

    /** Inserts or edits an entry after checking its date, references, category type and amount. */
    public synchronized long saveEntry(Entry entry) {
        ContentValues values = entryValues(entry);
        validateEntryReferences(entry, null, null, null);
        entry.id = save("entries", entry.id, values);
        return entry.id;
    }

    /**
     * Records a reviewed notification once across every account. Only its stable notification
     * identifier is compared; matching dates, descriptions or amounts never link other entries.
     * Validation, the duplicate check and insertion share a transaction, including when two
     * FinanceStore instances are open. The caller's entry receives an ID only after commit.
     */
    public synchronized long saveReviewedNotification(Entry entry) {
        if (entry == null || entry.id != 0 || !isNotificationSource(entry.sourceKey))
            throw invalid("Lançamento de notificação inválido. Atualize a lista.");
        ContentValues values = entryValues(entry);
        SQLiteDatabase db = db();
        db.beginTransaction();
        long id;
        try {
            if (hasNotificationSource(entry.sourceKey))
                throw invalid("Esta notificação já foi registrada. Atualize a lista.");
            validateEntryReferences(entry, null, null, null);
            id = db.insertOrThrow("entries", null, values);
            validateFinancialRanges();
            db.setTransactionSuccessful();
        } catch (SQLiteConstraintException error) {
            throw invalid("Nome ou identificador já cadastrado, ou vínculo inválido.");
        } finally { db.endTransaction(); }
        entry.id = id;
        return id;
    }

    /** Returns false for invalid keys; searches every account, including restored entries. */
    public synchronized boolean hasNotificationSource(String sourceKey) {
        return isNotificationSource(sourceKey)
                && exists("entries", "source_key COLLATE NOCASE=?", sourceKey);
    }

    public synchronized void deleteEntry(long id) { delete("entries", id); }

    public synchronized List<Investment> investments() {
        List<Investment> result = new ArrayList<>();
        try (Cursor c = db().query("investments", null, null, null, null, null, "name COLLATE NOCASE, _id")) {
            while (c.moveToNext()) result.add(investment(c));
        }
        return result;
    }

    /** Stores invested/current amounts and optional exact quantity/quote metadata. */
    public synchronized long saveInvestment(Investment investment) {
        ContentValues values = investmentValues(investment);
        investment.id = save("investments", investment.id, values);
        return investment.id;
    }

    public synchronized void deleteInvestment(long id) { delete("investments", id); }

    /**
     * Applies a BRL unit price to the same investment and quantity the request started with.
     * Exact decimal multiplication is rounded once, at the final monetary amount. Stale
     * requests never overwrite a newer price or a holding edited/deleted during the request.
     * The invested amount, ledger and imported B3 report are never changed by a quote.
     */
    public synchronized boolean applyInvestmentQuote(long investmentId, String expectedTicker,
            String expectedQuantity, String price, String quotedAt, String source) {
        return applyInvestmentQuote(investmentId, expectedTicker, expectedQuantity,
                null, null, price, quotedAt, source);
    }

    /**
     * Refresh requests also compare the original valuation inside the database transaction.
     * This check protects edits committed through a different FinanceStore instance while
     * the network request was running; cost/name changes are intentionally preserved.
     */
    public synchronized boolean applyInvestmentQuote(long investmentId, String expectedTicker,
            String expectedQuantity, long expectedCurrentCents, String expectedQuoteAt,
            String price, String quotedAt, String source) {
        if (expectedCurrentCents < 0) throw invalid("Valor anterior do investimento inválido.");
        return applyInvestmentQuote(investmentId, expectedTicker, expectedQuantity,
                Long.valueOf(expectedCurrentCents), text(expectedQuoteAt, "Data anterior da cotação", 40, true),
                price, quotedAt, source);
    }

    private boolean applyInvestmentQuote(long investmentId, String expectedTicker,
            String expectedQuantity, Long expectedCurrentCents, String expectedQuoteAt,
            String price, String quotedAt, String source) {
        positiveId(investmentId);
        String ticker = quoteTicker(expectedTicker);
        String quantity = decimalQuantity(expectedQuantity);
        if (quantity.isEmpty() || new BigDecimal(quantity).signum() <= 0)
            throw invalid("Informe uma quantidade maior que zero para atualizar a cotação.");
        String unitPrice = decimalPrice(price);
        String at = quoteTime(quotedAt);
        if (!"brapi".equals(source)) throw invalid("Fonte de cotação inválida.");
        long currentCents = quoteValue(quantity, unitPrice);
        if (expectedCurrentCents != null && Thread.currentThread().isInterrupted()) return false;
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            Investment current;
            try (Cursor c = db.query("investments", null, "_id=?", args(investmentId), null, null, null)) {
                if (!c.moveToFirst()) { db.setTransactionSuccessful(); return false; }
                current = investment(c);
            }
            if (!ticker.equals(current.ticker.trim().toUpperCase(Locale.ROOT))
                    || !quantity.equals(current.quantity)
                    || expectedCurrentCents != null && (!current.autoQuote || current.currentCents != expectedCurrentCents
                    || !current.quoteAt.equals(expectedQuoteAt))) {
                db.setTransactionSuccessful(); return false;
            }
            if (!current.quoteAt.isEmpty() && !Instant.parse(at).isAfter(Instant.parse(current.quoteAt))) {
                db.setTransactionSuccessful(); return false;
            }
            if (expectedCurrentCents != null && Thread.currentThread().isInterrupted()) {
                db.setTransactionSuccessful(); return false;
            }
            current.currentCents = currentCents;
            current.quotePrice = unitPrice; current.quoteAt = at; current.quoteSource = source;
            db.update("investments", investmentValues(current), "_id=?", args(investmentId));
            validateFinancialRanges();
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    private static Investment investment(Cursor c) {
        Investment investment = new Investment(number(c, "_id"), string(c, "name"),
                string(c, "ticker"), string(c, "kind"), number(c, "invested_cents"), number(c, "current_cents"));
        investment.quantity = string(c, "quantity"); investment.autoQuote = number(c, "auto_quote") == 1;
        investment.quotePrice = string(c, "quote_price"); investment.quoteAt = string(c, "quote_at");
        investment.quoteSource = string(c, "quote_source");
        investment.liquidity = string(c, "liquidity"); investment.maturityDate = string(c, "maturity_date");
        return investment;
    }

    /** Last imported B3 custody report. Its values are separate from all manual investments. */
    public synchronized B3Portfolio.Snapshot b3Snapshot() {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try (Cursor metadata = db.query("b3_snapshots", null, "_id=1", null, null, null, null)) {
            if (!metadata.moveToFirst()) {
                db.setTransactionSuccessful();
                return null;
            }
            List<B3Portfolio.Position> positions = new ArrayList<>();
            try (Cursor c = db.query("b3_positions", null, "snapshot_id=1", null, null, null, "_id")) {
                while (c.moveToNext()) {
                    int valuation = c.getColumnIndexOrThrow("current_cents");
                    positions.add(new B3Portfolio.Position(string(c, "product"), string(c, "institution"),
                            string(c, "account"), string(c, "ticker"), string(c, "isin"), string(c, "kind"),
                            string(c, "quantity"), c.isNull(valuation) ? null : c.getLong(valuation)));
                }
            }
            B3Portfolio.Snapshot snapshot = new B3Portfolio.Snapshot(string(metadata, "as_of_date"), string(metadata, "imported_at"),
                    string(metadata, "source_hash"), positions);
            db.setTransactionSuccessful();
            return snapshot;
        } finally { db.endTransaction(); }
    }

    /** Replaces only the B3 report, atomically. Reimporting never appends positions or edits cost. */
    public synchronized void replaceB3Snapshot(List<B3Portfolio.Position> positions, String asOfDate, String sourceHash) {
        B3Portfolio.Snapshot snapshot = new B3Portfolio.Snapshot(asOfDate, Instant.now().toString(), sourceHash, positions);
        B3Portfolio.requireNotFutureDate(snapshot.asOfDate);
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            writeB3Snapshot(db, snapshot);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Removes the imported report without changing manually entered investments or cash. */
    public synchronized void clearB3Snapshot() {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            writeB3Snapshot(db, null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /**
     * All-or-nothing statement import. Existing nonempty sourceKey values are skipped within
     * their own account. The same source key may be used by another account. Entries without
     * a source key cannot be deduplicated. Returns the number of newly inserted rows.
     */
    public synchronized int importEntries(List<Entry> entries) {
        if (entries == null) throw invalid("Importação vazia.");
        SQLiteDatabase db = db();
        db.beginTransaction();
        int inserted = 0;
        try {
            for (Entry entry : entries) {
                if (entry == null || entry.id != 0) throw invalid("Lançamento de importação inválido.");
                ContentValues values = entryValues(entry);
                validateEntryReferences(entry, null, null, null);
                String key = values.getAsString("source_key");
                if (key != null && exists("entries", "account_id=? AND source_key=?", entry.accountId, key))
                    continue;
                db.insertOrThrow("entries", null, values);
                inserted++;
            }
            validateFinancialRanges();
            db.setTransactionSuccessful();
            return inserted;
        } catch (SQLiteConstraintException error) {
            throw invalid("Não foi possível importar: vínculo ou identificador duplicado.");
        } finally { db.endTransaction(); }
    }

    /** Reviewed provider entries are globally deduplicated, even after remapping accounts. */
    public synchronized long saveReviewedPluggy(Entry entry) {
        if(entry==null||entry.id!=0||entry.sourceKey==null||!entry.sourceKey.matches("pluggy:[0-9a-f]{64}"))throw invalid("Origem Meu Pluggy inválida.");
        SQLiteDatabase database=db();database.beginTransaction();
        try {
            if(hasPluggySource(entry.sourceKey))throw invalid("Esta transação Meu Pluggy já foi salva.");
            ContentValues values=entryValues(entry);validateEntryReferences(entry,null,null,null);
            long id=database.insertOrThrow("entries",null,values);validateFinancialRanges();database.setTransactionSuccessful();return id;
        }finally{database.endTransaction();}
    }
    public synchronized boolean hasPluggySource(String sourceKey) {
        return sourceKey!=null&&sourceKey.matches("pluggy:[0-9a-f]{64}")&&exists("entries","source_key COLLATE NOCASE=?",sourceKey);
    }

    /** Complete versioned UTF-8 JSON backup. Contains sensitive financial data, without encryption. */
    public synchronized String exportBackup() {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            JSONObject root = new JSONObject();
            root.put("format", "MyFinanceHub"); root.put("version", BACKUP_VERSION);
            JSONArray categories = new JSONArray(), accounts = new JSONArray(), cards = new JSONArray();
            JSONArray entries = new JSONArray(), investments = new JSONArray();
            for (Category c : categories()) categories.put(new JSONObject().put("id", c.id).put("name", c.name).put("type", c.type));
            for (Account a : accounts()) accounts.put(new JSONObject().put("id", a.id).put("name", a.name).put("openingCents", a.openingCents));
            for (Card c : cards()) cards.put(new JSONObject().put("id", c.id).put("name", c.name).put("lastFour", c.lastFour)
                    .put("closingDay", c.closingDay).put("dueDay", c.dueDay));
            for (Entry e : entries()) entries.put(new JSONObject().put("id", e.id).put("accountId", e.accountId)
                    .put("categoryId", e.categoryId).put("cardId", e.cardId).put("description", e.description)
                    .put("date", e.date).put("type", e.type).put("method", e.method)
                    .put("sourceKey", e.sourceKey).put("amountCents", e.amountCents));
            for (Investment i : investments()) investments.put(new JSONObject().put("id", i.id).put("name", i.name)
                    .put("ticker", i.ticker).put("kind", i.kind).put("investedCents", i.investedCents).put("currentCents", i.currentCents)
                    .put("quantity", i.quantity).put("autoQuote", i.autoQuote).put("quotePrice", i.quotePrice)
                    .put("quoteAt", i.quoteAt).put("quoteSource", i.quoteSource)
                    .put("liquidity", i.liquidity).put("maturityDate", i.maturityDate));
            String backup = root.put("categories", categories).put("accounts", accounts).put("cards", cards)
                    .put("entries", entries).put("investments", investments)
                    .put("b3", b3Json(b3Snapshot())).toString(2);
            if (backup.getBytes(StandardCharsets.UTF_8).length > MAX_BACKUP_BYTES)
                throw invalid("O backup excede o limite de 8 MB. Exporte os lançamentos em CSV.");
            db.setTransactionSuccessful();
            return backup;
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        finally { db.endTransaction(); }
    }

    /**
     * Replaces all records only after validating the entire versioned backup. Every insertion
     * runs in one SQLite transaction, so malformed JSON, duplicate IDs or broken references
     * leave the previous database intact. The UI must ask before replacing existing data.
     */
    public synchronized void restoreBackup(String json) {
        if (json == null || json.length() > MAX_BACKUP_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BACKUP_BYTES)
            throw invalid("Backup inválido ou maior que o limite de 8 MB.");
        List<Category> categories = new ArrayList<>(); List<Account> accounts = new ArrayList<>();
        List<Card> cards = new ArrayList<>(); List<Entry> entries = new ArrayList<>();
        List<Investment> investments = new ArrayList<>();
        B3Portfolio.Snapshot b3 = null;
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0)
                throw invalid("Backup JSON inválido.");
            JSONObject root = (JSONObject) parsed;
            long backupVersion = jsonLong(root, "version");
            if (!"MyFinanceHub".equals(jsonString(root, "format")) || backupVersion < 1 || backupVersion > BACKUP_VERSION)
                throw invalid("Formato ou versão do backup não suportado.");
            JSONArray rows = root.getJSONArray("categories");
            for (int n = 0; n < rows.length(); n++) {
                JSONObject r = rows.getJSONObject(n);
                Category c = new Category(jsonLong(r, "id"), jsonString(r, "name"), jsonString(r, "type"));
                categoryValues(c); categories.add(c);
            }
            rows = root.getJSONArray("accounts");
            for (int n = 0; n < rows.length(); n++) {
                JSONObject r = rows.getJSONObject(n);
                Account a = new Account(jsonLong(r, "id"), jsonString(r, "name"), jsonLong(r, "openingCents"));
                accountValues(a); accounts.add(a);
            }
            rows = root.getJSONArray("cards");
            for (int n = 0; n < rows.length(); n++) {
                JSONObject r = rows.getJSONObject(n);
                long closing = jsonLong(r, "closingDay"), due = jsonLong(r, "dueDay");
                if (closing < 1 || closing > 31 || due < 1 || due > 31) throw invalid("Dia de cartão inválido.");
                Card c = new Card(jsonLong(r, "id"), jsonString(r, "name"), jsonString(r, "lastFour"), (int) closing, (int) due);
                cardValues(c); cards.add(c);
            }
            rows = root.getJSONArray("entries");
            for (int n = 0; n < rows.length(); n++) {
                JSONObject r = rows.getJSONObject(n);
                Entry e = new Entry(jsonLong(r, "id"), jsonLong(r, "accountId"), jsonLong(r, "categoryId"),
                        jsonLong(r, "cardId"), jsonString(r, "description"), jsonString(r, "date"),
                        jsonString(r, "type"), jsonString(r, "method"), jsonString(r, "sourceKey"), jsonLong(r, "amountCents"));
                entryValues(e); entries.add(e);
            }
            rows = root.getJSONArray("investments");
            for (int n = 0; n < rows.length(); n++) {
                JSONObject r = rows.getJSONObject(n);
                Investment i = new Investment(jsonLong(r, "id"), jsonString(r, "name"), jsonString(r, "ticker"),
                        jsonString(r, "kind"), jsonLong(r, "investedCents"), jsonLong(r, "currentCents"));
                if (backupVersion >= 3) {
                    i.quantity = r.has("quantity") ? jsonString(r, "quantity") : "";
                    i.autoQuote = r.has("autoQuote") && jsonBoolean(r, "autoQuote");
                    i.quotePrice = r.has("quotePrice") ? jsonString(r, "quotePrice") : "";
                    i.quoteAt = r.has("quoteAt") ? jsonString(r, "quoteAt") : "";
                    i.quoteSource = r.has("quoteSource") ? jsonString(r, "quoteSource") : "";
                }
                if (backupVersion >= 4) {
                    i.liquidity = jsonString(r, "liquidity"); i.maturityDate = jsonString(r, "maturityDate");
                }
                investmentValues(i); investments.add(i);
            }
            if (backupVersion >= 2) {
                Object report = root.get("b3");
                if (report != JSONObject.NULL) {
                    if (!(report instanceof JSONObject)) throw invalid("Relatório B3 inválido no backup.");
                    b3 = parseB3Json((JSONObject) report);
                }
            }
        } catch (JSONException | NullPointerException error) { throw invalid("Backup JSON inválido ou incompleto."); }

        Map<Long, Category> categoryIds = new HashMap<>(); Set<Long> accountIds = new HashSet<>(), cardIds = new HashSet<>();
        Set<Long> entryIds = new HashSet<>(), investmentIds = new HashSet<>();
        Set<String> categoryNames = new HashSet<>(), accountNames = new HashSet<>(), cardNames = new HashSet<>();
        Set<String> sourceKeys = new HashSet<>();
        for (Category c : categories) {
            positiveId(c.id); if (categoryIds.put(c.id, c) != null) throw invalid("Backup com IDs de categoria repetidos.");
            uniqueName(categoryNames, c.name);
        }
        for (Account a : accounts) { uniqueId(accountIds, a.id); uniqueName(accountNames, a.name); }
        for (Card c : cards) { uniqueId(cardIds, c.id); uniqueName(cardNames, c.name); }
        for (Entry e : entries) {
            uniqueId(entryIds, e.id); validateEntryReferences(e, accountIds, categoryIds, cardIds);
            String source = e.sourceKey.trim();
            if (!source.isEmpty() && !sourceKeys.add(e.accountId + ":" + source))
                throw invalid("Backup com lançamentos de importação duplicados.");
        }
        for (Investment i : investments) uniqueId(investmentIds, i.id);

        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            db.delete("entries", null, null); db.delete("investments", null, null); db.delete("cards", null, null);
            db.delete("categories", null, null); db.delete("accounts", null, null);
            for (Category c : categories) insertRestored("categories", c.id, categoryValues(c));
            for (Account a : accounts) insertRestored("accounts", a.id, accountValues(a));
            for (Card c : cards) insertRestored("cards", c.id, cardValues(c));
            for (Entry e : entries) insertRestored("entries", e.id, entryValues(e));
            for (Investment i : investments) insertRestored("investments", i.id, investmentValues(i));
            writeB3Snapshot(db, b3);
            validateFinancialRanges();
            db.setTransactionSuccessful();
        } catch (SQLiteConstraintException error) { throw invalid("Backup com registros duplicados ou vínculos inválidos."); }
        finally { db.endTransaction(); }
    }

    /**
     * Quoted semicolon CSV with a positive pt-BR amount and income/expense direction in type.
     * Formula-like text is prefixed with an apostrophe for safe spreadsheet opening. Use the
     * JSON backup to preserve every original character and all IDs without this presentation escape.
     */
    public synchronized String exportCsv() {
        Map<Long, String> accounts = new HashMap<>(), categories = new HashMap<>(), cards = new HashMap<>();
        for (Account a : accounts()) accounts.put(a.id, a.name);
        for (Category c : categories()) categories.put(c.id, c.name);
        for (Card c : cards()) cards.put(c.id, c.name);
        StringBuilder out = new StringBuilder("date;description;type;amount;account;category;method;card\r\n");
        for (Entry e : entries()) {
            String amount = BigDecimal.valueOf(e.amountCents, 2).toPlainString().replace('.', ',');
            out.append(csv(e.date)).append(';').append(csv(e.description)).append(';').append(csv(e.type)).append(';')
                    .append(csv(amount)).append(';').append(csv(accounts.get(e.accountId))).append(';')
                    .append(csv(categories.get(e.categoryId))).append(';').append(csv(e.method)).append(';')
                    .append(csv(cards.get(e.cardId))).append("\r\n");
        }
        return out.toString();
    }

    @Override public synchronized void close() { helper.close(); }

    private ContentValues categoryValues(Category category) {
        if (category == null) throw invalid("Categoria inválida.");
        nonnegativeId(category.id);
        String type = choice(category.type, "Tipo da categoria", "income", "expense", "both");
        ContentValues values = new ContentValues();
        values.put("name", text(category.name, "Nome da categoria", 80, false)); values.put("type", type);
        return values;
    }

    private ContentValues accountValues(Account account) {
        if (account == null) throw invalid("Conta inválida.");
        nonnegativeId(account.id);
        ContentValues values = new ContentValues();
        values.put("name", text(account.name, "Nome da conta", 80, false)); values.put("opening_cents", account.openingCents);
        return values;
    }

    private ContentValues cardValues(Card card) {
        if (card == null) throw invalid("Cartão inválido.");
        nonnegativeId(card.id);
        String last = text(card.lastFour, "Últimos quatro dígitos", 4, true);
        if (!last.isEmpty() && !last.matches("[0-9]{4}")) throw invalid("Informe apenas os quatro últimos dígitos do cartão.");
        if (card.closingDay < 1 || card.closingDay > 31 || card.dueDay < 1 || card.dueDay > 31)
            throw invalid("Fechamento e vencimento devem ser dias de 1 a 31.");
        ContentValues values = new ContentValues();
        values.put("name", text(card.name, "Nome do cartão", 80, false)); values.put("last_four", last);
        values.put("closing_day", card.closingDay); values.put("due_day", card.dueDay);
        return values;
    }

    private ContentValues entryValues(Entry entry) {
        if (entry == null) throw invalid("Lançamento inválido.");
        nonnegativeId(entry.id); positiveId(entry.accountId); nonnegativeId(entry.categoryId); nonnegativeId(entry.cardId);
        if (entry.amountCents <= 0) throw invalid("O valor do lançamento deve ser maior que zero.");
        String date = text(entry.date, "Data", 10, false);
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("Use uma data válida no formato AAAA-MM-DD.");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setLenient(false); format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            if (!format.format(format.parse(date)).equals(date)) throw invalid("Data inválida.");
        } catch (ParseException error) { throw invalid("Data inválida."); }
        ContentValues values = new ContentValues();
        values.put("account_id", entry.accountId);
        if (entry.categoryId == 0) values.putNull("category_id"); else values.put("category_id", entry.categoryId);
        if (entry.cardId == 0) values.putNull("card_id"); else values.put("card_id", entry.cardId);
        values.put("description", text(entry.description, "Descrição", 500, false)); values.put("date", date);
        values.put("type", choice(entry.type, "Tipo", "income", "expense"));
        values.put("method", choice(entry.method, "Forma de pagamento", "cash", "debit", "credit", "pix", "transfer", "card_payment", "other"));
        if ("card_payment".equals(entry.method) && !"expense".equals(entry.type))
            throw invalid("Pagamento de fatura deve ser uma saída.");
        String source = text(entry.sourceKey, "Identificador da importação", 512, true);
        if (source.isEmpty()) values.putNull("source_key"); else values.put("source_key", source);
        values.put("amount_cents", entry.amountCents);
        return values;
    }

    private ContentValues investmentValues(Investment investment) {
        if (investment == null) throw invalid("Investimento inválido.");
        nonnegativeId(investment.id);
        if (investment.investedCents < 0 || investment.currentCents < 0)
            throw invalid("Valores de investimento não podem ser negativos.");
        ContentValues values = new ContentValues();
        values.put("name", text(investment.name, "Nome do investimento", 100, false));
        values.put("ticker", text(investment.ticker, "Código", 30, true));
        values.put("kind", text(investment.kind, "Tipo de investimento", 80, true));
        values.put("liquidity", choice(investment.liquidity, "Liquidez", "unknown", "daily", "up_to30", "over30", "at_maturity"));
        String maturity = text(investment.maturityDate, "Vencimento / validade", 10, true);
        if (!maturity.isEmpty()) {
            if (!maturity.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || maturity.startsWith("0000")) throw invalid("Informe um vencimento válido no formato AAAA-MM-DD.");
            try { java.time.LocalDate.parse(maturity); }
            catch (DateTimeParseException error) { throw invalid("Vencimento / validade inválido."); }
        }
        values.put("maturity_date", maturity);
        values.put("invested_cents", investment.investedCents); values.put("current_cents", investment.currentCents);
        String quantity = decimalQuantity(investment.quantity);
        if (investment.autoQuote) {
            quoteTicker(investment.ticker);
            if (quantity.isEmpty() || new BigDecimal(quantity).signum() <= 0)
                throw invalid("Informe uma quantidade maior que zero para atualizar automaticamente.");
        }
        String price = text(investment.quotePrice, "Preço da cotação", 40, true);
        String at = text(investment.quoteAt, "Data da cotação", 40, true);
        String source = text(investment.quoteSource, "Fonte da cotação", 40, true);
        if (!price.isEmpty() || !at.isEmpty() || !source.isEmpty()) {
            quoteTicker(investment.ticker);
            price = decimalPrice(price); at = quoteTime(at);
            if (!"brapi".equals(source)) throw invalid("Fonte de cotação inválida.");
            if (quantity.isEmpty() || new BigDecimal(quantity).signum() <= 0
                    || investment.currentCents != quoteValue(quantity, price))
                throw invalid("A cotação não corresponde à quantidade e ao valor atual. Limpe a cotação para informar um valor manual.");
        }
        values.put("quantity", quantity); values.put("auto_quote", investment.autoQuote ? 1 : 0);
        values.put("quote_price", price); values.put("quote_at", at); values.put("quote_source", source);
        return values;
    }

    private static String decimalQuantity(String value) {
        String quantity = text(value, "Quantidade", 40, true);
        if (quantity.isEmpty()) return "";
        if (!quantity.matches("[0-9]{1,24}(\\.[0-9]{1,8})?"))
            throw invalid("Quantidade inválida. Use até oito casas decimais e ponto como separador.");
        return new BigDecimal(quantity).stripTrailingZeros().toPlainString();
    }

    private static String decimalPrice(String value) {
        String price = text(value, "Preço da cotação", 40, false);
        if (!price.matches("[0-9]{1,24}(\\.[0-9]{1,12})?") || new BigDecimal(price).signum() <= 0)
            throw invalid("Preço da cotação inválido.");
        return new BigDecimal(price).stripTrailingZeros().toPlainString();
    }

    private static String quoteTicker(String value) {
        String ticker = text(value, "Código para cotação", 30, false).toUpperCase(Locale.ROOT);
        if (!ticker.matches("[A-Z0-9][A-Z0-9.^-]{0,29}")) throw invalid("Código de ativo inválido para cotação.");
        return ticker;
    }

    private static String quoteTime(String value) {
        try {
            Instant instant = Instant.parse(text(value, "Data da cotação", 40, false));
            if (!instant.isAfter(Instant.EPOCH) || instant.isAfter(Instant.now().plusSeconds(600)))
                throw invalid("Data da cotação inválida.");
            return instant.toString();
        } catch (DateTimeParseException error) { throw invalid("Data da cotação inválida."); }
    }

    private static long quoteValue(String quantity, String price) {
        try {
            return new BigDecimal(quantity).multiply(new BigDecimal(price))
                    .setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        } catch (ArithmeticException error) { throw invalid("O valor da posição excede o limite monetário suportado."); }
    }

    private void validateEntryReferences(Entry entry, Set<Long> accountIds, Map<Long, Category> categoryIds, Set<Long> cardIds) {
        boolean accountExists = accountIds != null ? accountIds.contains(entry.accountId) : exists("accounts", "_id=?", entry.accountId);
        if (!accountExists) throw invalid("Conta do lançamento inexistente.");
        if (entry.cardId != 0 && !(cardIds != null ? cardIds.contains(entry.cardId) : exists("cards", "_id=?", entry.cardId)))
            throw invalid("Cartão do lançamento inexistente.");
        if (entry.categoryId != 0) {
            String categoryType = null;
            if (categoryIds != null) {
                Category category = categoryIds.get(entry.categoryId);
                if (category != null) categoryType = category.type;
            } else {
                try (Cursor c = db().query("categories", new String[]{"type"}, "_id=?", args(entry.categoryId), null, null, null)) {
                    if (c.moveToFirst()) categoryType = c.getString(0);
                }
            }
            if (categoryType == null) throw invalid("Categoria do lançamento inexistente.");
            if (!"both".equals(categoryType) && !entry.type.equals(categoryType))
                throw invalid("A categoria selecionada não corresponde ao tipo do lançamento.");
        }
    }

    private long save(String table, long id, ContentValues values) {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            long result = id;
            if (id == 0) result = db.insertOrThrow(table, null, values);
            else if (db.update(table, values, "_id=?", args(id)) != 1) throw invalid("Registro inexistente. Atualize a lista.");
            if (financialTable(table)) validateFinancialRanges();
            db.setTransactionSuccessful();
            return result;
        } catch (SQLiteConstraintException error) {
            throw invalid("Nome ou identificador já cadastrado, ou vínculo inválido.");
        } finally { db.endTransaction(); }
    }

    private void insertRestored(String table, long id, ContentValues values) {
        values.put("_id", id); db().insertOrThrow(table, null, values);
    }

    private static void writeB3Snapshot(SQLiteDatabase db, B3Portfolio.Snapshot snapshot) {
        db.delete("b3_positions", null, null);
        db.delete("b3_snapshots", null, null);
        if (snapshot == null) return;
        ContentValues metadata = new ContentValues();
        metadata.put("_id", 1); metadata.put("as_of_date", snapshot.asOfDate);
        metadata.put("imported_at", snapshot.importedAt); metadata.put("source_hash", snapshot.sourceHash);
        db.insertOrThrow("b3_snapshots", null, metadata);
        long ordinal = 1;
        for (B3Portfolio.Position p : snapshot.positions) {
            ContentValues row = new ContentValues();
            row.put("_id", ordinal++); row.put("snapshot_id", 1); row.put("identity_key", p.identityKey());
            row.put("product", p.product); row.put("institution", p.institution); row.put("account", p.account);
            row.put("ticker", p.ticker); row.put("isin", p.isin); row.put("kind", p.kind); row.put("quantity", p.quantity);
            if (p.currentCents == null) row.putNull("current_cents"); else row.put("current_cents", p.currentCents);
            db.insertOrThrow("b3_positions", null, row);
        }
    }

    private static Object b3Json(B3Portfolio.Snapshot snapshot) throws JSONException {
        if (snapshot == null) return JSONObject.NULL;
        JSONArray positions = new JSONArray();
        for (B3Portfolio.Position p : snapshot.positions) {
            positions.put(new JSONObject().put("product", p.product).put("institution", p.institution)
                    .put("account", p.account).put("ticker", p.ticker).put("isin", p.isin).put("kind", p.kind)
                    .put("quantity", p.quantity).put("currentCents", p.currentCents == null ? JSONObject.NULL : p.currentCents));
        }
        return new JSONObject().put("asOfDate", snapshot.asOfDate).put("importedAt", snapshot.importedAt)
                .put("sourceHash", snapshot.sourceHash).put("positions", positions);
    }

    private static B3Portfolio.Snapshot parseB3Json(JSONObject report) throws JSONException {
        JSONArray rows = report.getJSONArray("positions");
        if (rows.length() > B3Portfolio.MAX_POSITIONS) throw invalid("O relatório B3 deve ter no máximo 5.000 posições.");
        List<B3Portfolio.Position> positions = new ArrayList<>(rows.length());
        for (int n = 0; n < rows.length(); n++) {
            JSONObject row = rows.getJSONObject(n);
            Object value = row.get("currentCents");
            Long currentCents = value == JSONObject.NULL ? null : jsonLong(row, "currentCents");
            positions.add(new B3Portfolio.Position(jsonString(row, "product"), jsonString(row, "institution"),
                    jsonString(row, "account"), jsonString(row, "ticker"), jsonString(row, "isin"),
                    jsonString(row, "kind"), jsonString(row, "quantity"), currentCents));
        }
        B3Portfolio.Snapshot snapshot = new B3Portfolio.Snapshot(jsonString(report, "asOfDate"), jsonString(report, "importedAt"),
                jsonString(report, "sourceHash"), positions);
        B3Portfolio.requireNotFutureDate(snapshot.asOfDate);
        return snapshot;
    }
    private void validateUniqueName(String table, String name, long id) {
        String normalized = name.toLowerCase(Locale.ROOT);
        try (Cursor c = db().query(table, new String[]{"_id", "name"}, null, null, null, null, null)) {
            while (c.moveToNext()) {
                if (c.getLong(0) != id && normalized.equals(c.getString(1).toLowerCase(Locale.ROOT)))
                    throw invalid("Já existe um registro com esse nome.");
            }
        }
    }
    private void delete(String table, long id) {
        positiveId(id);
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            db.delete(table, "_id=?", args(id));
            if (financialTable(table)) validateFinancialRanges();
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private boolean exists(String table, String where, Object... parameters) {
        try (Cursor c = db().query(table, new String[]{"_id"}, where, args(parameters), null, null, null, "1")) { return c.moveToFirst(); }
    }
    private static String[] args(Object... values) {
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) result[i] = String.valueOf(values[i]);
        return result;
    }
    private static String string(Cursor c, String name) { int index = c.getColumnIndexOrThrow(name); return c.isNull(index) ? "" : c.getString(index); }
    private static long number(Cursor c, String name) { int index = c.getColumnIndexOrThrow(name); return c.isNull(index) ? 0 : c.getLong(index); }
    private static String text(String value, String field, int max, boolean optional) {
        if (value == null) { if (optional) return ""; throw invalid(field + " é obrigatório."); }
        String result = value.trim();
        if ((!optional && result.isEmpty()) || result.length() > max || result.indexOf('\u0000') >= 0)
            throw invalid(field + ": informe de " + (optional ? 0 : 1) + " a " + max + " caracteres.");
        return result;
    }
    private static String choice(String value, String field, String... choices) {
        for (String choice : choices) if (choice.equals(value)) return value;
        throw invalid(field + " inválido.");
    }
    private static boolean isNotificationSource(String sourceKey) {
        return sourceKey != null && sourceKey.matches("notification:[0-9a-fA-F]{64}");
    }
    private static long checkedCents(BigInteger value) {
        if (value.compareTo(MAX_CENTS) > 0 || value.compareTo(MIN_CENTS) < 0)
            throw invalid("O saldo ou total excede o limite monetário suportado.");
        return value.longValue();
    }
    private static boolean financialTable(String table) {
        return "accounts".equals(table) || "entries".equals(table) || "investments".equals(table);
    }
    /** Rejects mutations that would overflow any dashboard total, without partial writes. */
    private void validateFinancialRanges() {
        Map<Long, BigInteger> balances = new HashMap<>();
        BigInteger absoluteTotal = BigInteger.ZERO;
        try (Cursor c = db().query("accounts", new String[]{"_id", "opening_cents"}, null, null, null, null, null)) {
            while (c.moveToNext()) {
                BigInteger opening = BigInteger.valueOf(c.getLong(1));
                balances.put(c.getLong(0), opening); absoluteTotal = absoluteTotal.add(opening.abs());
            }
        }
        BigInteger income = BigInteger.ZERO, expenses = BigInteger.ZERO;
        try (Cursor c = db().query("entries", new String[]{"account_id", "type", "method", "amount_cents"}, null, null, null, null, null)) {
            while (c.moveToNext()) {
                BigInteger value = BigInteger.valueOf(c.getLong(3));
                absoluteTotal = absoluteTotal.add(value);
                boolean isIncome = "income".equals(c.getString(1));
                if (isIncome) income = income.add(value); else expenses = expenses.add(value);
                if (!"credit".equals(c.getString(2))) {
                    BigInteger current = balances.get(c.getLong(0));
                    if (current == null) throw invalid("Conta do lançamento inexistente.");
                    balances.put(c.getLong(0), current.add(isIncome ? value : value.negate()));
                }
            }
        }
        checkedCents(income); checkedCents(expenses);
        BigInteger cash = BigInteger.ZERO;
        for (BigInteger balance : balances.values()) { checkedCents(balance); cash = cash.add(balance); }
        checkedCents(cash);
        BigInteger invested = BigInteger.ZERO, current = BigInteger.ZERO;
        try (Cursor c = db().query("investments", new String[]{"invested_cents", "current_cents"}, null, null, null, null, null)) {
            while (c.moveToNext()) {
                invested = invested.add(BigInteger.valueOf(c.getLong(0)));
                current = current.add(BigInteger.valueOf(c.getLong(1)));
            }
        }
        checkedCents(invested); checkedCents(current); checkedCents(cash.add(current));
        // Any subset or order used by a UI aggregate stays inside long without wrapping.
        checkedCents(absoluteTotal.add(invested).add(current));
    }
    private static void nonnegativeId(long id) { if (id < 0) throw invalid("Identificador inválido."); }
    private static void positiveId(long id) { if (id <= 0 || id == Long.MAX_VALUE) throw invalid("Identificador inválido."); }
    private static void uniqueId(Set<Long> ids, long id) { positiveId(id); if (!ids.add(id)) throw invalid("Backup com IDs repetidos."); }
    private static void uniqueName(Set<String> names, String name) {
        if (!names.add(name.trim().toLowerCase(Locale.ROOT))) throw invalid("Backup com nomes repetidos.");
    }
    private static long jsonLong(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw invalid("Campo numérico inválido no backup: " + key);
        return ((Number) value).longValue();
    }
    private static String jsonString(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof String)) throw invalid("Campo de texto inválido no backup: " + key);
        return (String) value;
    }
    private static boolean jsonBoolean(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Boolean)) throw invalid("Campo booleano inválido no backup: " + key);
        return (Boolean) value;
    }
    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@\t\r\n".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context, "myfinancehub.db", null, 4); }
        @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE categories (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE, type TEXT NOT NULL CHECK(type IN ('income','expense','both')))");
            db.execSQL("CREATE TABLE accounts (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE, opening_cents INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE cards (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE, last_four TEXT NOT NULL, closing_day INTEGER NOT NULL CHECK(closing_day BETWEEN 1 AND 31), due_day INTEGER NOT NULL CHECK(due_day BETWEEN 1 AND 31))");
            db.execSQL("CREATE TABLE entries (_id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL REFERENCES accounts(_id) ON DELETE RESTRICT, category_id INTEGER REFERENCES categories(_id) ON DELETE SET NULL, card_id INTEGER REFERENCES cards(_id) ON DELETE SET NULL, description TEXT NOT NULL, date TEXT NOT NULL, type TEXT NOT NULL CHECK(type IN ('income','expense')), method TEXT NOT NULL CHECK(method IN ('cash','debit','credit','pix','transfer','card_payment','other')), source_key TEXT, amount_cents INTEGER NOT NULL CHECK(amount_cents>0), UNIQUE(account_id,source_key))");
            db.execSQL("CREATE INDEX entries_date ON entries(date DESC, _id DESC)");
            db.execSQL("CREATE INDEX entries_category ON entries(category_id)");
            db.execSQL("CREATE INDEX entries_card ON entries(card_id)");
            db.execSQL("CREATE TABLE investments (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ticker TEXT NOT NULL, kind TEXT NOT NULL, invested_cents INTEGER NOT NULL CHECK(invested_cents>=0), current_cents INTEGER NOT NULL CHECK(current_cents>=0))");
            addInvestmentQuoteColumns(db);
            addInvestmentMetadataColumns(db);
            createB3Tables(db);
            ContentValues account = new ContentValues(); account.put("name", "Principal"); account.put("opening_cents", 0);
            db.insertOrThrow("accounts", null, account);
            String[] expenses = {"Alimentação", "Moradia", "Transporte", "Saúde", "Educação", "Lazer", "Compras", "Serviços", "Impostos", "Outras despesas"};
            String[] income = {"Salário", "Rendimentos", "Outras receitas"};
            for (String name : expenses) seedCategory(db, name, "expense");
            for (String name : income) seedCategory(db, name, "income");
            seedCategory(db, "Transferências", "both");
        }
        private void seedCategory(SQLiteDatabase db, String name, String type) {
            ContentValues values = new ContentValues(); values.put("name", name); values.put("type", type);
            db.insertOrThrow("categories", null, values);
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 1 || oldVersion > 3 || newVersion != 4)
                throw new IllegalStateException("Migração de banco não suportada.");
            if (oldVersion < 2) createB3Tables(db);
            if (oldVersion < 3) addInvestmentQuoteColumns(db);
            addInvestmentMetadataColumns(db);
        }
        private static void addInvestmentMetadataColumns(SQLiteDatabase db) {
            db.execSQL("ALTER TABLE investments ADD COLUMN liquidity TEXT NOT NULL DEFAULT 'unknown' CHECK(liquidity IN ('unknown','daily','up_to30','over30','at_maturity'))");
            db.execSQL("ALTER TABLE investments ADD COLUMN maturity_date TEXT NOT NULL DEFAULT ''");
        }
        private static void addInvestmentQuoteColumns(SQLiteDatabase db) {
            db.execSQL("ALTER TABLE investments ADD COLUMN quantity TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE investments ADD COLUMN auto_quote INTEGER NOT NULL DEFAULT 0 CHECK(auto_quote IN (0,1))");
            db.execSQL("ALTER TABLE investments ADD COLUMN quote_price TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE investments ADD COLUMN quote_at TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE investments ADD COLUMN quote_source TEXT NOT NULL DEFAULT ''");
        }
        private static void createB3Tables(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE b3_snapshots (_id INTEGER PRIMARY KEY CHECK(_id=1), as_of_date TEXT NOT NULL, imported_at TEXT NOT NULL, source_hash TEXT NOT NULL)");
            db.execSQL("CREATE TABLE b3_positions (_id INTEGER PRIMARY KEY, snapshot_id INTEGER NOT NULL REFERENCES b3_snapshots(_id) ON DELETE CASCADE, identity_key TEXT NOT NULL UNIQUE, product TEXT NOT NULL, institution TEXT NOT NULL, account TEXT NOT NULL, ticker TEXT NOT NULL, isin TEXT NOT NULL, kind TEXT NOT NULL, quantity TEXT NOT NULL, current_cents INTEGER CHECK(current_cents IS NULL OR current_cents>=0))");
        }
    }
}
