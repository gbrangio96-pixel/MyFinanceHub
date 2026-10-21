package br.com.myfinancehub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Optional, read-only market quotes. Provider timestamps are preserved; prices may be delayed. */
public final class InvestmentQuotes {
    public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    public static final int TIMEOUT_MILLIS = 8000;
    private static final String ORIGIN = "https://brapi.dev";
    private final Transport transport;
    private final Clock clock;

    /** Injectable boundary: production follows no redirects and sends the token only as a header. */
    public interface Transport {
        Response get(URI uri, Map<String, String> headers) throws IOException;
    }

    public static final class Response {
        public final int status;
        public final String body, contentType;
        public Response(int status, String body) { this(status, body, "application/json"); }
        public Response(int status, String body, String contentType) {
            this.status = status; this.body = body; this.contentType = contentType;
        }
    }

    public static final class Quote {
        public final String symbol, currency, source;
        public final BigDecimal price;
        public final Instant asOf;
        private Quote(String symbol, BigDecimal price, Instant asOf) {
            this.symbol = symbol; this.currency = "BRL"; this.source = "brapi";
            this.price = price; this.asOf = asOf;
        }
    }

    /** Search identifies assets only: a search result has no timestamped quote. */
    public static final class Asset {
        public final String symbol, name;
        private Asset(String symbol, String name) { this.symbol = symbol; this.name = name; }
    }

    public InvestmentQuotes() { this(new HttpTransport(), Clock.systemUTC()); }
    public InvestmentQuotes(Transport transport, Clock clock) {
        if (transport == null || clock == null) throw new IllegalArgumentException("Serviço de cotações inválido.");
        this.transport = transport; this.clock = clock;
    }

    /** One symbol per request, compatible with the provider's free-tier request limit. */
    public Quote quote(String symbol, String apiKey) throws IOException {
        String requested = normalizeSymbol(symbol);
        if (requested.endsWith("-BRL")) return cryptoQuote(requested, apiKey);
        Map<String, Object> body = request("/api/v2/stocks/quote?symbols=" + requested, apiKey);
        try {
            List<?> results = array(body.get("results"));
            if (results.size() != 1) throw malformed();
            Map<?, ?> result = object(results.get(0));
            if (!requested.equals(result.get("requestedSymbol")) || !requested.equals(result.get("symbol"))
                    || !Boolean.FALSE.equals(result.get("changed")))
                throw failure("O código do ativo mudou ou não corresponde à consulta. Confira o ativo e a quantidade antes de atualizar.");
            return validatedQuote(requested, object(result.get("data")));
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException error) {
            throw malformed();
        }
    }

    /** Active B3 stocks, units, funds and BDRs quoted in BRL; excludes fixed income. */
    public List<Asset> search(String query, String apiKey) throws IOException {
        String search = query == null ? "" : query.trim();
        if (search.isEmpty() || search.length() > 80 || hasControl(search))
            throw new IllegalArgumentException("Busque pelo código ou nome do ativo, com até 80 caracteres.");
        Map<String, Object> body = request("/api/v2/tickers?search=" + URLEncoder.encode(search, "UTF-8") + "&limit=20", apiKey);
        try {
            List<?> results = array(body.get("results"));
            List<Asset> assets = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object candidate : results) {
                Map<?, ?> row = object(candidate);
                if (!"B3".equals(row.get("exchange")) || !"BRL".equals(row.get("currency"))
                        || !Boolean.TRUE.equals(row.get("isActive")) || !supported(row)) continue;
                String symbol = string(row.get("symbol"));
                if (!symbol.matches("[A-Z0-9]{3,12}")) continue;
                String name = string(row.get("longName")).trim();
                if (name.isEmpty()) name = string(row.get("name")).trim();
                if (name.isEmpty() || name.length() > 300 || hasControl(name) || !seen.add(symbol)) continue;
                assets.add(new Asset(symbol, name));
                if (assets.size() == 20) break;
            }
            return Collections.unmodifiableList(assets);
        } catch (IllegalArgumentException error) { throw malformed(); }
    }

    /** Crypto symbols are suffixed with -BRL so they cannot collide with a B3 holding. */
    public List<Asset> searchCrypto(String query, String apiKey) throws IOException {
        String search = query == null ? "" : query.trim();
        if (search.isEmpty() || search.length() > 80 || hasControl(search))
            throw new IllegalArgumentException("Busque pelo código da criptomoeda, com até 80 caracteres.");
        if (search.toUpperCase(Locale.ROOT).endsWith("-BRL")) search = search.substring(0, search.length() - 4);
        if (search.trim().isEmpty())
            throw new IllegalArgumentException("Informe o código da criptomoeda, como BTC ou ETH.");
        Map<String, Object> body = request("/api/v2/crypto/available?search=" + URLEncoder.encode(search, "UTF-8"), apiKey);
        try {
            List<Asset> assets = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object candidate : array(body.get("coins"))) {
                String coin = string(candidate);
                if (!coin.matches("[A-Z0-9]{2,12}") || !seen.add(coin)) continue;
                assets.add(new Asset(coin + "-BRL", coin + " (criptomoeda)"));
                if (assets.size() == 20) break;
            }
            return Collections.unmodifiableList(assets);
        } catch (IllegalArgumentException error) { throw malformed(); }
    }

    private Quote cryptoQuote(String requested, String apiKey) throws IOException {
        String coin = requested.substring(0, requested.length() - 4);
        Map<String, Object> body = request("/api/v2/crypto?coin=" + coin + "&currency=BRL", apiKey);
        try {
            List<?> results = array(body.get("coins"));
            if (results.size() != 1) throw malformed();
            Map<?, ?> data = object(results.get(0));
            if (!coin.equals(data.get("coin")))
                throw failure("A criptomoeda retornada não corresponde à consulta. Nenhum valor foi atualizado.");
            return validatedQuote(requested, data);
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException error) { throw malformed(); }
    }

    private Quote validatedQuote(String requested, Map<?, ?> data) throws IOException {
        if (!"BRL".equals(data.get("currency")))
            throw failure("A cotação deve estar em reais (BRL). Nenhum valor foi atualizado.");
        Object rawPrice = data.get("regularMarketPrice");
        if (!(rawPrice instanceof BigDecimal)) throw malformed();
        BigDecimal price = (BigDecimal) rawPrice;
        if (price.signum() <= 0 || price.precision() > 30 || Math.abs((long) price.scale()) > 12)
            throw malformed();
        Instant asOf = quoteTime(data.get("regularMarketTime"));
        if (!asOf.isAfter(Instant.EPOCH) || asOf.isAfter(clock.instant().plus(Duration.ofMinutes(10))))
            throw failure("A data da cotação é inválida. Nenhum valor foi atualizado.");
        return new Quote(requested, price, asOf);
    }

    private static boolean supported(Map<?, ?> row) {
        String asset = string(row.get("assetType"));
        return "stock".equals(asset) || "fund".equals(asset) || "bdr".equals(asset) || "etf".equals(asset);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String path, String apiKey) throws IOException {
        String token = apiKey == null ? "" : apiKey.trim();
        if (token.length() > 2048 || hasControl(token) || token.indexOf(' ') >= 0)
            throw new IllegalArgumentException("A chave brapi informada é inválida.");
        for (int i = 0; i < token.length(); i++) if (token.charAt(i) < 0x21 || token.charAt(i) > 0x7e)
            throw new IllegalArgumentException("A chave brapi informada é inválida.");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        if (!token.isEmpty()) headers.put("Authorization", "Bearer " + token);
        Response response;
        try { response = transport.get(URI.create(ORIGIN + path), Collections.unmodifiableMap(headers)); }
        catch (IOException | RuntimeException error) {
            throw failure("Não foi possível consultar as cotações. Verifique a conexão e tente novamente.");
        }
        if (response == null) throw malformed();
        if (response.status == 401 || response.status == 403)
            throw failure("Este ativo requer uma chave brapi válida. Sem chave, teste PETR4, VALE3, ITUB4 ou MGLU3.");
        if (response.status == 429)
            throw failure("O limite de consultas da brapi foi atingido. Aguarde antes de tentar novamente.");
        if (response.status != 200)
            throw failure("O serviço de cotações está indisponível para esta consulta. Tente novamente mais tarde.");
        String mime = response.contentType == null ? "" : response.contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!("application/json".equals(mime) || (mime.startsWith("application/") && mime.endsWith("+json")))
                || response.body == null || response.body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES)
            throw malformed();
        try { return (Map<String, Object>) object(new Json(response.body).read()); }
        catch (IllegalArgumentException error) { throw malformed(); }
    }

    private static Instant quoteTime(Object value) throws IOException {
        if (value instanceof String) return Instant.parse((String) value);
        if (value instanceof BigDecimal) return Instant.ofEpochSecond(((BigDecimal) value).longValueExact());
        throw malformed();
    }

    private static String normalizeSymbol(String value) {
        String symbol = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9]{3,12}") && !symbol.matches("[A-Z0-9]{2,12}-BRL"))
            throw new IllegalArgumentException("Informe um código válido, como PETR4, HGLG11 ou BTC-BRL.");
        return symbol;
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static String string(Object value) { return value instanceof String ? (String) value : ""; }
    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException();
        return (Map<?, ?>) value;
    }
    private static List<?> array(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException();
        return (List<?>) value;
    }
    private static IOException malformed() { return failure("O provedor retornou uma cotação inválida. Nenhum valor foi atualizado."); }
    private static IOException failure(String message) { return new IOException(message); }

    private static final class HttpTransport implements Transport {
        @Override public Response get(URI uri, Map<String, String> headers) throws IOException {
            if (!"https".equals(uri.getScheme()) || !"brapi.dev".equals(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443))
                throw new IOException("Destino inválido.");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            try {
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(TIMEOUT_MILLIS); connection.setReadTimeout(TIMEOUT_MILLIS);
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                for (Map.Entry<String, String> header : headers.entrySet())
                    connection.setRequestProperty(header.getKey(), header.getValue());
                int status = connection.getResponseCode();
                if (status != 200) return new Response(status, "", connection.getContentType());
                if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) throw new IOException("Resposta muito grande.");
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Resposta muito grande.");
                        bytes.write(buffer, 0, count);
                    }
                    return new Response(status, new String(bytes.toByteArray(), StandardCharsets.UTF_8), connection.getContentType());
                }
            } finally { connection.disconnect(); }
        }
    }

    /** Small bounded JSON reader: numeric tokens remain BigDecimal, never binary floating point. */
    private static final class Json {
        private final String input;
        private int offset;
        Json(String input) { this.input = input; }
        Object read() {
            whitespace(); Object value = value(0); whitespace();
            if (offset != input.length()) throw new IllegalArgumentException();
            return value;
        }
        private Object value(int depth) {
            if (depth > 32 || offset >= input.length()) throw new IllegalArgumentException();
            char first = input.charAt(offset);
            if (first == '{') {
                offset++; whitespace(); Map<String, Object> result = new LinkedHashMap<>();
                if (take('}')) return result;
                do {
                    if (offset >= input.length() || input.charAt(offset) != '"') throw new IllegalArgumentException();
                    String key = string(); whitespace(); require(':'); whitespace();
                    if (result.containsKey(key)) throw new IllegalArgumentException();
                    result.put(key, value(depth + 1)); whitespace();
                    if (take('}')) return result;
                    require(','); whitespace();
                } while (true);
            }
            if (first == '[') {
                offset++; whitespace(); List<Object> result = new ArrayList<>();
                if (take(']')) return result;
                do {
                    result.add(value(depth + 1)); whitespace();
                    if (take(']')) return result;
                    require(','); whitespace();
                } while (true);
            }
            if (first == '"') return string();
            if (first == 't') { literal("true"); return Boolean.TRUE; }
            if (first == 'f') { literal("false"); return Boolean.FALSE; }
            if (first == 'n') { literal("null"); return null; }
            int start = offset;
            while (offset < input.length() && "0123456789-+.eE".indexOf(input.charAt(offset)) >= 0) offset++;
            String number = input.substring(start, offset);
            if (number.length() > 128 || !number.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))
                throw new IllegalArgumentException();
            return new BigDecimal(number);
        }
        private String string() {
            require('"'); StringBuilder out = new StringBuilder();
            while (offset < input.length()) {
                char character = input.charAt(offset++);
                if (character == '"') return out.toString();
                if (character < 0x20) throw new IllegalArgumentException();
                if (character != '\\') { out.append(character); continue; }
                if (offset >= input.length()) throw new IllegalArgumentException();
                char escaped = input.charAt(offset++);
                switch (escaped) {
                    case '"': case '\\': case '/': out.append(escaped); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (offset + 4 > input.length()) throw new IllegalArgumentException();
                        String hex = input.substring(offset, offset + 4);
                        if (!hex.matches("[0-9a-fA-F]{4}")) throw new IllegalArgumentException();
                        out.append((char) Integer.parseInt(hex, 16)); offset += 4; break;
                    default: throw new IllegalArgumentException();
                }
            }
            throw new IllegalArgumentException();
        }
        private void literal(String value) {
            if (!input.startsWith(value, offset)) throw new IllegalArgumentException();
            offset += value.length();
        }
        private void whitespace() {
            while (offset < input.length() && " \t\r\n".indexOf(input.charAt(offset)) >= 0) offset++;
        }
        private boolean take(char value) {
            if (offset < input.length() && input.charAt(offset) == value) { offset++; return true; }
            return false;
        }
        private void require(char value) { if (!take(value)) throw new IllegalArgumentException(); }
    }
}
