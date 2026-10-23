package br.com.myfinancehub;

import android.app.Activity;
import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.util.Base64;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.AlgorithmParameterSpec;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocalBiometricsTest {
    private Context context;private ActivityController<Activity> controller;
    private static final String ALIAS="myfinancehub.biometric.11111111-1111-1111-1111-111111111111";
    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(LocalBiometrics.PREFS, 0).edit().clear().commit();
    }
    @After public void after() {
        LocalBiometrics.cancelPending();
        if(controller!=null)controller.pause().stop().destroy();
        context.getSharedPreferences(LocalBiometrics.PREFS, 0).edit().clear().commit();
    }

    @Test public void newInstallHasNoBiometricGrant() {
        assertFalse(LocalBiometrics.enabled(context));
        assertFalse(LocalBiometrics.available(context));
    }

    @Test @Config(sdk = 26) public void unsupportedAndroidNeverAuthorizesAndRetainsPasswordFallback() {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        AtomicInteger success = new AtomicInteger(), failure = new AtomicInteger();
        LocalBiometrics.Callback callback = new LocalBiometrics.Callback() {
            public void success() { success.incrementAndGet(); }
            public void failure(String message) { assertTrue(message.contains("senha")); failure.incrementAndGet(); }
        };
        LocalBiometrics.enable(controller.get(), callback);
        LocalBiometrics.unlock(controller.get(), callback);
        assertEquals(0, success.get());
        assertEquals(2, failure.get());
        assertFalse(LocalBiometrics.enabled(context));
        controller.pause().stop().destroy();
    }

    @Test public void corruptOrPartialEnrollmentCannotBeUsedAsProof() {
        String alias = "myfinancehub.biometric.11111111-1111-1111-1111-111111111111";
        context.getSharedPreferences(LocalBiometrics.PREFS, 0).edit().putString("alias", alias)
                .putString("iv", Base64.encodeToString(new byte[12], Base64.NO_WRAP)).commit();
        assertFalse(LocalBiometrics.enabled(context));
        context.getSharedPreferences(LocalBiometrics.PREFS, 0).edit().putString("proof", "malformed!").commit();
        assertFalse(LocalBiometrics.enabled(context));
    }

    @Test public void disablingDoesNotClearThePasswordOrFinancePreferences() {
        context.getSharedPreferences("local_auth", 0).edit().putString("username", "owner").commit();
        context.getSharedPreferences("MainActivity", 0).edit().putBoolean("hidden", true).commit();
        context.getSharedPreferences(LocalBiometrics.PREFS, 0).edit().putString("alias", "not-an-app-alias").commit();
        LocalBiometrics.disable(context);
        assertTrue(context.getSharedPreferences(LocalBiometrics.PREFS, 0).getAll().isEmpty());
        assertEquals("owner", context.getSharedPreferences("local_auth", 0).getString("username", ""));
        assertTrue(context.getSharedPreferences("MainActivity", 0).getBoolean("hidden", false));
        context.getSharedPreferences("local_auth", 0).edit().clear().commit();
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit();
    }

    @Test public void authenticatedCipherMustDecryptTheExpectedProof() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        Cipher encrypt = Cipher.getInstance("AES/GCM/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = LocalBiometrics.encryptProof(encrypt);
        Cipher decrypt = decrypt(key, encrypt.getIV());
        assertTrue(LocalBiometrics.validProof(decrypt, ciphertext));
        ciphertext[0] ^= 1;
        try {
            LocalBiometrics.validProof(decrypt(key, encrypt.getIV()), ciphertext);
            fail("A modified proof must fail its authentication tag");
        } catch (AEADBadTagException expected) { }
    }

    @Test public void anotherValidPlaintextDoesNotCountAsAnUnlock() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
        SecretKey key = generator.generateKey(); Cipher encrypt = Cipher.getInstance("AES/GCM/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, key);
        encrypt.updateAAD(LocalBiometrics.PREFS.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = encrypt.doFinal("some-other-purpose".getBytes(StandardCharsets.UTF_8));
        assertFalse(LocalBiometrics.validProof(decrypt(key, encrypt.getIV()), ciphertext));
    }

    @Test @Config(sdk={28,35}) public void enrollmentSendsCipherDataOnlyAfterTheSystemAuthenticates() throws Exception {
        controller=Robolectric.buildActivity(Activity.class).setup();SecretKey key=key();GateCipher cipher=gate(Cipher.ENCRYPT_MODE,key,null);
        Probe prompt=new Probe();Results result=new Results();
        LocalBiometrics.authenticatePrepared(controller.get(),result,true,ALIAS,"",cipher,null,prompt);
        assertEquals(0,cipher.gate.aadWrites);assertFalse(LocalBiometrics.enabled(context));assertEquals(0,result.success);
        cipher.gate.authorized=true;prompt.events.success(cipher);
        assertEquals(1,cipher.gate.aadWrites);assertEquals(1,result.success);assertEquals(0,result.failure);assertTrue(LocalBiometrics.enabled(context));
        byte[] proof=Base64.decode(context.getSharedPreferences(LocalBiometrics.PREFS,0).getString("proof",""),Base64.NO_WRAP);
        GateCipher decrypt=gate(Cipher.DECRYPT_MODE,key,cipher.getIV());Probe unlock=new Probe();Results unlocked=new Results();
        LocalBiometrics.authenticatePrepared(controller.get(),unlocked,false,ALIAS,ALIAS,decrypt,proof,unlock);
        assertEquals("An auth-per-use key must not receive AAD before the prompt",0,decrypt.gate.aadWrites);
        decrypt.gate.authorized=true;unlock.events.success(decrypt);unlock.events.success(decrypt);
        assertEquals(1,unlocked.success);assertEquals(0,unlocked.failure);
    }

    @Test public void invalidProofCannotUnlockAndCanBeReactivatedAfterPasswordLogin() throws Exception {
        controller=Robolectric.buildActivity(Activity.class).setup();SecretKey key=key();Cipher encrypted=Cipher.getInstance("AES/GCM/NoPadding");encrypted.init(Cipher.ENCRYPT_MODE,key);
        byte[] proof=LocalBiometrics.encryptProof(encrypted);proof[0]^=1;saveGrant(encrypted.getIV(),proof);
        context.getSharedPreferences("local_auth",0).edit().putString("username","owner").commit();
        GateCipher cipher=gate(Cipher.DECRYPT_MODE,key,encrypted.getIV());Probe prompt=new Probe();Results result=new Results();
        LocalBiometrics.authenticatePrepared(controller.get(),result,false,ALIAS,ALIAS,cipher,proof,prompt);
        cipher.gate.authorized=true;prompt.events.success(cipher);
        assertEquals(0,result.success);assertEquals(1,result.failure);assertFalse(LocalBiometrics.enabled(context));assertTrue(result.message.contains("senha"));
        assertEquals("owner",context.getSharedPreferences("local_auth",0).getString("username",""));context.getSharedPreferences("local_auth",0).edit().clear().commit();
    }

    @Test public void successWithoutTheExactAuthenticatedCipherNeverGrantsAccess() throws Exception {
        controller=Robolectric.buildActivity(Activity.class).setup();GateCipher cipher=gate(Cipher.ENCRYPT_MODE,key(),null);Probe prompt=new Probe();Results result=new Results();
        LocalBiometrics.authenticatePrepared(controller.get(),result,true,ALIAS,"",cipher,null,prompt);prompt.events.success(null);
        assertEquals(0,result.success);assertEquals(1,result.failure);assertEquals(0,cipher.gate.aadWrites);assertFalse(LocalBiometrics.enabled(context));
    }

    @Test public void canceledPromptRejectsLateSuccessAndRetainsExistingEnrollment() throws Exception {
        controller=Robolectric.buildActivity(Activity.class).setup();GateCipher cipher=gate(Cipher.ENCRYPT_MODE,key(),null);byte[] oldProof=new byte[LocalBiometrics.proofForEncryption().length+16];saveGrant(new byte[12],oldProof);
        Probe prompt=new Probe();Results result=new Results();LocalBiometrics.authenticatePrepared(controller.get(),result,false,ALIAS,ALIAS,cipher,oldProof,prompt);
        LocalBiometrics.cancelPending();assertTrue(prompt.cancellation.isCanceled());cipher.gate.authorized=true;prompt.events.success(cipher);
        assertEquals(0,result.success);assertEquals(0,result.failure);assertEquals(0,cipher.gate.aadWrites);assertTrue(LocalBiometrics.enabled(context));
    }

    @Test public void androidLockoutKeepsEnrollmentAndExplainsPasswordFallback() throws Exception {
        controller=Robolectric.buildActivity(Activity.class).setup();GateCipher cipher=gate(Cipher.ENCRYPT_MODE,key(),null);byte[] proof=new byte[LocalBiometrics.proofForEncryption().length+16];saveGrant(new byte[12],proof);
        Probe prompt=new Probe();Results result=new Results();LocalBiometrics.authenticatePrepared(controller.get(),result,false,ALIAS,ALIAS,cipher,proof,prompt);
        prompt.events.error(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT);assertEquals(0,result.success);assertEquals(1,result.failure);assertTrue(LocalBiometrics.enabled(context));assertTrue(result.message.contains("temporariamente"));
    }

    private void saveGrant(byte[] iv,byte[] proof){context.getSharedPreferences(LocalBiometrics.PREFS,0).edit().putString("alias",ALIAS).putString("iv",Base64.encodeToString(iv,Base64.NO_WRAP)).putString("proof",Base64.encodeToString(proof,Base64.NO_WRAP)).commit();}
    private static SecretKey key()throws Exception {KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);return generator.generateKey();}
    private static GateCipher gate(int mode,SecretKey key,byte[] iv)throws Exception {GateCipher cipher=new GateCipher(new GateSpi());if(iv==null)cipher.init(mode,key);else cipher.init(mode,key,new GCMParameterSpec(128,iv));return cipher;}
    private static final class Probe implements LocalBiometrics.PromptDriver {
        LocalBiometrics.AuthenticationEvents events;CancellationSignal cancellation;
        public void authenticate(Activity activity,boolean enrolling,Cipher cipher,CancellationSignal signal,LocalBiometrics.AuthenticationEvents events){this.events=events;this.cancellation=signal;}
    }
    private static final class Results implements LocalBiometrics.Callback {
        int success,failure;String message;
        public void success(){success++;}public void failure(String message){failure++;this.message=message;}
    }
    /** Model an auth-per-operation provider, using real AES/GCM once explicitly authorized. */
    private static final class GateCipher extends Cipher {
        final GateSpi gate;
        GateCipher(GateSpi gate){super(gate,new Provider("biometric-test",1.0,"auth-per-operation test provider"){},"AES/GCM/NoPadding");this.gate=gate;}
    }
    private static final class GateSpi extends CipherSpi {
        final Cipher delegate;boolean authorized;int aadWrites;
        GateSpi()throws Exception {delegate=Cipher.getInstance("AES/GCM/NoPadding");}
        private void requireAuth(){if(!authorized)throw new IllegalStateException("Biometric operation not authenticated");}
        protected void engineSetMode(String mode){}protected void engineSetPadding(String padding){}
        protected int engineGetBlockSize(){return delegate.getBlockSize();}protected int engineGetOutputSize(int size){return delegate.getOutputSize(size);}
        protected byte[] engineGetIV(){return delegate.getIV();}protected AlgorithmParameters engineGetParameters(){return delegate.getParameters();}
        protected void engineInit(int mode,Key key,SecureRandom random)throws InvalidKeyException {delegate.init(mode,key,random);}
        protected void engineInit(int mode,Key key,AlgorithmParameterSpec params,SecureRandom random)throws InvalidKeyException,InvalidAlgorithmParameterException {delegate.init(mode,key,params,random);}
        protected void engineInit(int mode,Key key,AlgorithmParameters params,SecureRandom random)throws InvalidKeyException,InvalidAlgorithmParameterException {delegate.init(mode,key,params,random);}
        protected byte[] engineUpdate(byte[] data,int offset,int length){requireAuth();return delegate.update(data,offset,length);}
        protected int engineUpdate(byte[] data,int offset,int length,byte[] out,int outOffset)throws javax.crypto.ShortBufferException {requireAuth();return delegate.update(data,offset,length,out,outOffset);}
        protected void engineUpdateAAD(byte[] data,int offset,int length){requireAuth();aadWrites++;delegate.updateAAD(data,offset,length);}
        protected byte[] engineDoFinal(byte[] data,int offset,int length)throws javax.crypto.IllegalBlockSizeException,javax.crypto.BadPaddingException {requireAuth();return delegate.doFinal(data,offset,length);}
        protected int engineDoFinal(byte[] data,int offset,int length,byte[] out,int outOffset)throws javax.crypto.ShortBufferException,javax.crypto.IllegalBlockSizeException,javax.crypto.BadPaddingException {requireAuth();return delegate.doFinal(data,offset,length,out,outOffset);}
    }

    private static Cipher decrypt(SecretKey key, byte[] iv) throws Exception {
        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return decrypt;
    }
}
