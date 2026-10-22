package br.com.myfinancehub;

/** Mutable records used by the local database and the Android forms. Money is always in cents. */
public final class Models {
    private Models() {}

    public static final class Category {
        public long id;
        public String name = "";
        /** income, expense, or both. */
        public String type = "both";
        public Category() {}
        public Category(long id, String name, String type) {
            this.id = id; this.name = name; this.type = type;
        }
    }

    public static final class Account {
        public long id;
        public String name = "";
        public long openingCents;
        public Account() {}
        public Account(long id, String name, long openingCents) {
            this.id = id; this.name = name; this.openingCents = openingCents;
        }
    }

    public static final class Card {
        public long id;
        public String name = "";
        /** Optional last four digits; never a full card number. */
        public String lastFour = "";
        public int closingDay = 1;
        public int dueDay = 10;
        public Card() {}
        public Card(long id, String name, String lastFour, int closingDay, int dueDay) {
            this.id = id; this.name = name; this.lastFour = lastFour;
            this.closingDay = closingDay; this.dueDay = dueDay;
        }
    }

    public static final class Entry {
        public long id, accountId, categoryId, cardId;
        public String description = "";
        /** ISO calendar date, yyyy-MM-dd. */
        public String date = "";
        /** income or expense; amountCents is strictly positive. */
        public String type = "expense";
        /** cash, debit, credit, pix, transfer, card_payment, or other. */
        public String method = "other";
        /** Optional stable import identifier, unique within an account. */
        public String sourceKey = "";
        public long amountCents;
        public Entry() {}
        public Entry(long id, long accountId, long categoryId, long cardId,
                     String description, String date, String type, String method,
                     String sourceKey, long amountCents) {
            this.id = id; this.accountId = accountId; this.categoryId = categoryId;
            this.cardId = cardId; this.description = description; this.date = date;
            this.type = type; this.method = method; this.sourceKey = sourceKey;
            this.amountCents = amountCents;
        }
    }

    public static final class Investment {
        public long id;
        public String name = "", ticker = "", kind = "";
        public long investedCents, currentCents;
        /** Optional user-entered liquidity classification; unknown is never inferred. */
        public String liquidity = "unknown";
        /** Optional maturity / validity calendar day in ISO format. Empty means not informed. */
        public String maturityDate = "";
        /** Exact unit quantity using a decimal dot; empty means it has not been entered. */
        public String quantity = "";
        /** User opt-in to refresh this investment when automatic quotes run. */
        public boolean autoQuote;
        /** Last unit price in BRL as an exact decimal, with its provider timestamp/source. */
        public String quotePrice = "", quoteAt = "", quoteSource = "";
        public Investment() {}
        public Investment(long id, String name, String ticker, String kind,
                          long investedCents, long currentCents) {
            this.id = id; this.name = name; this.ticker = ticker; this.kind = kind;
            this.investedCents = investedCents; this.currentCents = currentCents;
        }
    }
}
