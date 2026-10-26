package com.chatai.studio;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Database obrolan. Hanya metadata; isi teks ada di Penyimpan. */
final class Basisdata extends SQLiteOpenHelper {
    private static Basisdata inst;
    private final Context app;

    static final class Baris {
        long id; String peran; long dibuat; int status; String model; String lampiran; int panjang; String galat;
    }

    static final class RowObrolan {
        long id; String judul; long diubah;
    }

    static synchronized Basisdata dapat(Context c) {
        if (inst == null) inst = new Basisdata(c.getApplicationContext());
        return inst;
    }

    private Basisdata(Context c) {
        super(c, "chatai.db", null, 1);
        app = c;
    }

    @Override public void onConfigure(SQLiteDatabase d) { d.enableWriteAheadLogging(); }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE obrolan(id INTEGER PRIMARY KEY AUTOINCREMENT, judul TEXT, dibuat INTEGER, diubah INTEGER)");
        d.execSQL("CREATE TABLE pesan(id INTEGER PRIMARY KEY AUTOINCREMENT, obrolan INTEGER NOT NULL, peran TEXT NOT NULL,"
                + " dibuat INTEGER, status INTEGER DEFAULT 0, model TEXT, lampiran TEXT, panjang INTEGER DEFAULT 0, galat TEXT)");
        d.execSQL("CREATE INDEX idx_pesan_obrolan ON pesan(obrolan, id)");
        d.execSQL("CREATE INDEX idx_obrolan_diubah ON obrolan(diubah)");
    }

    @Override public void onUpgrade(SQLiteDatabase d, int a, int b) { }

    long buatObrolan(String judul) {
        ContentValues v = new ContentValues();
        long t = System.currentTimeMillis();
        v.put("judul", judul); v.put("dibuat", t); v.put("diubah", t);
        return getWritableDatabase().insertOrThrow("obrolan", null, v);
    }

    long tambahPesan(long obrolan, String peran, int status, String model, String lampiran) {
        SQLiteDatabase d = getWritableDatabase();
        ContentValues v = new ContentValues();
        long t = System.currentTimeMillis();
        v.put("obrolan", obrolan); v.put("peran", peran); v.put("dibuat", t);
        v.put("status", status); v.put("model", model); v.put("lampiran", lampiran);
        long id = d.insertOrThrow("pesan", null, v);
        ContentValues u = new ContentValues();
        u.put("diubah", t);
        d.update("obrolan", u, "id=?", new String[]{String.valueOf(obrolan)});
        return id;
    }

    void perbaruiPesan(long id, int status, String model, int panjang, String galat) {
        ContentValues v = new ContentValues();
        v.put("status", status); v.put("model", model); v.put("panjang", panjang); v.put("galat", galat);
        getWritableDatabase().update("pesan", v, "id=?", new String[]{String.valueOf(id)});
    }

    void setLampiran(long id, String json) {
        ContentValues v = new ContentValues();
        v.put("lampiran", json);
        getWritableDatabase().update("pesan", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** Pesan dengan id lebih kecil dari "sebelum", urut naik (terlama ke terbaru). */
    List<Baris> muat(long obrolan, long sebelum, int batas) {
        List<Baris> l = new ArrayList<>();
        String sql = "SELECT id,peran,dibuat,status,model,lampiran,panjang,galat FROM pesan WHERE obrolan=" + obrolan
                + " AND id<" + sebelum + " ORDER BY id DESC LIMIT " + batas;
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                Baris b = new Baris();
                b.id = c.getLong(0); b.peran = c.getString(1); b.dibuat = c.getLong(2); b.status = c.getInt(3);
                b.model = c.getString(4); b.lampiran = c.getString(5); b.panjang = c.getInt(6); b.galat = c.getString(7);
                l.add(b);
            }
        }
        Collections.reverse(l);
        return l;
    }

    boolean adaSebelum(long obrolan, long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM pesan WHERE obrolan=" + obrolan + " AND id<" + id + " LIMIT 1", null)) {
            return c.moveToFirst();
        }
    }

    long obrolanTerakhir() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM obrolan ORDER BY diubah DESC, id DESC LIMIT 1", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    boolean adaObrolan(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM obrolan WHERE id=" + id, null)) {
            return c.moveToFirst();
        }
    }

    List<RowObrolan> daftarObrolan(int offset, int batas) {
        List<RowObrolan> l = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,judul,diubah FROM obrolan ORDER BY diubah DESC, id DESC LIMIT " + batas + " OFFSET " + offset, null)) {
            while (c.moveToNext()) {
                RowObrolan r = new RowObrolan();
                r.id = c.getLong(0); r.judul = c.getString(1); r.diubah = c.getLong(2);
                l.add(r);
            }
        }
        return l;
    }

    void hapusObrolan(long id) {
        SQLiteDatabase d = getWritableDatabase();
        List<Long> ids = new ArrayList<>();
        try (Cursor c = d.rawQuery("SELECT id FROM pesan WHERE obrolan=" + id, null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        for (long p : ids) Penyimpan.hapus(app, p);
        d.delete("pesan", "obrolan=?", new String[]{String.valueOf(id)});
        d.delete("obrolan", "id=?", new String[]{String.valueOf(id)});
    }

    void bersihkanTerhenti() {
        getWritableDatabase().execSQL("UPDATE pesan SET status=2, galat='Terhenti karena aplikasi ditutup.' WHERE status=1");
    }
}
