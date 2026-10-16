package br.com.myfinancehub;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local, deterministic statement reader. No Android APIs, networking, or XML entity resolution.
 *
 * <p>CSV: UTF-8 text (optional BOM), comma/semicolon delimiter, RFC 4180 quoting, ISO or
 * dd/MM/yyyy dates, decimal comma/dot, optional Brazilian/US grouping, and header aliases in
 * Portuguese/English. Empty physical lines and extra trailing empty cells are allowed; unnamed
 * trailing header columns must also have empty data. Malformed nonempty records fail the
 * entire preview. Amounts must have at most two decimal places and cannot be zero. Explicit
 * income/expense (entrada/saida, receita/despesa, credito/debito) takes precedence over signs.
 * Without a type, account CSV uses negative=expense; credit-card CSV reverses that convention.
 *
 * <p>OFX: SGML 1.x and XML 2.x STMTTRN blocks, including credit-card statements, using
 * DTPOSTED/TRNAMT/NAME/MEMO/FITID. One statement/account per file; an explicit CURDEF must
 * be BRL because this application does not perform currency conversion. OFX always uses its own debit-negative convention, even
 * when the CSV credit-card option is selected. Dates retain the statement's calendar date;
 * timezone offsets never shift a posting to a different day. DTDs, custom entities and external
 * references are rejected. Standard/numeric XML entities and CDATA are supported by a small
 * tokenizer that never opens external resources; this is not a general-purpose XML parser.
 *
 * <p>Limits: 8 MiB UTF-8, 20,000 transactions, 128 CSV columns, 16,384 characters per field.
 * Existing external IDs provide stable keys. Otherwise a canonical transaction digest plus
 * occurrence number preserves legitimate identical transactions while making reimports stable.
 * The caller must scope these keys by the destination account. Category is a suggestion, not
 * part of identity, so changing categorization does not create a second transaction.
 */
public final class StatementImporter {
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_TRANSACTIONS = 20_000;
    private static final int MAX_COLUMNS = 128;
    private static final int MAX_FIELD_LENGTH = 16_384;
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter
            .ofPattern("uuuu-MM-dd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter
            .ofPattern("dd/MM/uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter OFX_DATE = DateTimeFormatter
            .ofPattern("uuuuMMdd", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

    private StatementImporter() { }

    public static final class Row {
        public final String date;
        public final String description;
        public final String type;
        public final long amountCents;
        public final String suggestedCard;
        public final String suggestedCategory;
        public final String sourceKey;

        private Row(String date, String description, String type, long amountCents,
                    String suggestedCard, String suggestedCategory, String sourceKey) {
            this.date = date;
            this.description = description;
            this.type = type;
            this.amountCents = amountCents;
            this.suggestedCard = suggestedCard;
            this.suggestedCategory = suggestedCategory;
            this.sourceKey = sourceKey;
        }
    }

    public static List<Row> parse(String content, String fileName) {
        return parse(content, fileName, false);
    }

    /** creditCardStatement affects CSV without an explicit type, never OFX. */
    public static List<Row> parse(String content, String fileName, boolean creditCardStatement) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("O arquivo está vazio.");
        }
        if (content.length() > MAX_FILE_BYTES
                || content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("O arquivo excede o limite de 8 MiB. Divida o extrato em arquivos menores.");
        }
        if (content.charAt(0) == '\uFEFF') content = content.substring(1);
        String beginning = content.substring(0, Math.min(content.length(), 512))
                .trim().toUpperCase(Locale.ROOT);
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        boolean ofx = name.endsWith(".ofx") || name.endsWith(".qfx")
                || beginning.startsWith("OFXHEADER:") || beginning.startsWith("<");
        Map<String, Integer> occurrences = new HashMap<>();
        return ofx ? parseOfx(content, occurrences)
                : parseCsv(content, creditCardStatement, occurrences);
    }

    private static List<Row> parseCsv(String content, boolean creditCardStatement,
                                      Map<String, Integer> occurrences) {
        char delimiter = detectDelimiter(content);
        List<CsvRecord> records = readCsv(content, delimiter);
        if (records.isEmpty()) throw new IllegalArgumentException("O arquivo CSV está vazio.");
        CsvRecord header = records.get(0);
        int headerWidth = header.cells.size();
        while (headerWidth > 0 && header.cells.get(headerWidth - 1).trim().isEmpty()) {
            headerWidth--;
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < headerWidth; i++) {
            String key = headerKey(header.cells.get(i));
            if (key != null && columns.put(key, i) != null) {
                throw csvError(header.line, "O cabeçalho repete a coluna " + key + ".");
            }
        }
        if (!columns.containsKey("date") || !columns.containsKey("description")
                || !columns.containsKey("amount")) {
            throw csvError(header.line, "O cabeçalho precisa conter data, descrição e valor "
                    + "(ou date, description/title e amount).");
        }
        List<Row> result = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            CsvRecord record = records.get(i);
            boolean validWidth = record.cells.size() >= headerWidth;
            // Only empty cells after the last named header column may be omitted or added.
            // Do not shift cells or discard financial/text content to repair malformed rows.
            for (int column = headerWidth; column < record.cells.size(); column++) {
                if (!record.cells.get(column).trim().isEmpty()) validWidth = false;
            }
            if (!validWidth) {
                throw csvError(record.line, "Quantidade de colunas diferente do cabeçalho: esperadas "
                        + headerWidth + ", encontradas " + record.cells.size()
                        + "; separador detectado '" + delimiter + "'. "
                        + "Valores com vírgula e textos com separadores devem estar entre aspas.");
            }
            String date = parseCsvDate(cell(record, columns, "date"), record.line);
            String description = cell(record, columns, "description").trim();
            if (description.isEmpty()) throw csvError(record.line, "A descrição não foi informada.");
            BigDecimal amount = parseAmount(cell(record, columns, "amount"), "Linha " + record.line);
            String explicitType = cell(record, columns, "type").trim();
            String type;
            if (!explicitType.isEmpty()) {
                type = parseType(explicitType, record.line);
            } else {
                boolean expense = amount.signum() < 0;
                if (creditCardStatement) expense = !expense;
                type = expense ? "expense" : "income";
            }
            String card = optional(cell(record, columns, "card"));
            String category = optional(cell(record, columns, "category"));
            String id = optional(cell(record, columns, "id"));
            long cents = cents(amount, "Linha " + record.line);
            result.add(new Row(date, description, type, cents, card, category,
                    sourceKey(id, date, description, type, cents, card, occurrences)));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("O CSV contém apenas o cabeçalho, sem lançamentos.");
        return result;
    }

    private static String cell(CsvRecord record, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null ? "" : record.cells.get(index);
    }

    private static String headerKey(String raw) {
        String key = normalized(raw);
        switch (key) {
            case "data": case "date": return "date";
            case "descricao": case "description": case "historico": case "title": return "description";
            case "valor": case "amount": return "amount";
            case "tipo": case "type": return "type";
            case "cartao": case "card": return "card";
            case "categoria": case "category": return "category";
            case "id": case "fitid": return "id";
            default: return null;
        }
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").trim().toLowerCase(Locale.ROOT);
    }

    private static String parseType(String raw, int line) {
        switch (normalized(raw)) {
            case "income": case "entrada": case "receita": case "credito": case "c":
                return "income";
            case "expense": case "saida": case "despesa": case "debito": case "d":
                return "expense";
            default:
                throw csvError(line, "Tipo inválido: use entrada/saída ou income/expense.");
        }
    }

    private static String parseCsvDate(String raw, int line) {
        String value = raw.trim();
        try {
            LocalDate date = LocalDate.parse(value, value.indexOf('/') >= 0 ? BR_DATE : ISO_DATE);
            if (date.getYear() < 1 || date.getYear() > 9999) throw new DateTimeParseException("Ano inválido", value, 0);
            return date.format(ISO_DATE);
        } catch (DateTimeParseException error) {
            throw csvError(line, "Data inválida: use yyyy-MM-dd ou dd/MM/yyyy e uma data existente.");
        }
    }

    private static BigDecimal parseAmount(String raw, String location) {
        String value = raw.trim().replace("\u00A0", "").replace(" ", "");
        if (value.regionMatches(true, 0, "R$", 0, 2)) value = value.substring(2);
        boolean parentheses = value.startsWith("(") && value.endsWith(")");
        if (parentheses) value = value.substring(1, value.length() - 1);
        String unsigned = value.startsWith("-") || value.startsWith("+") ? value.substring(1) : value;
        if (parentheses && (value.startsWith("-") || value.startsWith("+"))) {
            throw amountError(location);
        }
        if (unsigned.contains(",") && unsigned.contains(".")) {
            if (unsigned.lastIndexOf(',') > unsigned.lastIndexOf('.')) {
                if (!unsigned.matches("[0-9]{1,3}(?:\\.[0-9]{3})+,[0-9]{1,2}")) throw amountError(location);
                value = value.replace(".", "").replace(',', '.');
            } else {
                if (!unsigned.matches("[0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]{1,2}")) throw amountError(location);
                value = value.replace(",", "");
            }
        } else {
            if (!unsigned.matches("[0-9]+(?:[.,][0-9]{1,2})?")) throw amountError(location);
            value = value.replace(',', '.');
        }
        try {
            BigDecimal amount = new BigDecimal(value);
            if (parentheses) amount = amount.negate();
            if (amount.signum() == 0) {
                throw new IllegalArgumentException(location + ": o valor deve ser diferente de zero.");
            }
            // Validate range before creating any preview rows; no floating-point rounding.
            amount.abs().movePointRight(2).longValueExact();
            return amount;
        } catch (NumberFormatException | ArithmeticException error) {
            throw amountError(location);
        }
    }

    private static IllegalArgumentException amountError(String location) {
        return new IllegalArgumentException(location + ": valor inválido ou fora do limite. "
                + "Use até duas casas decimais, por exemplo -1234,56 ou 1.234,56.");
    }

    private static long cents(BigDecimal amount, String location) {
        try {
            return amount.abs().movePointRight(2).longValueExact();
        } catch (ArithmeticException error) {
            throw amountError(location);
        }
    }

    private static char detectDelimiter(String content) {
        boolean quoted = false;
        int commas = 0;
        int semicolons = 0;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < content.length() && content.charAt(i + 1) == '"') i++;
                else quoted = !quoted;
            } else if (!quoted && (ch == '\n' || ch == '\r')) {
                if (commas > 0 || semicolons > 0) break;
            } else if (!quoted && ch == ',') commas++;
            else if (!quoted && ch == ';') semicolons++;
        }
        return semicolons > commas ? ';' : ',';
    }

    private static final class CsvRecord {
        final List<String> cells;
        final int line;
        CsvRecord(List<String> cells, int line) { this.cells = cells; this.line = line; }
    }

    private static List<CsvRecord> readCsv(String content, char delimiter) {
        List<CsvRecord> records = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean afterQuote = false;
        boolean structuredRecord = false;
        int line = 1;
        int recordLine = 1;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        afterQuote = true;
                    }
                } else if (ch == '\r' || ch == '\n') {
                    if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                    field.append('\n');
                    line++;
                } else {
                    field.append(ch);
                }
            } else if (ch == delimiter) {
                addCell(cells, field, recordLine);
                afterQuote = false;
                structuredRecord = true;
            } else if (ch == '\r' || ch == '\n') {
                addCell(cells, field, recordLine);
                addCsvRecord(records, cells, recordLine, structuredRecord);
                cells = new ArrayList<>();
                afterQuote = false;
                structuredRecord = false;
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                line++;
                recordLine = line;
            } else if (ch == '"') {
                if (afterQuote || field.length() != 0) throw csvError(recordLine, "Aspas em posição inválida.");
                quoted = true;
                structuredRecord = true;
            } else if (afterQuote) {
                if (ch != ' ' && ch != '\t') {
                    throw csvError(recordLine, "Há texto após o fechamento de aspas.");
                }
            } else {
                field.append(ch);
            }
            if (field.length() > MAX_FIELD_LENGTH) throw csvError(recordLine, "Um campo excede 16.384 caracteres.");
        }
        if (quoted) throw csvError(recordLine, "Há um campo com aspas não fechadas.");
        if (field.length() > 0 || !cells.isEmpty() || afterQuote || structuredRecord) {
            addCell(cells, field, recordLine);
            addCsvRecord(records, cells, recordLine, structuredRecord);
        }
        return records;
    }

    private static void addCell(List<String> cells, StringBuilder field, int line) {
        if (cells.size() >= MAX_COLUMNS) throw csvError(line, "O CSV excede o limite de 128 colunas.");
        cells.add(field.toString());
        field.setLength(0);
    }

    private static void addCsvRecord(List<CsvRecord> records, List<String> cells, int line,
                                     boolean structuredRecord) {
        if (!structuredRecord && cells.size() == 1 && cells.get(0).trim().isEmpty()) return;
        if (records.size() > MAX_TRANSACTIONS) {
            throw csvError(line, "O arquivo excede 20.000 lançamentos. Divida o extrato.");
        }
        records.add(new CsvRecord(cells, line));
    }

    private static IllegalArgumentException csvError(int line, String message) {
        return new IllegalArgumentException("Linha " + line + ": " + message);
    }

    private static final class OfxTransaction {
        final int line;
        final Map<String, String> fields = new HashMap<>();
        OfxTransaction(int line) { this.line = line; }
    }

    private static List<Row> parseOfx(String content, Map<String, Integer> occurrences) {
        // There is deliberately no XML parser, network resolver or DTD processing anywhere.
        String uppercase = content.toUpperCase(Locale.ROOT);
        if (uppercase.contains("<!DOCTYPE") || uppercase.contains("<!ENTITY")) {
            throw new IllegalArgumentException("OFX inseguro: declarações DOCTYPE e entidades externas não são permitidas.");
        }
        List<Row> result = new ArrayList<>();
        OfxTransaction transaction = null;
        String fieldName = null;
        StringBuilder field = new StringBuilder();
        boolean rootSeen = false;
        boolean rootOpen = false;
        boolean rootClosed = false;
        int statementCount = 0;
        int position = 0;
        int line = 1;
        while (position < content.length()) {
            int opening = content.indexOf('<', position);
            if (opening < 0) opening = content.length();
            if (fieldName != null) appendOfxText(field, content.substring(position, opening), line, true);
            line += countLines(content, position, opening);
            position = opening;
            if (position == content.length()) break;
            int tagLine = line;
            if (content.startsWith("<!--", position)) {
                int end = content.indexOf("-->", position + 4);
                if (end < 0) throw ofxError(tagLine, "Comentário não fechado.");
                line += countLines(content, position, end + 3);
                position = end + 3;
                continue;
            }
            if (content.startsWith("<![CDATA[", position)) {
                int end = content.indexOf("]]>", position + 9);
                if (end < 0) throw ofxError(tagLine, "Seção CDATA não fechada.");
                if (fieldName != null) appendOfxText(field, content.substring(position + 9, end), tagLine, false);
                line += countLines(content, position, end + 3);
                position = end + 3;
                continue;
            }
            if (content.startsWith("<?", position)) {
                int end = content.indexOf("?>", position + 2);
                if (end < 0) throw ofxError(tagLine, "Declaração XML não fechada.");
                line += countLines(content, position, end + 2);
                position = end + 2;
                continue;
            }
            int end = findTagEnd(content, position + 1);
            if (end < 0) throw ofxError(tagLine, "Tag não fechada.");
            String tag = content.substring(position + 1, end).trim();
            line += countLines(content, position, end + 1);
            position = end + 1;
            boolean closing = tag.startsWith("/");
            if (closing) tag = tag.substring(1).trim();
            boolean selfClosing = tag.endsWith("/");
            if (selfClosing) tag = tag.substring(0, tag.length() - 1).trim();
            int space = 0;
            while (space < tag.length() && !Character.isWhitespace(tag.charAt(space))) space++;
            String tagName = tag.substring(0, space).toUpperCase(Locale.ROOT);
            if (!tagName.matches("[A-Z][A-Z0-9_:.]*")) {
                throw ofxError(tagLine, "Tag inválida ou declaração não permitida.");
            }
            if (fieldName != null) {
                if (transaction != null) saveOfxField(transaction, fieldName, field.toString());
                else if ("CURDEF".equals(fieldName) && !"BRL".equalsIgnoreCase(field.toString().trim())) {
                    throw ofxError(tagLine, "A moeda CURDEF deve ser BRL. Converta o extrato antes de importar.");
                }
                fieldName = null;
                field.setLength(0);
            }
            if ("OFX".equals(tagName)) {
                if (closing) {
                    if (!rootOpen || transaction != null) throw ofxError(tagLine, "Estrutura OFX incompleta.");
                    rootOpen = false;
                    rootClosed = true;
                } else {
                    if (rootSeen || selfClosing) throw ofxError(tagLine, "Estrutura OFX inválida.");
                    rootSeen = true;
                    rootOpen = true;
                }
            } else if ("STMTTRN".equals(tagName)) {
                if (!rootOpen || selfClosing) throw ofxError(tagLine, "Transação fora da estrutura OFX.");
                if (closing) {
                    if (transaction == null) throw ofxError(tagLine, "Fechamento de transação sem abertura.");
                    if (result.size() >= MAX_TRANSACTIONS) {
                        throw ofxError(tagLine, "O arquivo excede 20.000 lançamentos. Divida o extrato.");
                    }
                    result.add(ofxRow(transaction, occurrences));
                    transaction = null;
                } else {
                    if (transaction != null) throw ofxError(tagLine, "A transação anterior não foi fechada.");
                    transaction = new OfxTransaction(tagLine);
                }
            } else if (!closing && rootOpen && ("STMTRS".equals(tagName) || "CCSTMTRS".equals(tagName))) {
                if (++statementCount > 1) {
                    throw ofxError(tagLine, "O arquivo contém vários extratos/contas. Importe um extrato por arquivo.");
                }
            } else if (!closing && rootOpen && transaction == null && "CURDEF".equals(tagName)) {
                if (selfClosing) throw ofxError(tagLine, "A moeda CURDEF deve ser BRL.");
                fieldName = tagName;
            } else if (!closing && transaction != null && isOfxField(tagName)) {
                fieldName = tagName;
                if (selfClosing) {
                    saveOfxField(transaction, fieldName, "");
                    fieldName = null;
                }
            }
        }
        if (!rootSeen || !rootClosed || rootOpen || transaction != null) {
            throw new IllegalArgumentException("Estrutura OFX incompleta: verifique as tags OFX e STMTTRN.");
        }
        if (result.isEmpty()) throw new IllegalArgumentException("O OFX não contém lançamentos STMTTRN para importar.");
        return result;
    }

    private static boolean isOfxField(String name) {
        return "DTPOSTED".equals(name) || "TRNAMT".equals(name) || "NAME".equals(name)
                || "MEMO".equals(name) || "FITID".equals(name);
    }

    private static void saveOfxField(OfxTransaction transaction, String name, String value) {
        if (transaction.fields.put(name, value.trim()) != null) {
            throw ofxError(transaction.line, "O lançamento repete o campo " + name + ".");
        }
    }

    private static Row ofxRow(OfxTransaction transaction, Map<String, Integer> occurrences) {
        String location = "OFX, linha " + transaction.line;
        String rawDate = transaction.fields.get("DTPOSTED");
        if (rawDate == null || !rawDate.matches("[0-9]{8}(?:[0-9]{6})?(?:\\.[0-9]{1,9})?"
                + "(?:\\[[+-]?[0-9]{1,2}(?:\\.[0-9]{1,2})?(?::[^\\]\\r\\n<>]*)?\\])?")) {
            throw ofxError(transaction.line, "Data DTPOSTED inválida ou ausente.");
        }
        String date;
        try {
            LocalDate parsed = LocalDate.parse(rawDate.substring(0, 8), OFX_DATE);
            if (parsed.getYear() < 1) throw new DateTimeParseException("Ano inválido", rawDate, 0);
            if (rawDate.length() >= 14 && Character.isDigit(rawDate.charAt(8))) {
                int hour = Integer.parseInt(rawDate.substring(8, 10));
                int minute = Integer.parseInt(rawDate.substring(10, 12));
                int second = Integer.parseInt(rawDate.substring(12, 14));
                if (hour > 23 || minute > 59 || second > 59) throw new DateTimeParseException("Hora inválida", rawDate, 8);
            }
            date = parsed.format(ISO_DATE);
        } catch (DateTimeParseException error) {
            throw ofxError(transaction.line, "Data DTPOSTED inválida.");
        }
        BigDecimal amount = parseAmount(transaction.fields.getOrDefault("TRNAMT", ""), location);
        String name = transaction.fields.getOrDefault("NAME", "").trim();
        String memo = transaction.fields.getOrDefault("MEMO", "").trim();
        String description = name.isEmpty() ? memo
                : memo.isEmpty() || memo.equals(name) ? name : name + " — " + memo;
        if (description.isEmpty()) description = "Lançamento importado";
        String type = amount.signum() < 0 ? "expense" : "income";
        long amountCents = cents(amount, location);
        return new Row(date, description, type, amountCents, null, null,
                sourceKey(optional(transaction.fields.get("FITID")), date, description,
                        type, amountCents, null, occurrences));
    }

    private static int findTagEnd(String content, int from) {
        char quote = 0;
        for (int i = from; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
            } else if (ch == '\'' || ch == '"') quote = ch;
            else if (ch == '>') return i;
            else if (ch == '<') return -1;
        }
        return -1;
    }

    private static int countLines(String content, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            char ch = content.charAt(i);
            if (ch == '\n') count++;
            else if (ch == '\r') {
                count++;
                if (i + 1 < to && content.charAt(i + 1) == '\n') i++;
            }
        }
        return count;
    }

    private static void appendOfxText(StringBuilder output, String text, int line, boolean decode) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '&' && decode) {
                int semicolon = -1;
                for (int candidate = i + 1; candidate < text.length() && candidate - i <= 64; candidate++) {
                    if (text.charAt(candidate) == ';') {
                        semicolon = candidate;
                        break;
                    }
                    if (text.charAt(candidate) == '&' || Character.isWhitespace(text.charAt(candidate))) break;
                }
                if (semicolon >= 0) {
                    String entity = text.substring(i + 1, semicolon);
                    switch (entity) {
                        case "amp": output.append('&'); break;
                        case "lt": output.append('<'); break;
                        case "gt": output.append('>'); break;
                        case "quot": output.append('"'); break;
                        case "apos": output.append('\''); break;
                        default:
                            if (!entity.startsWith("#")) throw ofxError(line, "Entidade XML não permitida.");
                            try {
                                boolean hex = entity.startsWith("#x") || entity.startsWith("#X");
                                String digits = entity.substring(hex ? 2 : 1);
                                if (!digits.matches(hex ? "[0-9A-Fa-f]+" : "[0-9]+")) throw new NumberFormatException();
                                int codepoint = Integer.parseInt(digits, hex ? 16 : 10);
                                if (!Character.isValidCodePoint(codepoint)
                                        || (codepoint >= 0xD800 && codepoint <= 0xDFFF)
                                        || (codepoint < 0x20 && codepoint != '\n' && codepoint != '\r' && codepoint != '\t')) {
                                    throw new NumberFormatException();
                                }
                                output.appendCodePoint(codepoint);
                            } catch (NumberFormatException error) {
                                throw ofxError(line, "Entidade numérica XML inválida.");
                            }
                    }
                    i = semicolon;
                } else {
                    // Unescaped '&' occurs in otherwise valid legacy OFX/SGML descriptions.
                    output.append(ch);
                }
            } else {
                output.append(ch);
            }
            if (output.length() > MAX_FIELD_LENGTH) throw ofxError(line, "Um campo excede 16.384 caracteres.");
        }
    }

    private static IllegalArgumentException ofxError(int line, String message) {
        return new IllegalArgumentException("OFX, linha " + line + ": " + message);
    }

    private static String optional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static String sourceKey(String id, String date, String description, String type,
                                     long amountCents, String card, Map<String, Integer> occurrences) {
        if (id != null) return "id:" + sha256(id);
        StringBuilder canonical = new StringBuilder();
        canonicalPart(canonical, date);
        canonicalPart(canonical, compactWhitespace(description));
        canonicalPart(canonical, type);
        canonicalPart(canonical, Long.toString(amountCents));
        canonicalPart(canonical, card == null ? "" : compactWhitespace(card));
        String digest = sha256(canonical.toString());
        int occurrence = occurrences.getOrDefault(digest, 0) + 1;
        occurrences.put(digest, occurrence);
        return "row:" + digest + ":" + occurrence;
    }

    private static String compactWhitespace(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        StringBuilder result = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                space = result.length() > 0;
            } else {
                if (space) result.append(' ');
                result.append(ch);
                space = false;
            }
        }
        return result.toString();
    }

    private static void canonicalPart(StringBuilder result, String value) {
        result.append(value.length()).append(':').append(value).append('|');
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte valueByte : bytes) {
                hex.append(Character.forDigit((valueByte >>> 4) & 15, 16));
                hex.append(Character.forDigit(valueByte & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 indisponível no dispositivo.", error);
        }
    }
}
