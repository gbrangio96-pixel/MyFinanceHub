package br.com.myfinancehub;

import android.app.Activity;
import android.content.Context;
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
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocalBiometricsTest {
    private Context context;
    @Before public void before() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(LocalBiometrics.PREFS, 0).edit().clear().commit();
    }
    @After public void after() {
        LocalBiometrics.cancelPending();
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
        encrypt.updateAAD(LocalBiometrics.PREFS.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = encrypt.doFinal(LocalBiometrics.proofForEncryption());
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

    private static Cipher decrypt(SecretKey key, byte[] iv) throws Exception {
        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        decrypt.updateAAD(LocalBiometrics.PREFS.getBytes(StandardCharsets.UTF_8));
        return decrypt;
    }
}
