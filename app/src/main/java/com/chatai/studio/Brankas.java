package com.chatai.studio;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Menyimpan API key terenkripsi memakai AndroidKeyStore (AES-GCM). */
final class Brankas {
    private static final String ALIAS = "chatai_brankas_v1";
    private Brankas() { }

    private static SecretKey kunciAes() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return kg.generateKey();
    }

    static String kunci(String teks) {
        if (teks == null || teks.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, kunciAes());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(teks.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "e:" + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            return "p:" + teks;
        }
    }

    static String buka(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            if (s.startsWith("p:")) return s.substring(2);
            if (!s.startsWith("e:")) return s;
            byte[] all = Base64.decode(s.substring(2), Base64.NO_WRAP);
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, kunciAes(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
