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
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
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
    private static final int BACKUP_VERSION = 1;
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
            while (c.moveToNext()) result.add(new Investment(number(c, "_id"), string(c, "name"),
                    string(c, "ticker"), string(c, "kind"), number(c, "invested_cents"), number(c, "current_cents")));
        }
        return result;
    }

    /** Stores manually entered invested and current amounts; both must be zero or positive. */
    public synchronized long saveInvestment(Investment investment) {
        ContentValues values = investmentValues(investment);
        investment.id = save("investments", investment.id, values);
        return investment.id;
    }

    public synchronized void deleteInvestment(long id) { delete("investments", id); }

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
                    .put("ticker", i.ticker).put("kind", i.kind).put("investedCents", i.investedCents).put("currentCents", i.currentCents));
            String backup = root.put("categories", categories).put("accounts", accounts).put("cards", cards)
                    .put("entries", entries).put("investments", investments).toString(2);
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
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0)
                throw invalid("Backup JSON inválido.");
            JSONObject root = (JSONObject) parsed;
            if (!"MyFinanceHub".equals(jsonString(root, "format")) || jsonLong(root, "version") != BACKUP_VERSION)
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
                investmentValues(i); investments.add(i);
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
        values.put("invested_cents", investment.investedCents); values.put("current_cents", investment.currentCents);
        return values;
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
    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@\t\r\n".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context, "myfinancehub.db", null, 1); }
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
            throw new IllegalStateException("Migração de banco ainda não disponível.");
        }
    }
}
