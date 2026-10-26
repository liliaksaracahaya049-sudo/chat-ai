package com.chatai.studio;

import android.content.Context;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Isi pesan panjang disimpan sebagai berkas, bukan di dalam baris database. */
final class Penyimpan {
    private Penyimpan() { }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "pesan");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File berkas(Context c, long id, String ext) { return new File(dir(c), id + "." + ext); }

    private static void tulis(Context c, long id, String ext, CharSequence t) throws IOException {
        File tujuan = berkas(c, id, ext);
        File tmp = new File(tujuan.getPath() + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8), 65536)) {
            int n = t.length();
            for (int i = 0; i < n; i += 8192) w.append(t, i, Math.min(n, i + 8192));
        }
        if (tujuan.exists()) tujuan.delete();
        if (!tmp.renameTo(tujuan)) throw new IOException("Gagal menyimpan berkas pesan");
    }

    private static String baca(Context c, long id, String ext) {
        File f = berkas(c, id, ext);
        if (!f.exists()) return "";
        StringBuilder sb = new StringBuilder((int) Math.min(f.length(), 50_000_000L));
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            char[] buf = new char[16384];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        } catch (Throwable t) {
            return sb.toString();
        }
        return sb.toString();
    }

    static void tulisTeks(Context c, long id, CharSequence t) throws IOException { tulis(c, id, "txt", t); }
    static String bacaTeks(Context c, long id) { return baca(c, id, "txt"); }
    static void tulisPikir(Context c, long id, CharSequence t) throws IOException { if (t.length() > 0) tulis(c, id, "pikir", t); }
    static String bacaPikir(Context c, long id) { return baca(c, id, "pikir"); }

    static void hapus(Context c, long id) {
        berkas(c, id, "txt").delete();
        berkas(c, id, "pikir").delete();
    }
}
