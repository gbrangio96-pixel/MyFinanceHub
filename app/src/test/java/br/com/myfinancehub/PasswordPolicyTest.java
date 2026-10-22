package br.com.myfinancehub;
import org.junit.Test;
import static org.junit.Assert.*;
public class PasswordPolicyTest {
    @Test public void requiresAllFourCharacterGroupsAndAtLeastEightCharacters(){for(String value:new String[]{"Aa1!aaa","abcdefgh1!","ABCDEFGH1!","Abcdefgh!","Abcdefgh1","Abcdef1 ","Abcde1!\n"})try{PasswordPolicy.validate(value.toCharArray());fail(value);}catch(IllegalArgumentException expected){}}
    @Test public void acceptsRequestedMinimumAndLongerPasswords(){PasswordPolicy.validate("Abcdef1!".toCharArray());PasswordPolicy.validate("MinhaSenha123@".toCharArray());}
    @Test public void rejectsExcessiveInput(){try{PasswordPolicy.validate(("Ab1!"+"a".repeat(125)).toCharArray());fail();}catch(IllegalArgumentException expected){}}
}
