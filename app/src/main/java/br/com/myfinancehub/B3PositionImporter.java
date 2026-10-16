package br.com.myfinancehub;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Local position snapshots only. This reader does not access the B3 portal or execute Excel. */
public final class B3PositionImporter {
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_POSITIONS = 5_000;
    private static final int MAX_XML_BYTES = 8 * 1024 * 1024;
    private static final int MAX_UNZIPPED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_COLUMNS = 128;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_FIELD = 4_096;
    private static final int MAX_XML_NODES = 250_000;
    private static final DateTimeFormatter BR_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern CELL_REFERENCE = Pattern.compile("([A-Z]{1,3})([1-9][0-9]{0,6})");
    private static final Pattern POSITION_DATE = Pattern.compile(
            "^(?:data da posicao|data de referencia|data base|posicao em)\\s*:?\\s*(.*)$");

    private B3PositionImporter() { }

    public static final class Report {
        public final List<B3Portfolio.Position> positions;
        /** Empty when the report does not explicitly identify a reliable position date. */
        public final String asOfDate;
        private Report(List<B3Portfolio.Position> positions, String asOfDate) {
            this.positions = Collections.unmodifiableList(new ArrayList<>(positions));
            this.asOfDate = asOfDate;
        }
    }

    public static Report parse(byte[] data, String filename) {
        if (data == null || data.length == 0) throw error("O arquivo está vazio.");
        if (data.length > MAX_FILE_BYTES) throw error("O arquivo excede o limite de 8 MiB.");
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        boolean zip = data.length >= 4 && data[0] == 'P' && data[1] == 'K';
        if (name.endsWith(".xls") || (data.length >= 2 && (data[0] & 255) == 0xD0
                && (data[1] & 255) == 0xCF)) {
            throw error("Use a planilha .xlsx de Posição da B3 ou um CSV de posições. Arquivos .xls e planilhas criptografadas não são aceitos.");
        }
        if (name.endsWith(".xlsm") || name.endsWith(".xlsb")) {
            throw error("Planilhas com macros ou formato binário não são aceitas. Use .xlsx.");
        }
        Builder result = new Builder();
        if (zip || name.endsWith(".xlsx")) readXlsx(data, result);
        else {
            if (!name.isEmpty() && !name.endsWith(".csv") && !name.endsWith(".txt")) {
                throw error("Formato não suportado. Escolha o Excel .xlsx de Posição ou um CSV de posições.");
            }
            readTable(readCsv(decodeCsv(data)), "", result);
        }
        if (result.positions.isEmpty()) throw error("O arquivo não contém posições para importar.");
        return new Report(result.positions, result.date);
    }

    private static final class Builder {
        final List<B3Portfolio.Position> positions = new ArrayList<>();
        final Set<String> identities = new HashSet<>();
        String date = "";
        long totalCents;
        void setDate(String value) {
            if (value.isEmpty()) return;
            if (!date.isEmpty() && !date.equals(value)) {
                throw error("O arquivo contém datas de posição diferentes. Exporte uma única data de referência.");
            }
            date = value;
        }
        void add(B3Portfolio.Position p) {
            if (positions.size() >= MAX_POSITIONS) throw error("O arquivo excede 5.000 posições.");
            for (String key : p.identityKeys()) {
                if (identities.contains(key)) throw error("Há posições duplicadas no arquivo. Revise produto, instituição e conta antes de importar.");
            }
            identities.addAll(p.identityKeys());
            if (p.currentCents != null) {
                try { totalCents = Math.addExact(totalCents, p.currentCents); }
                catch (ArithmeticException ex) { throw error("O total da posição B3 excede o limite monetário suportado."); }
            }
            positions.add(p);
        }
    }

    private static final class Cell {
        final String text;
        final boolean numeric;
        Cell(String text, boolean numeric) {
            if (text.length() > MAX_FIELD || text.indexOf('\0') >= 0) throw error("Uma célula é muito longa ou contém texto inválido.");
            this.text = text.trim();
            this.numeric = numeric;
        }
    }

    private static final class Row {
        final int number;
        final Map<Integer, Cell> cells = new LinkedHashMap<>();
        int width;
        Row(int number) { this.number = number; }
        void put(int column, Cell value) {
            if (column < 0 || column >= MAX_COLUMNS || cells.put(column, value) != null) {
                throw error("A planilha tem colunas inválidas, repetidas ou excede 128 colunas.");
            }
            width = Math.max(width, column + 1);
        }
        Cell cell(Integer column) { return column == null ? new Cell("", false)
                : cells.getOrDefault(column, new Cell("", false)); }
        boolean empty() {
            for (Cell cell : cells.values()) if (!cell.text.isEmpty()) return false;
            return true;
        }
    }

    private static void readTable(List<Row> rows, String sheetName, Builder result) {
        int headerIndex = -1;
        Map<String, Integer> columns = null;
        int examined = 0;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row.empty()) continue;
            if (++examined > 30) break;
            rejectMovement(row);
            Map<String, Integer> candidate = header(row);
            if (candidate.containsKey("product") && candidate.containsKey("quantity")) {
                headerIndex = index;
                columns = candidate;
                break;
            }
            readPrefixDate(row, result);
            readPrefixCurrency(row);
        }
        if (headerIndex < 0) {
            boolean nonempty = false;
            for (Row row : rows) nonempty |= !row.empty();
            if (nonempty) throw error("Cabeçalho de posição não reconhecido. É necessário Produto e Quantidade. Exporte Posição na Área do Investidor; relatórios de movimentação não são aceitos.");
            return;
        }
        int headerWidth = rows.get(headerIndex).width;
        String defaultKind = sheetName.matches("(?i)(?:sheet|planilha)[0-9 ]*") ? "" : sheetName;
        for (int index = headerIndex + 1; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row.empty()) continue;
            rejectMovement(row);
            validateCurrency(row.cell(columns.get("currency")).text);
            for (Map.Entry<Integer, Cell> entry : row.cells.entrySet()) {
                if (entry.getKey() >= headerWidth && !entry.getValue().text.isEmpty()) {
                    throw rowError(row.number, "Há dados além das colunas do cabeçalho.");
                }
            }
            String product = row.cell(columns.get("product")).text;
            if (product.isEmpty()) throw rowError(row.number, "O produto não foi informado. Totais e notas devem ser removidos do CSV.");
            if (normalized(product).matches("(?:total|total geral|subtotal|saldo total)")) {
                throw rowError(row.number, "A linha contém um total, não uma posição. Remova totais do CSV.");
            }
            String quantity = number(row.cell(columns.get("quantity")), false, row.number)
                    .stripTrailingZeros().toPlainString();
            Long cents = null;
            Cell value = row.cell(columns.get("current"));
            if (!value.text.isEmpty() && !value.text.equals("-")) {
                try { cents = number(value, true, row.number).movePointRight(2).longValueExact(); }
                catch (ArithmeticException ex) { throw rowError(row.number, "O valor atual está fora do limite ou possui mais de duas casas decimais."); }
            }
            String kind = row.cell(columns.get("kind")).text;
            if (kind.isEmpty()) kind = defaultKind;
            String date = row.cell(columns.get("date")).text;
            if (!date.isEmpty()) result.setDate(parseDate(date, row.number));
            try {
                result.add(new B3Portfolio.Position(product, row.cell(columns.get("institution")).text,
                        row.cell(columns.get("account")).text, row.cell(columns.get("ticker")).text,
                        row.cell(columns.get("isin")).text, kind, quantity, cents));
            } catch (IllegalArgumentException ex) {
                // The storage contract supplies only generic validation messages, never cell payloads.
                throw rowError(row.number, ex.getMessage());
            }
        }
    }

    private static void rejectMovement(Row row) {
        for (Cell cell : row.cells.values()) {
            String key = normalized(cell.text);
            if (key.equals("entrada/saida") || key.equals("entrada / saida")
                    || key.equals("movimentacao") || key.equals("tipo de movimentacao")
                    || key.equals("data da movimentacao") || key.equals("data do negocio")
                    || key.equals("valor da operacao") || key.equals("tipo de operacao")) {
                throw error("Este é um relatório de movimentação ou negociação. Exporte Posição na Área do Investidor da B3.");
            }
        }
    }

    private static Map<String, Integer> header(Row row) {
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<Integer, Cell> entry : row.cells.entrySet()) {
            String key;
            switch (normalized(entry.getValue().text)) {
                case "produto": case "ativo": case "nome do produto": key = "product"; break;
                case "instituicao": case "instituicao financeira": case "corretora": key = "institution"; break;
                case "conta": case "conta na instituicao": case "numero da conta": key = "account"; break;
                case "codigo de negociacao": case "codigo do ativo": case "ticker": key = "ticker"; break;
                case "isin": case "codigo isin": key = "isin"; break;
                case "tipo": case "tipo de produto": case "categoria do produto": case "categoria": key = "kind"; break;
                case "quantidade": case "quantidade total": key = "quantity"; break;
                // Applied value, acquisition price and unit prices are deliberately not aliases.
                case "valor atualizado": case "valor atual": case "valor total":
                case "valor atualizado (r$)": case "valor atual (r$)": case "valor total (r$)":
                case "valor de mercado": case "valor de mercado (r$)": key = "current"; break;
                case "data da posicao": case "data de referencia": case "data base": key = "date"; break;
                case "moeda": case "codigo da moeda": case "currency": case "currency code": key = "currency"; break;
                default: continue;
            }
            if (result.put(key, entry.getKey()) != null) {
                throw rowError(row.number, "O cabeçalho repete colunas equivalentes. Mantenha uma coluna por informação.");
            }
        }
        return result;
    }

    private static void readPrefixDate(Row row, Builder builder) {
        List<Cell> values = new ArrayList<>(row.cells.values());
        for (int index = 0; index < values.size(); index++) {
            Matcher match = POSITION_DATE.matcher(normalized(values.get(index).text));
            if (!match.matches()) continue;
            String date = match.group(1).trim();
            if (date.isEmpty() && index + 1 < values.size()) date = values.get(index + 1).text;
            if (!date.isEmpty()) builder.setDate(parseDate(date, row.number));
        }
    }

    private static void readPrefixCurrency(Row row) {
        List<Cell> values = new ArrayList<>(row.cells.values());
        for (int index = 0; index < values.size(); index++) {
            String value = normalized(values.get(index).text);
            if (value.matches("(?:moeda|codigo da moeda|currency|currency code)")) {
                if (index + 1 < values.size()) validateCurrency(values.get(index + 1).text);
            } else if (value.matches("(?:moeda|codigo da moeda|currency|currency code)\\s*:.*")) {
                validateCurrency(value.substring(value.indexOf(':') + 1));
            }
        }
    }

    private static void validateCurrency(String text) {
        String value = normalized(text);
        if (!value.isEmpty() && !value.equals("brl") && !value.equals("r$")
                && !value.equals("real") && !value.equals("reais")) {
            throw error("A importação de posições B3 aceita somente valores em reais (BRL). Não há conversão de moedas.");
        }
    }

    private static String parseDate(String value, int row) {
        try {
            LocalDate date;
            if (value.matches("[0-9]{2}/[0-9]{2}/[0-9]{4}")) date = LocalDate.parse(value, BR_DATE);
            else if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) date = LocalDate.parse(value);
            else throw new IllegalArgumentException();
            if (date.getYear() < 1) throw new IllegalArgumentException();
            return date.toString();
        } catch (RuntimeException ex) { throw rowError(row, "Data de posição inválida. Use dd/MM/aaaa ou aaaa-MM-dd."); }
    }

    private static BigDecimal number(Cell cell, boolean money, int row) {
        String raw = cell.text.replace('\u00A0', ' ').trim();
        if (money && raw.startsWith("R$")) raw = raw.substring(2).trim();
        String canonical;
        if (cell.numeric) {
            if (!raw.matches("[+]?[0-9]+(?:\\.[0-9]+)?(?:[Ee][+-]?[0-9]{1,2})?")) {
                throw rowError(row, "Número inválido na planilha.");
            }
            canonical = raw;
        } else {
            if (!raw.matches("[+]?(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+)(?:,[0-9]+)?")) {
                throw rowError(row, "Número inválido. No CSV, use formato brasileiro: 1.234,56; quantidades podem ter até 12 casas decimais.");
            }
            canonical = raw.replace(".", "").replace(',', '.');
        }
        try {
            BigDecimal value = new BigDecimal(canonical).stripTrailingZeros();
            if (value.signum() < 0 || value.precision() - value.scale() > 18
                    || value.scale() > (money ? 2 : 12)) throw new ArithmeticException();
            return value;
        } catch (NumberFormatException | ArithmeticException ex) {
            throw rowError(row, money ? "Valor atual inválido ou fora do limite; use até duas casas decimais."
                    : "Quantidade inválida ou fora do limite; use até 12 casas decimais.");
        }
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('\u00A0', ' ').trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String decodeCsv(byte[] data) {
        try { return decodeUtf8(data); }
        catch (IllegalArgumentException ex) {
            try {
                String text = java.nio.charset.Charset.forName("windows-1252").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data)).toString();
                if (text.indexOf('\0') >= 0) throw error("O CSV deve usar UTF-8 ou Windows-1252.");
                return text;
            } catch (CharacterCodingException failure) { throw error("O CSV deve usar UTF-8 ou Windows-1252."); }
        }
    }

    private static String decodeUtf8(byte[] data) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
            if (text.indexOf('\0') >= 0) throw error("O arquivo contém texto inválido.");
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException ex) { throw error("A codificação do arquivo não é suportada."); }
    }

    private static List<Row> readCsv(String text) {
        char delimiter = delimiter(text);
        List<Row> result = new ArrayList<>();
        Row row = new Row(1);
        StringBuilder field = new StringBuilder();
        boolean quoted = false, closed = false;
        int line = 1, column = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') { field.append('"'); index++; }
                    else { quoted = false; closed = true; }
                } else if (ch == '\r' || ch == '\n') {
                    if (ch == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                    field.append('\n'); line++;
                } else field.append(ch);
            } else if (ch == delimiter || ch == '\r' || ch == '\n') {
                row.put(column++, new Cell(field.toString(), false)); field.setLength(0); closed = false;
                if (ch != delimiter) {
                    if (!row.empty() && !(row.width == 2 && row.cell(0).text.equalsIgnoreCase("sep="))) result.add(row);
                    if (ch == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                    line++; row = new Row(line); column = 0;
                }
            } else if (ch == '"') {
                if (field.length() != 0 || closed) throw rowError(row.number, "Aspas em posição inválida no CSV.");
                quoted = true;
            } else if (closed) {
                if (ch != ' ' && ch != '\t') throw rowError(row.number, "Há texto depois do fechamento de aspas.");
            } else field.append(ch);
            if (field.length() > MAX_FIELD) throw rowError(row.number, "Uma célula excede 4.096 caracteres.");
            if (result.size() > MAX_ROWS) throw error("O arquivo contém linhas demais.");
        }
        if (quoted) throw rowError(row.number, "Há aspas não fechadas no CSV.");
        if (field.length() != 0 || column != 0 || closed) {
            row.put(column, new Cell(field.toString(), false));
            if (!row.empty()) result.add(row);
        }
        return result;
    }

    private static char delimiter(String text) {
        boolean quoted = false;
        int commas = 0, semicolons = 0, bestComma = 0, bestSemicolon = 0, line = 0;
        StringBuilder current = new StringBuilder();
        for (int index = 0; index <= text.length() && line < 30; index++) {
            char ch = index == text.length() ? '\n' : text.charAt(index);
            current.append(ch);
            if (ch == '"') {
                if (quoted && index + 1 < text.length() && text.charAt(index + 1) == '"') index++;
                else quoted = !quoted;
            } else if (!quoted && (ch == '\n' || ch == '\r')) {
                String value = normalized(current.toString());
                if ((value.contains("produto") || value.contains("ativo")) && value.contains("quantidade")) {
                    return semicolons > commas ? ';' : ',';
                }
                bestComma = Math.max(bestComma, commas); bestSemicolon = Math.max(bestSemicolon, semicolons);
                commas = semicolons = 0; current.setLength(0); line++;
            } else if (!quoted && ch == ',') commas++;
            else if (!quoted && ch == ';') semicolons++;
        }
        return bestSemicolon > bestComma ? ';' : ',';
    }

    private static void readXlsx(byte[] data, Builder builder) {
        Map<String, byte[]> entries = new HashMap<>();
        int total = 0, count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw error("A planilha contém arquivos internos demais.");
                String path = safePath(entry.getName());
                if (entry.isDirectory()) continue;
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.contains("vbaproject") || lower.contains("encryptedpackage") || lower.endsWith(".bin")) {
                    throw error("Planilhas com macros, conteúdo binário ou criptografia não são aceitas.");
                }
                if (entries.containsKey(path)) throw error("A planilha contém arquivos internos repetidos.");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_UNZIPPED_BYTES || bytes.size() + read > MAX_XML_BYTES) {
                        throw error("A planilha expandida excede o limite de segurança (16 MiB no total; 8 MiB por arquivo interno).");
                    }
                    bytes.write(buffer, 0, read);
                }
                entries.put(path, bytes.toByteArray());
            }
        } catch (IOException ex) { throw error("A planilha está incompleta, corrompida ou criptografada."); }
        if (!entries.containsKey("[Content_Types].xml") || !entries.containsKey("xl/workbook.xml")
                || !entries.containsKey("xl/_rels/workbook.xml.rels")) {
            throw error("O arquivo não é uma planilha Excel .xlsx válida.");
        }
        // Check every relationship, including links on sheets we would otherwise never open.
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().endsWith(".rels")) relationships(entry.getValue());
        }
        XmlPullParser content = xml(entries.get("[Content_Types].xml"));
        try {
            for (int event = content.getEventType(); event != XmlPullParser.END_DOCUMENT; event = next(content)) {
                if (event == XmlPullParser.START_TAG) {
                    String type = attribute(content, "ContentType").toLowerCase(Locale.ROOT);
                    if (type.contains("macroenabled") || type.contains("encrypted")) throw error("Planilhas com macros ou criptografia não são aceitas.");
                }
            }
        } catch (XmlPullParserException ex) { throw error("XML inválido na planilha."); }
        Map<String, String> relations = relationships(entries.get("xl/_rels/workbook.xml.rels"));
        List<String> shared = entries.containsKey("xl/sharedStrings.xml")
                ? sharedStrings(entries.get("xl/sharedStrings.xml")) : Collections.emptyList();
        XmlPullParser workbook = xml(entries.get("xl/workbook.xml"));
        Set<String> sheets = new HashSet<>();
        int sheetCount = 0;
        try {
            for (int event = workbook.getEventType(); event != XmlPullParser.END_DOCUMENT; event = next(workbook)) {
                if (event != XmlPullParser.START_TAG || !workbook.getName().equals("sheet")) continue;
                if (++sheetCount > 32) throw error("A planilha excede 32 abas.");
                String id = attribute(workbook, "id"), name = attribute(workbook, "name");
                String target = relations.get(id);
                if (target == null) throw error("A planilha possui uma aba sem conteúdo acessível.");
                String path = target.startsWith("/") ? safePath(target.substring(1)) : safePath("xl/" + target);
                if (!path.startsWith("xl/worksheets/") || !entries.containsKey(path) || !sheets.add(path)) {
                    throw error("A planilha possui referências de abas inválidas ou repetidas.");
                }
                if (name.length() > 100) throw error("A planilha contém um nome de aba inválido.");
                readTable(sheet(entries.get(path), shared), name, builder);
            }
        } catch (XmlPullParserException ex) { throw error("XML inválido na planilha."); }
        if (sheetCount == 0) throw error("A planilha não contém abas de posição.");
    }

    private static String safePath(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.indexOf('\0') >= 0 || path.contains("//")) throw error("Referência interna inválida na planilha.");
        for (String segment : path.split("/")) if (segment.equals("..") || segment.equals(".")) {
            throw error("Referência interna inválida na planilha.");
        }
        return path;
    }

    private static Map<String, String> relationships(byte[] data) {
        XmlPullParser parser = xml(data);
        Map<String, String> result = new HashMap<>();
        try {
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = next(parser)) {
                if (event != XmlPullParser.START_TAG || !parser.getName().equals("Relationship")) continue;
                String target = attribute(parser, "Target"), mode = attribute(parser, "TargetMode");
                if (!mode.isEmpty() && !mode.equals("Internal") || target.contains(":")
                        || target.startsWith("//") || target.indexOf('\\') >= 0) {
                    throw error("A planilha contém vínculos externos. Exporte uma cópia sem vínculos.");
                }
                String id = attribute(parser, "Id");
                if (id.isEmpty() || target.isEmpty() || result.put(id, target) != null) throw error("Referências internas inválidas na planilha.");
            }
        } catch (XmlPullParserException ex) { throw error("XML inválido na planilha."); }
        return result;
    }

    private static List<String> sharedStrings(byte[] data) {
        List<String> result = new ArrayList<>();
        XmlPullParser parser = xml(data);
        StringBuilder value = null;
        int totalChars = 0;
        try {
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = next(parser)) {
                if (event == XmlPullParser.START_TAG && parser.getName().equals("si")) {
                    if (value != null) throw error("Textos compartilhados inválidos na planilha.");
                    value = new StringBuilder();
                } else if (event == XmlPullParser.START_TAG && parser.getName().equals("t")) {
                    if (value == null) throw error("Textos compartilhados inválidos na planilha.");
                    value.append(text(parser));
                    if (value.length() > MAX_FIELD) throw error("Uma célula excede 4.096 caracteres.");
                } else if (event == XmlPullParser.END_TAG && parser.getName().equals("si")) {
                    if (value == null || result.size() >= 100_000) throw error("A planilha contém textos compartilhados demais.");
                    totalChars += value.length();
                    if (totalChars > MAX_XML_BYTES) throw error("A planilha contém textos demais.");
                    result.add(value.toString()); value = null;
                }
            }
        } catch (XmlPullParserException ex) { throw error("XML inválido na planilha."); }
        return result;
    }

    private static List<Row> sheet(byte[] data, List<String> shared) {
        XmlPullParser parser = xml(data);
        List<Row> result = new ArrayList<>();
        Row row = null;
        int previousRow = 0, previousColumn = -1;
        try {
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = next(parser)) {
                if (event == XmlPullParser.START_TAG && parser.getName().equals("row")) {
                    String reference = attribute(parser, "r");
                    int number = reference.isEmpty() ? previousRow + 1 : positiveInt(reference);
                    if (row != null || number <= previousRow || result.size() >= MAX_ROWS) throw error("Linhas inválidas ou demais na planilha.");
                    row = new Row(number); previousColumn = -1;
                } else if (event == XmlPullParser.START_TAG && parser.getName().equals("c")) {
                    if (row == null) throw error("Célula fora de uma linha na planilha.");
                    String reference = attribute(parser, "r"), type = attribute(parser, "t");
                    int column = previousColumn + 1;
                    if (!reference.isEmpty()) {
                        Matcher match = CELL_REFERENCE.matcher(reference);
                        if (!match.matches() || positiveInt(match.group(2)) != row.number) throw error("Referência de célula inválida na planilha.");
                        column = 0;
                        for (char ch : match.group(1).toCharArray()) column = column * 26 + ch - 'A' + 1;
                        column--;
                    }
                    if (column <= previousColumn) throw error("Células repetidas ou fora de ordem na planilha.");
                    previousColumn = column;
                    row.put(column, readCell(parser, type, shared));
                } else if (event == XmlPullParser.END_TAG && parser.getName().equals("row")) {
                    if (row == null) throw error("Linhas inválidas na planilha.");
                    previousRow = row.number;
                    if (!row.empty()) result.add(row);
                    row = null;
                }
            }
        } catch (XmlPullParserException ex) { throw error("XML inválido na planilha."); }
        return result;
    }

    private static Cell readCell(XmlPullParser parser, String type, List<String> shared) throws XmlPullParserException {
        if (!type.isEmpty() && !type.equals("n") && !type.equals("s") && !type.equals("inlineStr")
                && !type.equals("str")) throw error("A planilha contém uma célula com erro ou tipo não suportado.");
        StringBuilder inline = new StringBuilder();
        String value = "";
        boolean seenValue = false;
        int startDepth = parser.getDepth();
        for (int event = next(parser); event != XmlPullParser.END_DOCUMENT; event = next(parser)) {
            if (event == XmlPullParser.END_TAG && parser.getDepth() == startDepth && parser.getName().equals("c")) break;
            if (event != XmlPullParser.START_TAG) continue;
            if (parser.getName().equals("f")) throw error("A planilha contém fórmulas. Exporte os valores da posição sem fórmulas.");
            if (parser.getName().equals("v")) {
                if (seenValue) throw error("Uma célula contém valores repetidos.");
                value = text(parser); seenValue = true;
            } else if (parser.getName().equals("t")) {
                inline.append(text(parser));
                if (inline.length() > MAX_FIELD) throw error("Uma célula excede 4.096 caracteres.");
            }
        }
        if (type.equals("s")) {
            int index;
            try { index = Integer.parseInt(value); }
            catch (NumberFormatException ex) { throw error("Índice de texto inválido na planilha."); }
            if (index < 0 || index >= shared.size()) throw error("Índice de texto inválido na planilha.");
            return new Cell(shared.get(index), false);
        }
        if (type.equals("inlineStr")) return new Cell(inline.toString(), false);
        return new Cell(value, type.isEmpty() || type.equals("n"));
    }

    private static XmlPullParser xml(byte[] data) {
        String content = decodeUtf8(data);
        String uppercase = content.toUpperCase(Locale.ROOT);
        if (uppercase.contains("<!DOCTYPE") || uppercase.contains("<!ENTITY")) throw error("DTD e entidades externas não são aceitas em planilhas.");
        int nodes = 0;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '<' && ++nodes > MAX_XML_NODES) {
                throw error("A planilha contém XML demais.");
            }
        }
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(new StringReader(content));
            return parser;
        } catch (XmlPullParserException ex) { throw error("Não foi possível ler o XML da planilha."); }
    }

    private static int next(XmlPullParser parser) throws XmlPullParserException {
        try {
            int event = parser.nextToken();
            if (parser.getDepth() > 64) throw error("A estrutura XML da planilha é profunda demais.");
            if (event == XmlPullParser.DOCDECL || event == XmlPullParser.ENTITY_REF && parser.getText() == null) {
                throw error("DTD e entidades desconhecidas não são aceitas em planilhas.");
            }
            return event;
        } catch (IOException ex) { throw error("Não foi possível ler o XML da planilha."); }
    }

    private static String text(XmlPullParser parser) throws XmlPullParserException {
        StringBuilder value = new StringBuilder();
        int depth = parser.getDepth();
        for (int event = next(parser); event != XmlPullParser.END_DOCUMENT; event = next(parser)) {
            if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) return value.toString();
            if (event == XmlPullParser.START_TAG) throw error("Texto de célula com estrutura inválida.");
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF
                    || event == XmlPullParser.IGNORABLE_WHITESPACE) {
                if (parser.getText() != null) value.append(parser.getText());
                if (value.length() > MAX_FIELD) throw error("Uma célula excede 4.096 caracteres.");
            }
        }
        throw error("Texto de célula incompleto na planilha.");
    }

    private static String attribute(XmlPullParser parser, String name) {
        for (int index = 0; index < parser.getAttributeCount(); index++) {
            if (parser.getAttributeName(index).equals(name)) return parser.getAttributeValue(index);
        }
        return "";
    }

    private static int positiveInt(String text) {
        try {
            if (!text.matches("[1-9][0-9]{0,6}")) throw new NumberFormatException();
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) { throw error("Referência de linha inválida na planilha."); }
    }

    private static IllegalArgumentException rowError(int row, String message) {
        return error("Linha " + row + ": " + message);
    }

    private static IllegalArgumentException error(String message) { return new IllegalArgumentException(message); }
}
