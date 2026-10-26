package com.chatai.studio;

// tt-guard: v2

import android.app.Activity;
import android.app.Application;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Random;

/**
 * Penjaga tanda tangan (dibuat otomatis oleh GitHub by novan).
 * Jika APK ditandatangani ulang dengan kunci lain, sidik jari sertifikat tidak cocok
 * dan aplikasi ditutup. Pengecekan dilakukan dengan dua jalur yang berdiri sendiri:
 * (1) PackageManager milik Android, dan (2) membaca langsung berkas APK yang terpasang
 * (blok tanda tangan v2/v3), sehingga alat pemalsu tanda tangan yang hanya mengelabui
 * PackageManager tetap ketahuan. Pengecekan berjalan saat aplikasi mulai, setiap layar tampil,
 * berkala, dan dari MainActivity. Penutupan aplikasi ditunda secara acak supaya sulit dilacak.
 * Sidik jari disimpan terpecah (dua bagian yang di-XOR), bukan sebagai teks utuh.
 * Penjaga hanya aktif bila APK dibangun dengan tanda tangan pribadi.
 */
public class TTGuard extends ContentProvider {
    // Diisi otomatis oleh alur kerja GitHub Actions saat membangun dengan tanda tangan pribadi.
    private static final String A = "__TT_A__";
    private static final String B = "__TT_B__";
    private static final String KOSONG = "__TT" + "_A__";
    private static volatile boolean terpasang;
    private static volatile boolean curang;
    private static volatile boolean dijadwalkan;

    private static boolean aktif() {
        return !KOSONG.equals(A);
    }

    @Override
    public boolean onCreate() {
        if (!aktif()) return true; // bukan tanda tangan pribadi: penjaga tidak aktif
        Context c = getContext();
        periksa(c);
        pasangPengawas(c);
        return true;
    }

    /** Titik pengecekan: dipanggil dari provider, siklus layar, jadwal berkala, dan MainActivity. */
    public static void periksa(Context c) {
        if (!aktif()) return;
        if (curang) {
            if (dijadwalkan) return;
            tutup(c);
            return;
        }
        boolean ok = false;
        try {
            ok = cocok(c);
        } catch (Throwable ignored) { }
        if (!ok) {
            curang = true;
            tutup(c);
        }
    }

    private static void pasangPengawas(final Context c) {
        if (terpasang) return;
        terpasang = true;
        try {
            Context ctx = c.getApplicationContext();
            if (ctx == null) ctx = c;
            if (ctx instanceof Application) {
                ((Application) ctx).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityCreated(Activity a, Bundle b) { periksa(a); }
                    @Override public void onActivityStarted(Activity a) { }
                    @Override public void onActivityResumed(Activity a) { periksa(a); }
                    @Override public void onActivityPaused(Activity a) { }
                    @Override public void onActivityStopped(Activity a) { }
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                    @Override public void onActivityDestroyed(Activity a) { }
                });
            }
            final Context app = ctx;
            final Handler h = new Handler(Looper.getMainLooper());
            final Random r = new Random();
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    periksa(app);
                    h.postDelayed(this, 30000L + r.nextInt(60000));
                }
            }, 20000L + r.nextInt(20000));
        } catch (Throwable ignored) { }
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] o = new byte[n];
        for (int i = 0; i < n; i++) {
            o[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return o;
    }

    private static byte[] harapan() {
        byte[] x = hex(A);
        byte[] y = hex(B);
        byte[] o = new byte[x.length];
        for (int i = 0; i < o.length; i++) o[i] = (byte) (x[i] ^ y[i % y.length]);
        return o;
    }

    private static boolean cocok(Context c) throws Exception {
        byte[] h = harapan();
        if (!cocokPaket(c, h)) return false;
        return cocokBerkas(c.getPackageCodePath(), h) != 0;
    }

    /** Jalur 1: sertifikat menurut PackageManager. */
    private static boolean cocokPaket(Context c, byte[] h) throws Exception {
        PackageManager pm = c.getPackageManager();
        String pkg = c.getPackageName();
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = pi.signingInfo;
            if (si == null) return false;
            sigs = si.getApkContentsSigners();
        } else {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            sigs = pi.signatures;
        }
        if (sigs == null || sigs.length == 0) return false;
        for (Signature s : sigs) {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
            if (!MessageDigest.isEqual(d, h)) return false;
        }
        return true;
    }

    /** Jalur 2: 1 = cocok, 0 = beda, -1 = tidak terbaca (dilewati agar aplikasi asli tidak ikut tertutup). */
    private static int cocokBerkas(String path, byte[] h) {
        try {
            byte[] s = sidikBerkas(path);
            if (s == null) return Build.VERSION.SDK_INT >= 24 ? 0 : -1; // APK modern wajib punya blok v2/v3
            return MessageDigest.isEqual(s, h) ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- mulai pembaca blok tanda tangan ----
    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static long le64(byte[] b, int o) {
        return le32(b, o) | (le32(b, o + 4) << 32);
    }

    /** Ambil satu bagian berawalan panjang (uint32) dari buffer, lalu majukan posisi. */
    private static ByteBuffer bagian(ByteBuffer b) {
        int n = b.getInt();
        if (n < 0 || n > b.remaining()) throw new IllegalStateException("bagian");
        ByteBuffer s = b.slice().order(ByteOrder.LITTLE_ENDIAN);
        s.limit(n);
        b.position(b.position() + n);
        return s;
    }

    /** SHA-256 sertifikat penanda tangan pertama dari blok v3 (atau v2); null bila tidak ada blok. */
    static byte[] sidikBerkas(String path) throws Exception {
        RandomAccessFile f = new RandomAccessFile(path, "r");
        try {
            long len = f.length();
            int max = (int) Math.min(len, 65535L + 22L);
            byte[] tail = new byte[max];
            f.seek(len - max);
            f.readFully(tail);
            int e = -1;
            for (int i = max - 22; i >= 0; i--) {
                if (tail[i] == 0x50 && tail[i + 1] == 0x4b && tail[i + 2] == 5 && tail[i + 3] == 6
                        && i + 22 + (tail[i + 20] & 0xFF) + ((tail[i + 21] & 0xFF) << 8) == max) { e = i; break; }
            }
            if (e < 0) throw new IllegalStateException("eocd");
            long cd = le32(tail, e + 16);
            if (cd < 32 || cd > len) throw new IllegalStateException("cd");
            byte[] magic = new byte[16];
            f.seek(cd - 16);
            f.readFully(magic);
            if (!"APK Sig Block 42".equals(new String(magic, "ISO-8859-1"))) return null;
            byte[] sz = new byte[8];
            f.seek(cd - 24);
            f.readFully(sz);
            long size = le64(sz, 0);
            long start = cd - size - 8;
            if (size < 24 || start < 0 || size > 64L * 1024 * 1024) throw new IllegalStateException("blok");
            byte[] pairs = new byte[(int) (size - 24)];
            f.seek(start + 8);
            f.readFully(pairs);
            ByteBuffer b = ByteBuffer.wrap(pairs).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer v2 = null, v3 = null;
            while (b.remaining() >= 12) {
                long l = b.getLong();
                int id = b.getInt();
                long vl = l - 4;
                if (vl < 0 || vl > b.remaining()) throw new IllegalStateException("pasangan");
                ByteBuffer v = b.slice().order(ByteOrder.LITTLE_ENDIAN);
                v.limit((int) vl);
                b.position(b.position() + (int) vl);
                if (id == 0xf05368c0) v3 = v;
                else if (id == 0x7109871a) v2 = v;
            }
            ByteBuffer pick = v3 != null ? v3 : v2;
            if (pick == null) return null;
            ByteBuffer signers = bagian(pick);
            ByteBuffer signer = bagian(signers);
            ByteBuffer signed = bagian(signer);
            bagian(signed);                       // daftar digest dilewati
            ByteBuffer certs = bagian(signed);
            ByteBuffer cert = bagian(certs);
            byte[] der = new byte[cert.remaining()];
            cert.get(der);
            return MessageDigest.getInstance("SHA-256").digest(der);
        } finally {
            f.close();
        }
    }
    // ---- akhir pembaca blok tanda tangan ----

    /** Penutupan ditunda acak beberapa detik agar titik deteksi tidak mudah dilacak. */
    private static void tutup(final Context c) {
        if (dijadwalkan) return;
        dijadwalkan = true;
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { mati(c); }
            }, 2000L + new Random().nextInt(13000));
        } catch (Throwable t) {
            mati(c);
        }
    }

    private static void mati(Context c) {
        try {
            if (c instanceof Activity) ((Activity) c).finishAffinity();
        } catch (Throwable ignored) { }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(10);
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
