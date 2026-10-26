package com.chatai.studio;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/** Menyalin lampiran ke cache (dengan progres) dan mengunggahnya ke Gemini Files API. */
final class PengelolaLampiran {
    static final long MAKS_UKURAN = 100L * 1024 * 1024;
    private static final Set<String> EKS_TEKS = new HashSet<>(Arrays.asList(
            "txt", "md", "json", "csv", "tsv", "xml", "html", "htm", "css", "js", "ts", "jsx", "tsx", "java", "kt",
            "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "sh", "bash", "bat", "sql", "yaml", "yml",
            "toml", "ini", "cfg", "conf", "log", "lua", "swift", "dart", "gradle", "properties", "srt", "vtt", "tex"));

    private PengelolaLampiran() { }

    static boolean miripTeks(Lampiran l) {
        String m = l.mime == null ? "" : l.mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/") || m.equals("application/pdf")) return false;
        if (m.startsWith("text/") || m.contains("json") || m.contains("xml") || m.contains("javascript")
                || m.contains("yaml") || m.contains("x-sh") || m.contains("sql") || m.contains("csv")) return true;
        String n = l.nama.toLowerCase(Locale.ROOT);
        int d = n.lastIndexOf('.');
        return d >= 0 && EKS_TEKS.contains(n.substring(d + 1));
    }

    private static String ekstensi(String nama) {
        int d = nama.lastIndexOf('.');
        return d >= 0 ? nama.substring(d + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** Alur lengkap: salin, lalu (bila Gemini) unggah. Dijalankan di thread latar. */
    static void siapkan(Context ctx, Lampiran l, Pengaturan set, Runnable ubah) {
        try {
            l.tahap = Lampiran.MENYALIN;
            l.progres = 0;
            ubah.run();
            salin(ctx, l, ubah);
            String kunci = set.kunci(Penyedia.GEMINI);
            if (Penyedia.GEMINI.equals(set.provider()) && !kunci.isEmpty() && (!miripTeks(l) || l.berkas.length() > 200_000L)) {
                l.tahap = Lampiran.MENGUNGGAH;
                l.progres = 0;
                ubah.run();
                unggahGemini(kunci, l, ubah);
            }
            l.progres = 100;
            l.tahap = Lampiran.SIAP;
        } catch (Throwable t) {
            l.tahap = Lampiran.GAGAL;
            l.galat = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        }
        ubah.run();
    }

    private static void salin(Context ctx, Lampiran l, Runnable ubah) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        android.net.Uri u = l.sumber;
        String nama = null;
        long ukuran = -1;
        try (Cursor c = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int a = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (a >= 0 && !c.isNull(a)) nama = c.getString(a);
                int b = c.getColumnIndex(OpenableColumns.SIZE);
                if (b >= 0 && !c.isNull(b)) ukuran = c.getLong(b);
            }
        } catch (Exception ignored) { }
        if (nama == null || nama.isEmpty()) {
            String p = u.getLastPathSegment();
            nama = p != null ? p : "berkas";
        }
        String mime = cr.getType(u);
        boolean virtual = false;
        try {
            if (DocumentsContract.isDocumentUri(ctx, u)) {
                try (Cursor c = cr.query(u, new String[]{DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
                    if (c != null && c.moveToFirst()) virtual = (c.getInt(0) & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0;
                }
            }
        } catch (Exception ignored) { }
        InputStream in;
        if (virtual) {
            String[] tipe = cr.getStreamTypes(u, "*/*");
            String pilih = null;
            if (tipe != null) {
                for (String t : tipe) if ("application/pdf".equals(t)) pilih = t;
                if (pilih == null && tipe.length > 0) pilih = tipe[0];
            }
            if (pilih == null) throw new IOException("Berkas Drive ini tidak bisa diekspor");
            AssetFileDescriptor afd = cr.openTypedAssetFileDescriptor(u, pilih, null);
            if (afd == null) throw new IOException("Berkas Drive tidak bisa dibuka");
            in = afd.createInputStream();
            mime = pilih;
            ukuran = -1;
            if (ekstensi(nama).isEmpty()) {
                String e = MimeTypeMap.getSingleton().getExtensionFromMimeType(pilih);
                if (e != null) nama = nama + "." + e;
            }
        } else {
            in = cr.openInputStream(u);
            if (in == null) throw new IOException("Berkas tidak bisa dibuka");
        }
        if (mime == null || mime.equals("application/octet-stream") || mime.isEmpty()) {
            String g = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ekstensi(nama));
            mime = g != null ? g : "application/octet-stream";
        }
        l.nama = nama;
        l.mime = mime;
        l.ukuran = ukuran;
        ubah.run();
        if (ukuran > MAKS_UKURAN) {
            in.close();
            throw new IOException("Ukuran berkas lebih dari 100 MB, tidak diunggah");
        }
        File dir = new File(ctx.getCacheDir(), "lampiran");
        dir.mkdirs();
        File tujuan = new File(dir, l.idUnik + "_" + nama.replaceAll("[^A-Za-z0-9._-]", "_"));
        byte[] buf = new byte[65536];
        long total = 0;
        int terakhir = -1;
        try (InputStream i2 = in; OutputStream o = new FileOutputStream(tujuan)) {
            int r;
            while ((r = i2.read(buf)) != -1) {
                if (l.batal) throw new IOException("Dibatalkan");
                total += r;
                if (total > MAKS_UKURAN) throw new IOException("Ukuran berkas lebih dari 100 MB, tidak diunggah");
                o.write(buf, 0, r);
                if (ukuran > 0) {
                    int p = (int) (total * 100 / ukuran);
                    if (p != terakhir) { terakhir = p; l.progres = p; ubah.run(); }
                }
            }
        } catch (IOException e) {
            tujuan.delete();
            throw e;
        }
        l.berkas = tujuan;
        l.ukuran = total;
        l.progres = 100;
    }

    static void unggahGemini(String kunci, Lampiran l, Runnable ubah) throws Exception {
        if (miripTeks(l)) l.mime = "text/plain";
        long n = l.berkas.length();
        if (n == 0) throw new IOException("Berkas kosong");
        l.progres = 0;
        HttpURLConnection c1 = Jaringan.buka("https://generativelanguage.googleapis.com/upload/v1beta/files", "POST", 60000);
        c1.setRequestProperty("x-goog-api-key", kunci);
        c1.setRequestProperty("X-Goog-Upload-Protocol", "resumable");
        c1.setRequestProperty("X-Goog-Upload-Command", "start");
        c1.setRequestProperty("X-Goog-Upload-Header-Content-Length", String.valueOf(n));
        c1.setRequestProperty("X-Goog-Upload-Header-Content-Type", l.mime);
        c1.setRequestProperty("Content-Type", "application/json");
        c1.setDoOutput(true);
        byte[] meta = new JSONObject().put("file", new JSONObject().put("display_name", l.nama)).toString().getBytes(StandardCharsets.UTF_8);
        c1.setFixedLengthStreamingMode(meta.length);
        try (OutputStream o = c1.getOutputStream()) { o.write(meta); }
        int k = c1.getResponseCode();
        if (k >= 300) {
            String e = Jaringan.ringkasGalat(k, Jaringan.bacaGalat(c1));
            c1.disconnect();
            throw new IOException(e);
        }
        String url = c1.getHeaderField("X-Goog-Upload-URL");
        c1.disconnect();
        if (url == null) throw new IOException("Gemini tidak memberi alamat unggah");
        HttpURLConnection c2 = Jaringan.buka(url, "POST", 900000);
        c2.setRequestProperty("X-Goog-Upload-Offset", "0");
        c2.setRequestProperty("X-Goog-Upload-Command", "upload, finalize");
        c2.setDoOutput(true);
        c2.setFixedLengthStreamingMode(n);
        long kirim = 0;
        int terakhir = -1;
        byte[] buf = new byte[65536];
        try (OutputStream o = c2.getOutputStream(); InputStream in = new FileInputStream(l.berkas)) {
            int r;
            while ((r = in.read(buf)) != -1) {
                if (l.batal) throw new IOException("Dibatalkan");
                o.write(buf, 0, r);
                kirim += r;
                int p = (int) (kirim * 100 / n);
                if (p != terakhir) { terakhir = p; l.progres = p; if (ubah != null) ubah.run(); }
            }
        }
        k = c2.getResponseCode();
        if (k >= 300) {
            String e = Jaringan.ringkasGalat(k, Jaringan.bacaGalat(c2));
            c2.disconnect();
            throw new IOException(e);
        }
        String badan = Jaringan.bacaSemua(c2.getInputStream());
        c2.disconnect();
        JSONObject f = new JSONObject(badan).getJSONObject("file");
        String nama = f.optString("name");
        String state = f.optString("state", "ACTIVE");
        String uri = f.optString("uri");
        long batas = System.currentTimeMillis() + 15 * 60 * 1000L;
        while ("PROCESSING".equals(state) && System.currentTimeMillis() < batas) {
            if (l.batal) throw new IOException("Dibatalkan");
            Thread.sleep(2000);
            JSONObject j = Jaringan.ambilJson("https://generativelanguage.googleapis.com/v1beta/" + nama, "x-goog-api-key", kunci);
            state = j.optString("state", "ACTIVE");
            if (j.optString("uri").length() > 0) uri = j.optString("uri");
        }
        if ("FAILED".equals(state)) throw new IOException("Gemini gagal memproses berkas ini");
        if (uri.isEmpty()) throw new IOException("Gemini tidak memberi alamat berkas");
        l.geminiUri = uri;
        l.waktuUnggah = System.currentTimeMillis();
    }

    static String bacaBase64(File f) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.min(f.length(), 64L * 1024 * 1024));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) != -1) bo.write(buf, 0, r);
        }
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    static String gambarDataUrl(Lampiran l) throws IOException {
        String m = l.mime.toLowerCase(Locale.ROOT);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(l.berkas.getAbsolutePath(), o);
        int maks = Math.max(o.outWidth, o.outHeight);
        boolean umum = m.equals("image/jpeg") || m.equals("image/png") || m.equals("image/webp") || m.equals("image/gif");
        if (umum && l.berkas.length() <= 3_000_000L && maks <= 2560) {
            return "data:" + m + ";base64," + bacaBase64(l.berkas);
        }
        int sample = 1;
        while (maks / sample > 2560) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(l.berkas.getAbsolutePath(), o2);
        if (b == null) throw new IOException("Gambar " + l.nama + " tidak bisa dibaca");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 85, bo);
        b.recycle();
        return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }
}
