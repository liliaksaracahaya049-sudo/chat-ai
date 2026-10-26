package com.chatai.studio;

// kt-guard: v2

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Kunci terikat tanda tangan (dibuat otomatis oleh GitHub by novan).
 * Kunci enkripsi AES-256 diturunkan dari sidik jari sertifikat penanda tangan APK yang sedang
 * berjalan (dibaca langsung dari PackageManager), digabung dengan rahasia yang disematkan khusus
 * untuk build ini. Data atau fitur yang dienkripsi saat build hanya dapat dibaca kembali oleh APK
 * yang masih memakai tanda tangan asli: bila APK ditandatangani ulang dengan kunci lain, sidik jari
 * berubah sehingga kunci turunannya ikut berubah dan dekripsi gagal secara alami, tanpa ada
 * pengecekan eksplisit yang bisa dihapus penyerang.
 * Kunci hanya aktif bila APK dibangun dengan tanda tangan pribadi (lihat aktif()); pada build
 * dengan tanda tangan bawaan GitHub, pemanggilan kunci()/enkripsi()/dekripsi() akan gagal.
 *
 * Enkripsi string otomatis: tandai teks tetap di kode dengan s("teks"). Selama build belum
 * memprosesnya, s(...) hanya mengembalikan teks apa adanya sehingga aplikasi tetap berjalan
 * normal. Saat APK dibangun dengan tanda tangan pribadi, alur kerja GitHub Actions menggantikan
 * setiap pemanggilan s("teks asli") menjadi d("teks terenkripsi") sebelum kode dikompilasi, jadi
 * teks aslinya tidak lagi tertulis di dalam APK. d(...) membaca konteks aplikasi yang ditangkap
 * otomatis lewat Init (provider di AndroidManifest.xml), jadi tidak perlu diberi konteks manual.
 */
public final class KunciTandaTangan {
    // Diisi otomatis oleh alur kerja GitHub Actions saat membangun dengan tanda tangan pribadi.
    private static final String RAHASIA = "__KT_RAHASIA__";
    private static final String KOSONG = "__KT" + "_RAHASIA__";
    private static volatile Context KONTEKS;

    private KunciTandaTangan() { }

    /** true bila rahasia build sudah disematkan (APK dibangun dengan tanda tangan pribadi). */
    public static boolean aktif() {
        return !KOSONG.equals(RAHASIA);
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] o = new byte[n];
        for (int i = 0; i < n; i++) {
            o[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return o;
    }

    /** Sidik jari (SHA-256) sertifikat penanda tangan APK yang sedang berjalan. */
    private static byte[] sidik(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        String pkg = c.getPackageName();
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = pi.signingInfo;
            if (si == null) throw new IllegalStateException("tanpa signingInfo");
            sigs = si.getApkContentsSigners();
        } else {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            sigs = pi.signatures;
        }
        if (sigs == null || sigs.length == 0) throw new IllegalStateException("tanpa signature");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Signature s : sigs) md.update(s.toByteArray());
        return md.digest();
    }

    /** Kunci AES-256 turunan sidik jari sertifikat asli + rahasia build ini. Beda tanda tangan = beda kunci. */
    public static SecretKeySpec kunci(Context c) throws Exception {
        if (!aktif()) throw new IllegalStateException("kunci terikat tanda tangan tidak aktif pada build ini");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hex(RAHASIA), "HmacSHA256"));
        byte[] k = mac.doFinal(sidik(c));
        return new SecretKeySpec(k, "AES");
    }

    /** Enkripsi AES/GCM; IV acak 12 byte disisipkan di awal hasil. */
    public static byte[] enkripsi(Context c, byte[] data) throws Exception {
        SecretKeySpec k = kunci(c);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, iv));
        byte[] ct = ci.doFinal(data);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    /** Dekripsi; melempar galat bila kunci salah (tanda tangan berbeda) atau data rusak. */
    public static byte[] dekripsi(Context c, byte[] data) throws Exception {
        if (data == null || data.length < 13) throw new IllegalArgumentException("data tidak lengkap");
        SecretKeySpec k = kunci(c);
        byte[] iv = new byte[12];
        System.arraycopy(data, 0, iv, 0, 12);
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
        return ci.doFinal(data, 12, data.length - 12);
    }

    public static String enkripsiTeks(Context c, String teks) throws Exception {
        return Base64.encodeToString(enkripsi(c, teks.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    public static String dekripsiTeks(Context c, String base64) throws Exception {
        return new String(dekripsi(c, Base64.decode(base64, Base64.DEFAULT)), StandardCharsets.UTF_8);
    }

    /** Versi aman untuk dipakai di fitur: null bila gagal (tanda tangan beda, rusak, atau belum aktif). */
    public static String dekripsiTeksAtauNull(Context c, String base64) {
        try {
            return dekripsiTeks(c, base64);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Tandai teks tetap di kode yang ingin disembunyikan dari hasil APK, contoh:
     * String alamat = KunciTandaTangan.s("https://server-rahasia.contoh/api");
     * Di sini teks dikembalikan apa adanya. Alur kerja build yang menggantikannya dengan d(...)
     * saat APK dibangun dengan tanda tangan pribadi, jadi aman dipakai di build apa pun: bila
     * belum/tidak diproses, aplikasi tetap berjalan normal dengan teks aslinya.
     */
    public static String s(String teksAsli) {
        return teksAsli;
    }

    /**
     * Dipanggil otomatis oleh alur kerja build (menggantikan pemanggilan s(...)) untuk teks yang
     * sudah dienkripsi. Mengembalikan teks asli bila berhasil didekripsi, atau teks kosong ("")
     * bila gagal (tanda tangan berbeda, konteks belum siap, atau belum aktif) -- tidak pernah
     * melempar galat.
     */
    public static String d(String base64) {
        Context c = KONTEKS;
        if (c == null) return "";
        String r = dekripsiTeksAtauNull(c, base64);
        return r == null ? "" : r;
    }

    /**
     * Menangkap konteks aplikasi secara otomatis saat proses dimulai (dipasang lewat
     * AndroidManifest.xml), supaya d(...) tidak perlu diberi konteks secara manual di setiap
     * pemanggilan. Tidak melakukan apa pun selain itu.
     */
    public static final class Init extends ContentProvider {
        @Override
        public boolean onCreate() {
            Context c = getContext();
            if (c != null) {
                Context app = c.getApplicationContext();
                KONTEKS = app != null ? app : c;
            }
            return true;
        }

        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri u) { return null; }
        @Override public Uri insert(Uri u, ContentValues v) { return null; }
        @Override public int delete(Uri u, String s, String[] a) { return 0; }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    }
}
