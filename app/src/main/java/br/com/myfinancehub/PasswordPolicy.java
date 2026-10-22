package br.com.myfinancehub;

/** Shared validation for account creation and password changes. */
public final class PasswordPolicy {
    private PasswordPolicy(){}
    public static void validate(char[] password){
        if(password==null||password.length<8||password.length>128)throw invalid();
        boolean upper=false,lower=false,number=false,special=false;
        for(char c:password){if(Character.isISOControl(c))throw invalid();upper|=Character.isUpperCase(c);lower|=Character.isLowerCase(c);number|=Character.isDigit(c);special|=!Character.isLetterOrDigit(c)&&!Character.isWhitespace(c);}
        if(!upper||!lower||!number||!special)throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("A senha deve ter de 8 a 128 caracteres, com letra maiúscula, minúscula, número e caractere especial.");}
}
