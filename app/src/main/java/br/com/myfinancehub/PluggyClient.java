package br.com.myfinancehub;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Read-only client for this project's private backend, never the provider's credential API. */
public final class PluggyClient {
    public static final int MAX_BYTES=8*1024*1024;
    public interface Transport { Reply get(URI uri,String token)throws IOException; }
    public static final class Reply {
        final int status;final String body,contentType;
        public Reply(int status,String body){this(status,body,"application/json");}
        public Reply(int status,String body,String contentType){this.status=status;this.body=body;this.contentType=contentType;}
    }
    public static final class Account {
        public final String id,itemId,name,kind;public final long balance;
        Account(JSONObject o)throws JSONException {id=str(o,"externalId",200);itemId=str(o,"itemId",128);name=str(o,"name",80);kind=one(o,"kind","BANK","CREDIT");if(!"BRL".equals(str(o,"currency",3)))throw invalid();balance=cents(o,"reportedBalanceCents",false);}
    }
    public static final class Transaction {
        public final String sourceKey,id,accountId,itemId,description,date,status,direction,method;
        public final long amount;
        Transaction(JSONObject o)throws JSONException {
            id=str(o,"externalId",200);accountId=str(o,"externalAccountId",200);itemId=str(o,"itemId",128);
            sourceKey=str(o,"sourceKey",71);if(!sourceKey.matches("pluggy:[0-9a-f]{64}"))throw invalid();
            String identity=one(o,"identityKind","id","providerId");Object providerId=o.get("providerTransactionId");String identityValue=id;
            if(identity.equals("providerId")){identityValue=str(o,"providerTransactionId",200);}else if(providerId!=JSONObject.NULL)throw invalid();
            try {String tuple=new JSONArray().put(itemId).put(accountId).put(identity).put(identityValue).toString();byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(tuple.getBytes(StandardCharsets.UTF_8));StringBuilder hash=new StringBuilder("pluggy:");for(byte b:digest)hash.append(String.format(Locale.ROOT,"%02x",b&255));if(!sourceKey.equals(hash.toString()))throw invalid();}catch(java.security.NoSuchAlgorithmException ex){throw invalid();}
            description=str(o,"description",500);date=day(str(o,"date",10)).toString();
            String sourceDate=str(o,"sourceDate",35);if(!sourceDate.startsWith(date))throw invalid();
            if(!sourceDate.equals(date))OffsetDateTime.parse(sourceDate);
            status=one(o,"status","POSTED","PENDING");direction=one(o,"direction","income","expense");method=one(o,"method","pix","other");amount=cents(o,"amountCents",true);
        }
    }
    public static final class Snapshot {
        public final String observedAt,from,to;public final List<Account> accounts;public final List<Transaction> transactions;public final List<String> connections;
        Snapshot(JSONObject root,LocalDate from,LocalDate to)throws JSONException {
            if(!(root.get("schemaVersion") instanceof Number)||root.getDouble("schemaVersion")!=1||!"pluggy".equals(root.get("provider")))throw invalid();
            observedAt=str(root,"observedAt",40);Instant.parse(observedAt);this.from=from.toString();this.to=to.toString();
            JSONObject range=root.getJSONObject("range");if(!this.from.equals(str(range,"from",10))||!this.to.equals(str(range,"to",10)))throw invalid();
            JSONArray cs=root.getJSONArray("connections"),as=root.getJSONArray("accounts"),ts=root.getJSONArray("transactions");
            if(cs.length()>5||cs.length()==0||as.length()>100||ts.length()>5000)throw invalid();
            Set<String> items=new HashSet<>();List<String> connectionList=new ArrayList<>();
            for(int i=0;i<cs.length();i++){JSONObject c=cs.getJSONObject(i);String id=str(c,"externalId",128);if(!items.add(id))throw invalid();String updated=c.isNull("providerUpdatedAt")?"não informado":str(c,"providerUpdatedAt",40);if(!updated.equals("não informado"))Instant.parse(updated);connectionList.add(str(c,"status",80)+" · atualização do provedor: "+updated);}
            Map<String,Account> accountMap=new LinkedHashMap<>();
            for(int i=0;i<as.length();i++){Account a=new Account(as.getJSONObject(i));if(!items.contains(a.itemId)||accountMap.put(a.itemId+"\0"+a.id,a)!=null)throw invalid();}
            List<Transaction> rows=new ArrayList<>();Set<String> keys=new HashSet<>(),ids=new HashSet<>();
            for(int i=0;i<ts.length();i++){Transaction t=new Transaction(ts.getJSONObject(i));Account a=accountMap.get(t.itemId+"\0"+t.accountId);LocalDate date=day(t.date);if(a==null||!a.itemId.equals(t.itemId)||date.isBefore(from)||date.isAfter(to)||!keys.add(t.sourceKey)||!ids.add(t.itemId+"\0"+t.accountId+"\0"+t.id))throw invalid();rows.add(t);}
            accounts=Collections.unmodifiableList(new ArrayList<>(accountMap.values()));transactions=Collections.unmodifiableList(rows);connections=Collections.unmodifiableList(connectionList);
        }
        public Account account(Transaction t){for(Account a:accounts)if(a.id.equals(t.accountId)&&a.itemId.equals(t.itemId))return a;throw invalid();}
    }
    private final Transport transport;private final Clock clock;
    public PluggyClient(){this(new HttpTransport(),Clock.systemUTC());}
    public PluggyClient(Transport transport,Clock clock){this.transport=transport;this.clock=clock;}
    public static String endpoint(String value) {
        try {URI uri=new URI(value==null?"":value.trim());if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!(uri.getRawPath().isEmpty()||uri.getRawPath().equals("/"))||uri.getPort()>65535||uri.getPort()==0)throw invalid();return new URI("https",null,uri.getHost().toLowerCase(Locale.ROOT),uri.getPort(),null,null,null).toASCIIString();}
        catch(Exception ex){throw new IllegalArgumentException("Informe somente a origem HTTPS do backend, sem caminho, usuário, parâmetros ou chave.");}
    }
    public static String token(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{32,128}"))throw new IllegalArgumentException("Informe o token de acesso do app configurado no backend (32 a 128 caracteres).");return value;}
    public Snapshot fetch(String origin,String accessToken,LocalDate from,LocalDate to)throws IOException {
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("America/Sao_Paulo")));
        if(from==null||to==null||to.isBefore(from)||to.isAfter(today)||ChronoUnit.DAYS.between(from,to)>=90)throw new IllegalArgumentException("Escolha um período de até 90 dias, sem data final futura.");
        URI uri=URI.create(endpoint(origin)+"/v1/snapshot?from="+from+"&to="+to);String validToken=token(accessToken);
        Reply reply;
        try {reply=transport.get(uri,validToken);}catch(IOException ex){throw new IOException("Meu Pluggy: consulta não concluída. Confira a conexão e tente novamente.");}
        if(reply==null)throw new IOException("Meu Pluggy: resposta vazia.");
        if(reply.status!=200)throw new IOException(error(reply.status));
        if(reply.body==null||reply.body.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES||reply.contentType==null||!reply.contentType.toLowerCase(Locale.ROOT).matches("application/json(?:\\s*;.*)?"))throw new IOException("Meu Pluggy: resposta inválida ou grande demais.");
        return parse(reply.body,from,to);
    }
    static Snapshot parse(String body,LocalDate from,LocalDate to)throws IOException {
        try{return new Snapshot(new JSONObject(body),from,to);}catch(Exception ex){throw new IOException("Meu Pluggy: resposta inválida ou incompleta. Nenhum lançamento foi salvo.");}
    }
    static String error(int status){return "Meu Pluggy: "+(status==401?"token recusado. Confira o token privado do app.":status==503?"backend sem configuração. Confira os secrets na hospedagem.":status==400?"período ou endereço inválido.":status==502?"provedor indisponível ou limite excedido. Reduza o período e confira a autorização no Meu Pluggy.":status>=300&&status<400?"redirecionamento recusado. Use a URL HTTPS final do backend.":"consulta indisponível. Tente novamente mais tarde.");}
    static String str(JSONObject o,String k,int max)throws JSONException {Object v=o.get(k);if(!(v instanceof String)||((String)v).trim().isEmpty()||((String)v).length()>max||((String)v).matches("(?s).*[\\x00-\\x1F\\x7F].*"))throw invalid();return (String)v;}
    static String one(JSONObject o,String k,String a,String b)throws JSONException {String v=str(o,k,20);if(!v.equals(a)&&!v.equals(b))throw invalid();return v;}
    static LocalDate day(String v){if(!v.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||v.startsWith("0000"))throw invalid();return LocalDate.parse(v);}
    static long cents(JSONObject o,String k,boolean positive)throws JSONException {String s=str(o,k,24);if(!s.matches(positive?"[1-9][0-9]*":"-?(0|[1-9][0-9]*)"))throw invalid();long n=Long.parseLong(s);if(n < -9_007_199_254_740_991L || n > 9_007_199_254_740_991L)throw invalid();return n;}
    static IllegalArgumentException invalid(){return new IllegalArgumentException("Resposta Meu Pluggy inválida.");}
    private static final class HttpTransport implements Transport {
        public Reply get(URI uri,String token)throws IOException {
            HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(55000);c.setRequestMethod("GET");c.setUseCaches(false);c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Accept","application/json");
            long deadline=System.nanoTime()+60_000_000_000L;
            try{int status=c.getResponseCode();if(status!=200)return new Reply(status,"");if(c.getContentLengthLong()>MAX_BYTES)throw new IOException();try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline||out.size()+n>MAX_BYTES)throw new IOException();out.write(bytes,0,n);}return new Reply(status,out.toString("UTF-8"),c.getContentType());}}
            finally{c.disconnect();}
        }
    }
}
