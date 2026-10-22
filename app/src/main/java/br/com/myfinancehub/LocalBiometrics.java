package br.com.myfinancehub;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Optional, device-bound unlock: a biometric result alone is never sufficient without crypto. */
public final class LocalBiometrics {
    static final String PREFS = "local_biometrics";
    private static final String PREFIX = "myfinancehub.biometric.";
    private static final byte[] PROOF = "myfinancehub-local-unlock-v1".getBytes(StandardCharsets.UTF_8);
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static CancellationSignal pending;

    public interface Callback {
        void success();
        void failure(String message);
    }

    private LocalBiometrics() {}

    @android.annotation.SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    public static boolean available(Context context) {
        if (Build.VERSION.SDK_INT < 28) return false;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                BiometricManager manager = context.getSystemService(BiometricManager.class);
                return manager != null && manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        == BiometricManager.BIOMETRIC_SUCCESS;
            }
            if (Build.VERSION.SDK_INT == 29) {
                BiometricManager manager = context.getSystemService(BiometricManager.class);
                return manager != null && manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
            }
            FingerprintManager manager = context.getSystemService(FingerprintManager.class);
            return manager != null && manager.isHardwareDetected() && manager.hasEnrolledFingerprints();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public static boolean enabled(Context context) {
        if (Build.VERSION.SDK_INT < 28) return false;
        try {
            SharedPreferences preferences = preferences(context);
            String alias = preferences.getString("alias", "");
            if (!validAlias(alias)) return false;
            byte[] iv = Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP);
            byte[] proof = Base64.decode(preferences.getString("proof", ""), Base64.NO_WRAP);
            return iv.length == 12 && proof.length == PROOF.length + 16;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** Caller must have just authenticated with the password before offering enrollment. */
    public static void enable(Activity activity, Callback callback) {
        start(activity, callback, true);
    }

    public static void unlock(Activity activity, Callback callback) {
        start(activity, callback, false);
    }

    public static void disable(Context context) {
        cancelPending();
        SharedPreferences preferences = preferences(context);
        String alias = preferences.getString("alias", "");
        if (!preferences.edit().clear().commit())
            throw new IllegalStateException("Não foi possível desativar a biometria neste aparelho.");
        deleteKey(alias);
    }

    /** Invalidate callbacks before cancellation, including callbacks queued during activity shutdown. */
    public static synchronized void cancelPending() {
        GENERATION.incrementAndGet();
        CancellationSignal signal = pending;
        pending = null;
        if (signal != null) signal.cancel();
    }

    private static void start(Activity activity, Callback callback, boolean enrolling) {
        if (callback == null) throw new IllegalArgumentException("Retorno de biometria ausente.");
        if (Build.VERSION.SDK_INT < 28 || !available(activity)) {
            callback.failure("Biometria forte indisponível. Use sua senha e confira as biometrias cadastradas no Android.");
            return;
        }
        if (!enrolling && !enabled(activity)) {
            callback.failure("Biometria não configurada neste aparelho. Entre com a senha.");
            return;
        }
        cancelPending();
        Api28.start(activity, callback, enrolling);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean validAlias(String alias) {
        return alias != null && alias.matches("myfinancehub\\.biometric\\.[0-9a-f-]{36}");
    }

    private static void deleteKey(String alias) {
        if (!validAlias(alias)) return;
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(alias);
        } catch (Exception ignored) {
            // Removed preferences make the old proof unusable even if a keystore deletion fails.
        }
    }

    /** Authenticated GCM verification, shared with the offline crypto regression tests. */
    static boolean validProof(Cipher cipher, byte[] ciphertext) throws Exception {
        return MessageDigest.isEqual(PROOF, cipher.doFinal(ciphertext));
    }

    static byte[] proofForEncryption() { return PROOF.clone(); }

    // Only start() reaches this platform bridge, after explicitly checking SDK >= 28.
    @android.annotation.SuppressLint("NewApi")
    private static final class Api28 {
        static void start(Activity activity, Callback callback, boolean enrolling) {
            SharedPreferences preferences = preferences(activity);
            String previousAlias = preferences.getString("alias", "");
            String alias = enrolling ? PREFIX + UUID.randomUUID() : previousAlias;
            AtomicBoolean completed = new AtomicBoolean();
            int generation = GENERATION.get();
            try {
                KeyStore store = KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                if (enrolling) {
                    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                    KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(alias,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .setUserAuthenticationRequired(true)
                            .setInvalidatedByBiometricEnrollment(true);
                    if (Build.VERSION.SDK_INT >= 30)
                        spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
                    else spec.setUserAuthenticationValidityDurationSeconds(-1);
                    generator.init(spec.build());
                    generator.generateKey();
                }
                SecretKey key = (SecretKey) store.getKey(alias, null);
                if (key == null) throw new IllegalStateException();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                byte[] encrypted;
                if (enrolling) {
                    cipher.init(Cipher.ENCRYPT_MODE, key);
                    encrypted = null;
                } else {
                    encrypted = Base64.decode(preferences.getString("proof", ""), Base64.NO_WRAP);
                    byte[] iv = Base64.decode(preferences.getString("iv", ""), Base64.NO_WRAP);
                    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
                }
                // Bind ciphertext to this app purpose as well as the device's authentication key.
                cipher.updateAAD(PREFS.getBytes(StandardCharsets.UTF_8));
                CancellationSignal cancellation = new CancellationSignal();
                synchronized (LocalBiometrics.class) { pending = cancellation; }
                cancellation.setOnCancelListener(() -> {
                    if (completed.compareAndSet(false, true)) {
                        if (enrolling) deleteKey(alias);
                        if (GENERATION.get() == generation && !activity.isFinishing() && !activity.isDestroyed())
                            callback.failure("Biometria cancelada. Use sua senha para entrar.");
                    }
                });
                BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                        .setTitle(enrolling ? "Ativar entrada por biometria" : "Entrar no MyFinanceHub")
                        .setSubtitle("Confirme uma biometria cadastrada neste aparelho")
                        .setNegativeButton("Usar senha", activity.getMainExecutor(), (dialog, which) -> cancellation.cancel());
                if (Build.VERSION.SDK_INT >= 30)
                    builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
                builder.build().authenticate(new BiometricPrompt.CryptoObject(cipher), cancellation,
                        activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        if (GENERATION.get() != generation || activity.isFinishing() || activity.isDestroyed()
                                || !completed.compareAndSet(false, true)) return;
                        synchronized (LocalBiometrics.class) { if (pending == cancellation) pending = null; }
                        try {
                            if (result.getCryptoObject() == null || result.getCryptoObject().getCipher() != cipher)
                                throw new IllegalStateException();
                            if (enrolling) {
                                byte[] proof = cipher.doFinal(PROOF);
                                if (!preferences.edit().putString("alias", alias)
                                        .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                                        .putString("proof", Base64.encodeToString(proof, Base64.NO_WRAP)).commit())
                                    throw new IllegalStateException();
                                deleteKey(previousAlias);
                            } else if (!validProof(cipher, encrypted)) throw new IllegalStateException();
                            callback.success();
                        } catch (Exception invalidated) {
                            if (enrolling) deleteKey(alias);
                            callback.failure("Biometria não pôde liberar o acesso. Entre com a senha e ative-a novamente.");
                        }
                    }
                    @Override public void onAuthenticationError(int errorCode, CharSequence errorMessage) {
                        if (!completed.compareAndSet(false, true)) return;
                        synchronized (LocalBiometrics.class) { if (pending == cancellation) pending = null; }
                        if (enrolling) deleteKey(alias);
                        if (GENERATION.get() == generation && !activity.isDestroyed())
                            callback.failure("Biometria não concluída. Use sua senha para entrar.");
                    }
                    // A nonmatching biometric leaves the system prompt open; it never grants access.
                });
            } catch (Exception invalidated) {
                if (enrolling) deleteKey(alias);
                if (completed.compareAndSet(false, true))
                    callback.failure("Biometria indisponível ou alterada. Entre com a senha e ative-a novamente.");
            }
        }
    }
}
