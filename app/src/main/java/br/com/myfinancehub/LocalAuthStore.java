package br.com.myfinancehub;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Local credentials only. Passwords and the recovery code never enter financial backups. */
public final class LocalAuthStore {
    private static final String PREFERENCES="local_auth";
    private static final int ITERATIONS=210_000;
    private static final Object LOCK=new Object();
    private final SharedPreferences preferences;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();

    public LocalAuthStore(Context context){this(context,Clock.systemUTC());}
    LocalAuthStore(Context context,Clock clock){
        preferences=context.getApplicationContext().getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
        this.clock=clock;
    }

    /** An existing but damaged configuration remains locked rather than being replaced silently. */
    public boolean configured(){return preferences.contains("username");}
    public String username(){return preferences.getString("username","");}

    /** Returns the recovery code once; only its salted derivative is retained. */
    public String create(String username,char[] password)throws GeneralSecurityException {
        String normalized=normalizeUsername(username);
        PasswordPolicy.validate(password);
        synchronized(LOCK){
            if(configured())throw new IllegalStateException("Já existe uma conta local neste aparelho. Entre ou use seu código de recuperação.");
            byte[] salt=salt(),recoverySalt=salt();
            String recoveryCode=recoveryCode();
            byte[] hash=derive(password,salt),recoveryHash=derive(normalizeRecovery(recoveryCode).toCharArray(),recoverySalt);
            try {
                write(preferences.edit().putInt("version",1).putString("username",normalized)
                        .putString("password_salt",encode(salt)).putString("password_hash",encode(hash))
                        .putString("recovery_salt",encode(recoverySalt)).putString("recovery_hash",encode(recoveryHash))
                        .remove("failures").remove("blocked_until"));
                return recoveryCode;
            }finally{Arrays.fill(hash,(byte)0);Arrays.fill(recoveryHash,(byte)0);}
        }
    }

    /** Wrong credentials return false. A persisted temporary lock requires waiting before retry. */
    public boolean verify(String username,char[] password)throws GeneralSecurityException {
        synchronized(LOCK){
            requireConfigured();checkLock();
            String normalized="";
            try{normalized=normalizeUsername(username);}catch(IllegalArgumentException ignored){/* Failed login. */}
            boolean passwordMatches=matches(password,"password_salt","password_hash");
            if(passwordMatches&&this.username().equals(normalized)){
                clearFailures();return true;
            }
            failedAttempt();return false;
        }
    }

    /** A password change leaves the separately saved recovery code valid. */
    public void changePassword(char[] current,char[] replacement)throws GeneralSecurityException {
        PasswordPolicy.validate(replacement);
        synchronized(LOCK){
            requireConfigured();checkLock();
            if(!matches(current,"password_salt","password_hash")){
                failedAttempt();throw new IllegalArgumentException("Senha atual incorreta.");
            }
            byte[] salt=salt(),hash=derive(replacement,salt);
            try{write(preferences.edit().putString("password_salt",encode(salt)).putString("password_hash",encode(hash))
                    .remove("failures").remove("blocked_until"));}
            finally{Arrays.fill(hash,(byte)0);}
        }
    }

    /** A successful reset invalidates the previous password and recovery code. */
    public String resetPassword(String username,String recoveryCode,char[] replacement)throws GeneralSecurityException {
        PasswordPolicy.validate(replacement);
        synchronized(LOCK){
            requireConfigured();checkLock();
            String normalizedUsername="",normalizedCode="";
            try{normalizedUsername=normalizeUsername(username);}catch(IllegalArgumentException ignored){/* Failed recovery. */}
            try{normalizedCode=normalizeRecovery(recoveryCode);}catch(IllegalArgumentException ignored){/* Failed recovery. */}
            boolean codeMatches=matches(normalizedCode.toCharArray(),"recovery_salt","recovery_hash");
            if(!codeMatches||!this.username().equals(normalizedUsername)){
                failedAttempt();throw new IllegalArgumentException("Usuário ou código de recuperação incorreto.");
            }
            byte[] salt=salt(),recoverySalt=salt();String newCode=recoveryCode();
            byte[] hash=derive(replacement,salt),recoveryHash=derive(normalizeRecovery(newCode).toCharArray(),recoverySalt);
            try{
                write(preferences.edit().putString("password_salt",encode(salt)).putString("password_hash",encode(hash))
                        .putString("recovery_salt",encode(recoverySalt)).putString("recovery_hash",encode(recoveryHash))
                        .remove("failures").remove("blocked_until"));
                return newCode;
            }finally{Arrays.fill(hash,(byte)0);Arrays.fill(recoveryHash,(byte)0);}
        }
    }

    private boolean matches(char[] input,String saltName,String hashName)throws GeneralSecurityException {
        if(input==null||input.length>128)return false;
        byte[] salt,expected;
        try{
            if(preferences.getInt("version",0)!=1)throw new IllegalArgumentException();
            salt=Base64.getDecoder().decode(preferences.getString(saltName,""));
            expected=Base64.getDecoder().decode(preferences.getString(hashName,""));
            if(salt.length!=32||expected.length!=32)throw new IllegalArgumentException();
        }catch(IllegalArgumentException ex){throw new IllegalStateException("As credenciais locais estão indisponíveis. Preserve os dados do aplicativo.");}
        byte[] actual=derive(input,salt);
        try{return MessageDigest.isEqual(expected,actual);}
        finally{Arrays.fill(actual,(byte)0);Arrays.fill(expected,(byte)0);}
    }
    private static byte[] derive(char[] password,byte[] salt)throws GeneralSecurityException {
        PBEKeySpec specification=new PBEKeySpec(password,salt,ITERATIONS,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();}
        finally{specification.clearPassword();}
    }
    private byte[] salt(){byte[] bytes=new byte[32];random.nextBytes(bytes);return bytes;}
    private String recoveryCode(){
        byte[] bytes=new byte[16];random.nextBytes(bytes);StringBuilder result=new StringBuilder();
        for(int index=0;index<bytes.length;index++){if(index>0&&index%2==0)result.append('-');result.append(String.format(Locale.ROOT,"%02X",bytes[index]&255));}
        return result.toString();
    }
    private static String normalizeRecovery(String value){
        if(value==null||value.length()>80)throw new IllegalArgumentException();
        String normalized=value.replace("-","").replace(" ","").toUpperCase(Locale.ROOT);
        if(!normalized.matches("[0-9A-F]{32}"))throw new IllegalArgumentException();return normalized;
    }
    private static String normalizeUsername(String username){
        if(username==null)throw new IllegalArgumentException("Informe um usuário de 3 a 80 caracteres, sem espaços.");
        String normalized=username.trim().toLowerCase(Locale.ROOT);
        if(!normalized.matches("[\\p{L}\\p{N}._@+\\-]{3,80}"))throw new IllegalArgumentException("Informe um usuário de 3 a 80 caracteres, sem espaços.");
        return normalized;
    }
    private void requireConfigured(){if(!configured())throw new IllegalStateException("Crie sua conta local neste aparelho antes de entrar.");}
    private void checkLock(){
        long remaining=preferences.getLong("blocked_until",0)-clock.millis();
        if(remaining>0)throw new IllegalStateException("Muitas tentativas. Aguarde "+((remaining+999)/1000)+" segundos e tente novamente.");
    }
    private void failedAttempt(){
        int failures=Math.min(20,preferences.getInt("failures",0)+1);long until=0;
        if(failures>=5){long seconds=Math.min(300,30L<<Math.min(4,failures-5));until=clock.millis()+seconds*1000;}
        write(preferences.edit().putInt("failures",failures).putLong("blocked_until",until));
    }
    private void clearFailures(){write(preferences.edit().remove("failures").remove("blocked_until"));}
    private static String encode(byte[] value){return Base64.getEncoder().encodeToString(value);}
    private static void write(SharedPreferences.Editor changes){if(!changes.commit())throw new IllegalStateException("Não foi possível salvar a configuração de acesso. Tente novamente.");}
}
