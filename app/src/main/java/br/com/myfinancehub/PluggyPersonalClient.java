package br.com.myfinancehub;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Personal, read-only Meu Pluggy access. Secrets are provided by the private device vault. */
public final class PluggyPersonalClient {
    public static final String API_ORIGIN = "https://api.pluggy.ai";
    public static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 8 * 1024 * 1024;
    private static final int MAX_REQUESTS = 40, MAX_PAGES = 20, MAX_ACCOUNTS = 100, MAX_ROWS = 5000;
    private static final int REQUEST_TIMEOUT = 15_000, SNAPSHOT_TIMEOUT = 45_000;
    private static final long MAX_CENTS = 9_007_199_254_740_991L;
    private static final Set<String> ITEM_STATUSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "CREATED", "UPDATED", "UPDATING", "WAITING_USER_INPUT", "LOGIN_ERROR", "OUTDATED", "ERROR")));

    public interface Transport {
        Response request(String method, URI uri, Map<String, String> headers, String body) throws IOException;
        default Response request(String method, URI uri, Map<String, String> headers, String body,
                                 int timeoutMillis) throws IOException {
            return request(method, uri, headers, body);
        }
    }
    public static final class Response {
        public final int status;
        public final String body, contentType;
        public Response(int status, String body) { this(status, body, "application/json"); }
        public Response(int status, String body, String contentType) {
            this.status = status; this.body = body; this.contentType = contentType;
        }
    }
    public static final class Failure extends IOException {
        public final String code;
        Failure(String code) { super(message(code)); this.code = code; }
    }
    private final Transport transport;
    private final Clock clock;
    public PluggyPersonalClient() { this(new HttpTransport(), Clock.systemUTC()); }
    public PluggyPersonalClient(Transport transport, Clock clock) {
        if (transport == null || clock == null) throw new IllegalArgumentException("Serviço Meu Pluggy inválido.");
        this.transport = transport; this.clock = clock;
    }

    /** Explicit item IDs are required: the personal API does not list all items. */
    public static List<String> itemIds(String text) {
        if (text == null || text.length() > 1024) throw new IllegalArgumentException(message("INVALID_CONFIG"));
        String trimmed = text.trim();
        List<String> result = trimmed.isEmpty() ? Collections.emptyList()
                : Arrays.asList(trimmed.split("[\\s,;]+"));
        validateItems(result);
        return Collections.unmodifiableList(new ArrayList<>(result));
    }
    public static void validateCredentials(String clientId, String clientSecret, List<String> itemIds) {
        if (!credential(clientId) || clientId.length() > 256 || !credential(clientSecret))
            throw new IllegalArgumentException(message("INVALID_CONFIG"));
        validateItems(itemIds);
    }
    private static void validateItems(List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty() || itemIds.size() > 5)
            throw new IllegalArgumentException(message("INVALID_CONFIG"));
        Set<String> seen = new HashSet<>();
        for (String id : itemIds) if (!safeId(id) || !seen.add(id))
            throw new IllegalArgumentException(message("INVALID_CONFIG"));
    }
    private static boolean credential(String value) {
        return value != null && !value.isEmpty() && value.length() <= 4096 && value.equals(value.trim())
                && !value.matches("(?s).*[\\x00-\\x1f\\x7f].*");
    }
    private static boolean safeId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }

    /** All pages are normalized in memory. No partial snapshot or financial write is returned. */
    public PluggyClient.Snapshot fetch(String clientId, String clientSecret, List<String> itemIds,
                                      LocalDate from, LocalDate to) throws IOException {
        validateCredentials(clientId, clientSecret, itemIds);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("America/Sao_Paulo")));
        if (from == null || to == null || from.isAfter(to) || to.isAfter(today)
                || from.getYear() < 1 || to.getYear() > 9999 || ChronoUnit.DAYS.between(from, to) >= 90)
            throw new IllegalArgumentException(message("INVALID_RANGE"));
        List<String> items = new ArrayList<>(itemIds);
        Session session = new Session();
        try {
            JSONObject authBody = new JSONObject().put("clientId", clientId).put("clientSecret", clientSecret);
            JSONObject auth = session.request("/auth", authBody);
            String apiKey = string(auth, "apiKey", 8192, false);
            if (apiKey.matches("(?s).*[\\s\\x00-\\x1f\\x7f].*")) throw fail("INVALID_PROVIDER_DATA");
            session.apiKey = apiKey;
            JSONArray connections = new JSONArray();
            Map<String, JSONObject> accounts = new LinkedHashMap<>(), transactions = new LinkedHashMap<>();
            Map<String, String> rawTransactions = new LinkedHashMap<>();
            int accountRows = 0, transactionRows = 0;
            for (String itemId : items) {
                JSONObject item = session.request("/items/" + encode(itemId), null);
                if (!itemId.equals(string(item, "id", 128, false))) throw fail("INVALID_PROVIDER_DATA");
                String itemStatus = string(item, "status", 128, false);
                connections.put(new JSONObject().put("externalId", itemId)
                        .put("status", ITEM_STATUSES.contains(itemStatus) ? itemStatus : "UNKNOWN")
                        .put("providerUpdatedAt", timestamp(item.opt("lastUpdatedAt"))));
                JSONObject accountPage = session.request("/accounts?itemId=" + encode(itemId), null);
                JSONArray rawAccounts = accountPage.getJSONArray("results");
                if (accountPage.has("totalPages") && (!integer(accountPage.get("totalPages"))
                        || accountPage.getInt("totalPages") < 0 || accountPage.getInt("totalPages") > 1)
                        || accountPage.has("total") && (!integer(accountPage.get("total"))
                        || accountPage.getInt("total") != rawAccounts.length())
                        || accountPage.has("next") && !accountPage.isNull("next"))
                    throw fail("INVALID_PROVIDER_DATA");
                accountRows += rawAccounts.length();
                if (accountRows > MAX_ACCOUNTS) throw fail("REQUEST_LIMIT");
                for (int a = 0; a < rawAccounts.length(); a++) {
                    session.remaining();
                    JSONObject rawAccount = rawAccounts.getJSONObject(a);
                    String accountId = string(rawAccount, "id", 128, false);
                    if (!safeId(accountId)) throw fail("INVALID_PROVIDER_DATA");
                    JSONObject account = normalizeAccount(rawAccount, itemId);
                    String identity = tuple(itemId, accountId);
                    JSONObject previousAccount = accounts.get(identity);
                    if (previousAccount != null) {
                        if (!previousAccount.toString().equals(account.toString())) throw fail("INVALID_PROVIDER_DATA");
                        continue;
                    }
                    accounts.put(identity, account);
                    String query = "?accountId=" + encode(accountId) + "&dateFrom=" + from + "&dateTo=" + to;
                    Set<String> seenQueries = new HashSet<>();
                    int page = 0;
                    while (query != null) {
                        if (++page > MAX_PAGES) throw fail("REQUEST_LIMIT");
                        if (!seenQueries.add(query)) throw fail("INVALID_PROVIDER_DATA");
                        JSONObject transactionPage = session.request("/v2/transactions" + query, null);
                        JSONArray rawRows = transactionPage.getJSONArray("results");
                        if (!transactionPage.has("next")) throw fail("INVALID_PROVIDER_DATA");
                        transactionRows += rawRows.length();
                        if (transactionRows > MAX_ROWS) throw fail("REQUEST_LIMIT");
                        for (int r = 0; r < rawRows.length(); r++) {
                            session.remaining();
                            JSONObject raw = rawRows.getJSONObject(r);
                            String rawId = string(raw, "id", 128, false);
                            if (!safeId(rawId)) throw fail("INVALID_PROVIDER_DATA");
                            JSONObject row = normalizeTransaction(raw, account);
                            String date = row.getString("date");
                            if (date.compareTo(from.toString()) < 0 || date.compareTo(to.toString()) > 0)
                                throw fail("INVALID_PROVIDER_DATA");
                            String rawIdentity = tuple(itemId, accountId, rawId);
                            String priorRaw = rawTransactions.put(rawIdentity, row.toString());
                            if (priorRaw != null && !priorRaw.equals(row.toString())) throw fail("INVALID_PROVIDER_DATA");
                            String source = row.getString("sourceKey");
                            JSONObject priorRow = transactions.get(source);
                            if (priorRow == null) transactions.put(source, row);
                            else if (!comparable(priorRow).equals(comparable(row))) throw fail("INVALID_PROVIDER_DATA");
                        }
                        query = transactionPage.isNull("next") ? null
                                : nextQuery(string(transactionPage, "next", 8192, false), accountId, from, to);
                    }
                }
            }
            session.remaining();
            JSONObject snapshot = new JSONObject().put("schemaVersion", 1).put("provider", "pluggy")
                    .put("observedAt", clock.instant().toString())
                    .put("range", new JSONObject().put("from", from.toString()).put("to", to.toString()))
                    .put("connections", connections).put("accounts", new JSONArray(accounts.values()))
                    .put("transactions", new JSONArray(transactions.values()));
            return PluggyClient.parse(snapshot.toString(), from, to);
        } catch (Failure failure) { throw failure; }
        catch (JSONException | IllegalArgumentException | ArithmeticException error) { throw fail("INVALID_PROVIDER_DATA"); }
    }

    private final class Session {
        final long startedAt = System.nanoTime();
        int requests, bytes;
        String apiKey;
        int remaining() throws Failure {
            long left = SNAPSHOT_TIMEOUT - (System.nanoTime() - startedAt) / 1_000_000L;
            if (Thread.currentThread().isInterrupted() || left <= 0) throw fail("REQUEST_LIMIT");
            return (int) left;
        }
        JSONObject request(String path, JSONObject authBody) throws IOException, JSONException {
            int timeout = Math.min(REQUEST_TIMEOUT, remaining());
            if (++requests > MAX_REQUESTS) throw fail("REQUEST_LIMIT");
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            if (authBody != null) headers.put("Content-Type", "application/json");
            else headers.put("X-API-KEY", apiKey);
            Response response;
            try {
                response = transport.request(authBody == null ? "GET" : "POST", URI.create(API_ORIGIN + path),
                        Collections.unmodifiableMap(headers), authBody == null ? null : authBody.toString(), timeout);
            } catch (IOException | RuntimeException error) { throw fail("PROVIDER_UNAVAILABLE"); }
            remaining();
            if (response == null || response.status < 200 || response.status >= 300) throw fail("PROVIDER_UNAVAILABLE");
            if (response.body == null || response.contentType == null
                    || !response.contentType.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?"))
                throw fail("INVALID_PROVIDER_DATA");
            int count = response.body.getBytes(StandardCharsets.UTF_8).length;
            if (count > MAX_RESPONSE_BYTES) throw fail("INVALID_PROVIDER_DATA");
            bytes += count;
            if (bytes > MAX_TOTAL_BYTES) throw fail("REQUEST_LIMIT");
            org.json.JSONTokener reader = new org.json.JSONTokener(response.body);
            Object value = reader.nextValue();
            if (!(value instanceof JSONObject) || reader.nextClean() != 0) throw fail("INVALID_PROVIDER_DATA");
            return (JSONObject) value;
        }
    }

    private static JSONObject normalizeAccount(JSONObject raw, String itemId) throws JSONException, Failure {
        if (raw.has("itemId") && !itemId.equals(raw.get("itemId"))) throw fail("INVALID_PROVIDER_DATA");
        String kind = one(raw, "type", "BANK", "CREDIT");
        if (!"BRL".equals(raw.get("currencyCode"))) throw fail("INVALID_PROVIDER_DATA");
        return new JSONObject().put("externalId", id(raw, "id")).put("itemId", itemId)
                .put("name", string(raw, "name", 80, true)).put("kind", kind).put("currency", "BRL")
                .put("reportedBalanceCents", money(raw.get("balance"), false));
    }
    private static JSONObject normalizeTransaction(JSONObject raw, JSONObject account) throws JSONException, Failure {
        String externalId = id(raw, "id"), accountId = account.getString("externalId"), itemId = account.getString("itemId");
        if (!accountId.equals(id(raw, "accountId")) || raw.has("itemId") && !itemId.equals(raw.get("itemId"))
                || !"BRL".equals(raw.get("currencyCode"))) throw fail("INVALID_PROVIDER_DATA");
        String type = one(raw, "type", "CREDIT", "DEBIT"), status = one(raw, "status", "POSTED", "PENDING");
        String providerId = !raw.has("providerId") || raw.isNull("providerId") ? null : id(raw, "providerId");
        String identityKind = providerId == null ? "id" : "providerId";
        String sourceDate = string(raw, "date", 35, false), date = calendarDate(sourceDate);
        JSONObject payment = raw.optJSONObject("paymentData");
        boolean pix = "PIX".equals(raw.opt("operationType")) || payment != null && "PIX".equals(payment.opt("paymentMethod"));
        return new JSONObject().put("externalId", externalId).put("externalAccountId", accountId).put("itemId", itemId)
                .put("providerTransactionId", providerId == null ? JSONObject.NULL : providerId).put("identityKind", identityKind)
                .put("sourceKey", hash(tuple(itemId, accountId, identityKind, providerId == null ? externalId : providerId)))
                .put("direction", type.equals("CREDIT") ? "income" : "expense").put("status", status)
                .put("amountCents", money(raw.get("amount"), true)).put("date", date).put("sourceDate", sourceDate)
                .put("description", string(raw, "description", 500, true)).put("method", pix ? "pix" : "other");
    }
    private static String comparable(JSONObject row) throws JSONException {
        JSONObject copy = new JSONObject(row.toString());
        if ("providerId".equals(copy.get("identityKind"))) copy.remove("externalId");
        return copy.toString();
    }
    private static String money(Object raw, boolean positive) throws Failure {
        String value;
        if (raw instanceof Number) {
            double number = ((Number) raw).doubleValue();
            if (!Double.isFinite(number) || Math.abs(number) >= 70_368_744_177_664d) throw fail("INVALID_PROVIDER_DATA");
            try { value = new BigDecimal(raw.toString()).toPlainString(); }
            catch (NumberFormatException error) { throw fail("INVALID_PROVIDER_DATA"); }
        } else if (raw instanceof String) value = (String) raw;
        else throw fail("INVALID_PROVIDER_DATA");
        if (value.length() > 24 || !value.matches("-?(0|[1-9][0-9]*)(?:\\.[0-9]{1,2})?")) throw fail("INVALID_PROVIDER_DATA");
        try {
            long cents = new BigDecimal(value).movePointRight(2).longValueExact();
            if (cents < -MAX_CENTS || cents > MAX_CENTS || positive && cents == 0) throw fail("INVALID_PROVIDER_DATA");
            return Long.toString(positive ? Math.abs(cents) : cents);
        } catch (ArithmeticException error) { throw fail("INVALID_PROVIDER_DATA"); }
    }
    private static String calendarDate(String value) throws Failure {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,3})?(?:Z|[+-][0-9]{2}:[0-9]{2}))?"))
            throw fail("INVALID_PROVIDER_DATA");
        try {
            PluggyClient.day(value.substring(0, 10));
            if (value.length() > 10 && Math.abs(OffsetDateTime.parse(value).getOffset().getTotalSeconds()) > 14 * 3600)
                throw fail("INVALID_PROVIDER_DATA");
            return value.substring(0, 10);
        } catch (IllegalArgumentException | java.time.DateTimeException error) { throw fail("INVALID_PROVIDER_DATA"); }
    }
    private static Object timestamp(Object value) {
        if (!(value instanceof String)) return JSONObject.NULL;
        String text = (String) value;
        try {
            if (text.length() > 64 || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})")) return JSONObject.NULL;
            PluggyClient.day(text.substring(0, 10));
            OffsetDateTime.parse(text);
            return text;
        } catch (RuntimeException error) { return JSONObject.NULL; }
    }
    static String nextQuery(String next, String accountId, LocalDate from, LocalDate to) throws Failure {
        if (next == null || next.length() < 2 || next.length() > 8192 || !next.startsWith("?")
                || next.matches("(?s).*[\\s#\\\\].*")) throw fail("INVALID_PROVIDER_DATA");
        Map<String, String> params = new LinkedHashMap<>();
        try {
            for (String pair : next.substring(1).split("&", -1)) {
                int equal = pair.indexOf('=');
                String key = URLDecoder.decode(equal < 0 ? pair : pair.substring(0, equal), "UTF-8");
                String value = URLDecoder.decode(equal < 0 ? "" : pair.substring(equal + 1), "UTF-8");
                if (!Arrays.asList("accountId", "after", "dateFrom", "dateTo").contains(key) || value.isEmpty()
                        || params.put(key, value) != null) throw fail("INVALID_PROVIDER_DATA");
            }
            if (!accountId.equals(params.get("accountId")) || !params.containsKey("after")
                    || params.containsKey("dateFrom") && !from.toString().equals(params.get("dateFrom"))
                    || params.containsKey("dateTo") && !to.toString().equals(params.get("dateTo"))) throw fail("INVALID_PROVIDER_DATA");
            params.put("dateFrom", from.toString()); params.put("dateTo", to.toString());
            StringBuilder result = new StringBuilder("?");
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (result.length() > 1) result.append('&');
                result.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
            return result.toString();
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException error) { throw fail("INVALID_PROVIDER_DATA"); }
    }
    private static String string(JSONObject object, String key, int max, boolean trim) throws JSONException, Failure {
        Object raw = object.get(key);
        if (!(raw instanceof String)) throw fail("INVALID_PROVIDER_DATA");
        String value = (String) raw;
        if (value.length() > max || value.matches("(?s).*[\\x00-\\x1f\\x7f-\\x9f].*")) throw fail("INVALID_PROVIDER_DATA");
        if (trim) value = value.trim();
        if (value.isEmpty()) throw fail("INVALID_PROVIDER_DATA");
        return value;
    }
    private static String id(JSONObject object, String key) throws JSONException, Failure {
        String value = string(object, key, 200, false);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")) throw fail("INVALID_PROVIDER_DATA");
        return value;
    }
    private static String one(JSONObject object, String key, String a, String b) throws JSONException, Failure {
        String value = string(object, key, 32, false);
        if (!value.equals(a) && !value.equals(b)) throw fail("INVALID_PROVIDER_DATA");
        return value;
    }
    private static boolean integer(Object value) {
        return value instanceof Number && Double.isFinite(((Number) value).doubleValue())
                && ((Number) value).doubleValue() == ((Number) value).longValue();
    }
    private static String tuple(String... values) {
        JSONArray array = new JSONArray(); for (String value : values) array.put(value); return array.toString();
    }
    private static String hash(String value) throws Failure {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("pluggy:");
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw fail("INVALID_PROVIDER_DATA"); }
    }
    private static String encode(String value) { try { return URLEncoder.encode(value, "UTF-8"); } catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); } }
    private static Failure fail(String code) { return new Failure(code); }
    private static String message(String code) {
        switch (code) {
            case "INVALID_CONFIG": return "Meu Pluggy: confira Client ID, Client Secret e de 1 a 5 itemIds próprios, sem repetir conexões.";
            case "INVALID_RANGE": return "Meu Pluggy: escolha um período de até 90 dias, sem data final futura.";
            case "REQUEST_LIMIT": return "Meu Pluggy: limite seguro da consulta excedido. Reduza o período ou as conexões.";
            case "INVALID_PROVIDER_DATA": return "Meu Pluggy: dados inconsistentes ou incompletos. Nenhuma consulta parcial foi aplicada.";
            default: return "Meu Pluggy: não foi possível consultar. Confira suas credenciais, autorizações e conexão e tente novamente.";
        }
    }
    private static final class HttpTransport implements Transport {
        public Response request(String method, URI uri, Map<String, String> headers, String body) throws IOException {
            return request(method, uri, headers, body, REQUEST_TIMEOUT);
        }
        public Response request(String method, URI uri, Map<String, String> headers, String body, int timeoutMillis) throws IOException {
            if (!"https".equals(uri.getScheme()) || !"api.pluggy.ai".equals(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null)
                throw new IOException();
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            try {
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                connection.setConnectTimeout(timeoutMillis); connection.setReadTimeout(timeoutMillis);
                connection.setRequestMethod(method);
                for (Map.Entry<String, String> entry : headers.entrySet()) connection.setRequestProperty(entry.getKey(), entry.getValue());
                if (body != null) {
                    byte[] data = body.getBytes(StandardCharsets.UTF_8); connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(data.length);
                    try (OutputStream output = connection.getOutputStream()) { output.write(data); }
                }
                int remaining = (int) Math.max(1, (deadline - System.nanoTime()) / 1_000_000L);
                connection.setReadTimeout(remaining);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) return new Response(status, "");
                String rawLength = connection.getHeaderField("Content-Length");
                if (rawLength != null && !rawLength.matches("[0-9]+")) throw new IOException();
                long length = connection.getContentLengthLong();
                if (length > MAX_RESPONSE_BYTES) throw new IOException();
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192]; int read;
                    while (true) {
                        long left = (deadline - System.nanoTime()) / 1_000_000L;
                        if (Thread.currentThread().isInterrupted() || left <= 0) throw new IOException();
                        connection.setReadTimeout((int) Math.max(1, left));
                        read = input.read(chunk); if (read == -1) break;
                        if (bytes.size() + read > MAX_RESPONSE_BYTES) throw new IOException(); bytes.write(chunk, 0, read);
                    }
                    String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                    return new Response(status, text, connection.getContentType());
                }
            } finally { connection.disconnect(); }
        }
    }
}
