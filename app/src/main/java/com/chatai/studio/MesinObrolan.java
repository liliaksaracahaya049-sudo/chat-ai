package com.chatai.studio;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Mesin yang menjalankan permintaan ke AI di thread latar. Tetap jalan walau layar ditutup;
 * tampilan cukup "menarik" isi terbaru lewat Pekerjaan.
 */
final class MesinObrolan {
    static final String SISTEM = "Kamu adalah asisten AI yang membantu. Jawab dengan bahasa yang dipakai pengguna. "
            + "Jika diminta menulis kode, cerita, atau teks panjang, selesaikan sampai tuntas tanpa memotong atau meringkas.";
    static final String LANJUT = "Lanjutkan jawabanmu tepat dari titik terakhir yang terpotong. Jangan mengulang bagian sebelumnya, "
            + "jangan beri kata pembuka, langsung teruskan teksnya (termasuk kode atau format yang sedang berjalan).";
    private static final int MAKS_RONDE = 60;
    private static final long TUJUH_BELAS_JAM_MS = 47L * 3600 * 1000;

    private static MesinObrolan instans;

    static synchronized MesinObrolan dapat(Context c) {
        if (instans == null) instans = new MesinObrolan(c.getApplicationContext());
        return instans;
    }

    // ---------- tipe data ----------
    static final class Giliran {
        final String peran;
        final String teks;
        final List<Lampiran> lampiran = new ArrayList<>();
        Giliran(String peran, String teks) { this.peran = peran; this.teks = teks; }
    }

    static final class Hasil {
        boolean ok, terpotong;
        String galat;
        int kode;
        static Hasil sukses() { Hasil h = new Hasil(); h.ok = true; return h; }
        static Hasil terpotongSaja() { Hasil h = sukses(); h.terpotong = true; return h; }
        static Hasil gagal(int kode, String galat) { Hasil h = new Hasil(); h.kode = kode; h.galat = galat; return h; }
        boolean bolehAlih() { return !ok && kode != 401 && kode != 403 && !galatKunci(galat); }
    }

    static boolean galatKunci(String g) {
        if (g == null) return false;
        String x = g.toLowerCase(Locale.ROOT);
        return x.contains("api key not valid") || x.contains("api_key_invalid") || x.contains("invalid api key")
                || x.contains("incorrect api key") || x.contains("invalid_api_key") || x.contains("unauthorized");
    }

    static final class Pekerjaan {
        final long obrolanId, pesanUserId, pesanAiId;
        private final StringBuilder teks = new StringBuilder();
        private final StringBuilder pikir = new StringBuilder();
        private final List<String[]> sumber = new ArrayList<>();
        final AtomicBoolean batal = new AtomicBoolean(false);
        volatile String status = "Menyiapkan…";
        volatile String galat;
        volatile String modelAktif = "";
        volatile boolean selesai;
        volatile int ronde;
        volatile HttpURLConnection koneksi;
        private boolean sedangMenulis;
        private long simpanTerakhir;
        private final Context app;

        Pekerjaan(Context app, long o, long u, long a) { this.app = app; obrolanId = o; pesanUserId = u; pesanAiId = a; }

        synchronized void mulaiRonde(int r) { ronde = r; sedangMenulis = false; }

        void tambahTeks(String s) {
            boolean simpan = false;
            synchronized (this) {
                teks.append(s);
                if (!sedangMenulis) {
                    sedangMenulis = true;
                    status = ronde > 0 ? "AI sedang menulis (lanjutan ke-" + ronde + ")…" : "AI sedang menulis…";
                }
                long t = System.currentTimeMillis();
                if (t - simpanTerakhir > 5000) { simpanTerakhir = t; simpan = true; }
            }
            if (simpan) {
                try { Penyimpan.tulisTeks(app, pesanAiId, semuaTeks()); } catch (Throwable ignored) { }
            }
        }

        synchronized void tambahPikir(String s) { pikir.append(s); }
        synchronized String ambilTeks(int dari) { return dari >= teks.length() ? "" : teks.substring(dari); }
        synchronized int panjangTeks() { return teks.length(); }
        synchronized String semuaTeks() { return teks.toString(); }
        synchronized int panjangPikir() { return pikir.length(); }
        synchronized String ambilPikir(int maks) { return pikir.length() <= maks ? pikir.toString() : pikir.substring(0, maks); }
        synchronized String semuaPikir() { return pikir.toString(); }

        synchronized void tambahSumber(String judul, String url) {
            if (url == null || url.isEmpty()) return;
            for (String[] s : sumber) if (s[1].equals(url)) return;
            sumber.add(new String[]{judul == null ? url : judul, url});
        }

        synchronized String teksSumber() {
            if (sumber.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\n\nSumber pencarian:\n");
            for (int i = 0; i < sumber.size(); i++) sb.append(i + 1).append(". ").append(sumber.get(i)[0]).append(" — ").append(sumber.get(i)[1]).append('\n');
            return sb.toString();
        }

        void batalkan() {
            batal.set(true);
            final HttpURLConnection c = koneksi;
            if (c != null) new Thread(() -> { try { c.disconnect(); } catch (Throwable ignored) { } }).start();
        }
    }

    // ---------- mesin ----------
    private final Context app;
    private final Basisdata db;
    private final Pengaturan set;
    private final ExecutorService eks = Executors.newSingleThreadExecutor();
    volatile Pekerjaan aktif;

    private MesinObrolan(Context app) {
        this.app = app;
        this.db = Basisdata.dapat(app);
        this.set = new Pengaturan(app);
        try { db.bersihkanTerhenti(); } catch (Throwable ignored) { }
    }

    boolean sibuk() { Pekerjaan p = aktif; return p != null && !p.selesai; }

    static String pesan(Throwable t) { return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(); }

    Pekerjaan mulai(long obrolanId, final String teksUser, final List<Lampiran> lampiran) {
        long idUser = db.tambahPesan(obrolanId, "user", 0, null, lampiranKeJson(lampiran));
        try { Penyimpan.tulisTeks(app, idUser, teksUser); } catch (Throwable ignored) { }
        db.perbaruiPesan(idUser, 0, null, teksUser.length(), null);
        InfoModel m = set.modelTerpilih(set.provider());
        long idAi = db.tambahPesan(obrolanId, "assistant", 1, m.id, null);
        final Pekerjaan job = new Pekerjaan(app, obrolanId, idUser, idAi);
        job.modelAktif = m.id;
        aktif = job;
        LayananObrolan.mulai(app);
        final List<Lampiran> salinan = new ArrayList<>(lampiran);
        eks.execute(() -> {
            try {
                jalankan(job, teksUser, salinan);
            } catch (Throwable t) {
                job.galat = "Galat tak terduga: " + pesan(t);
            } finally {
                akhiri(job);
            }
        });
        return job;
    }

    private void akhiri(Pekerjaan job) {
        try {
            String sumber = job.teksSumber();
            if (!sumber.isEmpty() && job.panjangTeks() > 0) job.tambahTeks(sumber);
            if (job.batal.get() && job.galat == null) job.galat = "Dihentikan oleh pengguna.";
            String t = job.semuaTeks();
            Penyimpan.tulisTeks(app, job.pesanAiId, t);
            Penyimpan.tulisPikir(app, job.pesanAiId, job.semuaPikir());
            db.perbaruiPesan(job.pesanAiId, job.galat != null ? 2 : 0, job.modelAktif, t.length(), job.galat);
        } catch (Throwable ignored) { }
        job.status = job.galat == null ? "Selesai." : "Berhenti: " + job.galat;
        job.selesai = true;
        LayananObrolan.henti(app);
    }

    // ---------- lampiran <-> json ----------
    static String lampiranKeJson(List<Lampiran> l) {
        JSONArray a = new JSONArray();
        try {
            for (Lampiran x : l) {
                JSONObject o = new JSONObject();
                o.put("n", x.nama); o.put("m", x.mime);
                o.put("u", x.geminiUri == null ? "" : x.geminiUri);
                o.put("t", x.waktuUnggah);
                a.put(o);
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    static List<Lampiran> lampiranDariJson(String json) {
        List<Lampiran> l = new ArrayList<>();
        if (json == null || json.isEmpty()) return l;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Lampiran x = new Lampiran();
                x.nama = o.optString("n", "berkas");
                x.mime = o.optString("m", "application/octet-stream");
                String u = o.optString("u", "");
                x.geminiUri = u.isEmpty() ? null : u;
                x.waktuUnggah = o.optLong("t", 0);
                l.add(x);
            }
        } catch (Exception ignored) { }
        return l;
    }

    static String bacaTeksBerkas(File f, int maks) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) {
                if (sb.length() + n > maks) {
                    sb.append(buf, 0, maks - sb.length());
                    sb.append("\n…[isi berkas dipotong karena terlalu panjang]");
                    break;
                }
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    // ---------- inti ----------
    private InfoModel cari(String prov, String id) {
        for (InfoModel m : set.daftarModel(prov)) if (m.id.equals(id)) return m;
        return InfoModel.tebak(prov, id);
    }

    private long anggaran(String prov, InfoModel m) {
        long k = m.konteks;
        switch (prov) {
            case Penyedia.GEMINI: return k > 0 ? Math.min(k * 3, 600_000L) : 600_000L;
            case Penyedia.GROQ: return 40_000L;
            default: return k > 0 ? Math.min(k * 3, 300_000L) : 100_000L;
        }
    }

    private void siapkanLampiran(Pekerjaan job, String prov, String kunci, InfoModel m, List<Lampiran> lampiran) throws Exception {
        int kapasitas = (int) Math.max(20_000L, anggaran(prov, m) / 2);
        for (Lampiran l : lampiran) {
            if (l.tahap != Lampiran.SIAP || l.berkas == null) throw new IOException("Lampiran '" + l.nama + "' belum siap.");
            String mime = l.mime == null ? "" : l.mime.toLowerCase(Locale.ROOT);
            if (PengelolaLampiran.miripTeks(l)) {
                if (Penyedia.GEMINI.equals(prov) && l.berkas.length() > 200_000L) {
                    boolean lewat = l.geminiUri == null || System.currentTimeMillis() - l.waktuUnggah > TUJUH_BELAS_JAM_MS;
                    if (lewat) {
                        job.status = "Mengunggah " + l.nama + "…";
                        PengelolaLampiran.unggahGemini(kunci, l, null);
                    }
                    continue;
                }
                l.teksInline = bacaTeksBerkas(l.berkas, kapasitas);
                continue;
            }
            if (Penyedia.GEMINI.equals(prov)) {
                boolean kadaluarsa = l.geminiUri == null || System.currentTimeMillis() - l.waktuUnggah > TUJUH_BELAS_JAM_MS;
                if (kadaluarsa) {
                    job.status = "Mengunggah " + l.nama + "…";
                    PengelolaLampiran.unggahGemini(kunci, l, null);
                }
            } else if (mime.startsWith("image/")) {
                l.dataUrl = PengelolaLampiran.gambarDataUrl(l);
            } else if (Penyedia.OPENROUTER.equals(prov) && mime.equals("application/pdf") && l.berkas.length() <= 25L * 1024 * 1024) {
                l.dataUrl = "data:application/pdf;base64," + PengelolaLampiran.bacaBase64(l.berkas);
            } else {
                throw new IOException("Provider " + Penyedia.nama(prov) + " tidak mendukung lampiran " + l.nama);
            }
        }
    }

    private void jalankan(Pekerjaan job, String teksUser, List<Lampiran> lampiran) throws Exception {
        final String prov = set.provider();
        final String kunci = set.kunci(prov);
        if (kunci.isEmpty()) {
            job.galat = "API key " + Penyedia.nama(prov) + " belum diisi. Buka Pengaturan untuk mengisinya.";
            return;
        }
        InfoModel awal = set.modelTerpilih(prov);
        boolean adaGambar = false;
        try {
            job.status = lampiran.isEmpty() ? "Menyiapkan pesan…" : "Menyiapkan lampiran…";
            siapkanLampiran(job, prov, kunci, awal, lampiran);
            db.setLampiran(job.pesanUserId, lampiranKeJson(lampiran));
            for (Lampiran l : lampiran) if (l.mime != null && l.mime.startsWith("image/")) adaGambar = true;
        } catch (Throwable t) {
            job.galat = "Lampiran bermasalah: " + pesan(t);
            return;
        }
        Set<String> gagal = new HashSet<>();
        String modelId = awal.id;
        int alih = 0;
        while (true) {
            InfoModel mm = cari(prov, modelId);
            job.modelAktif = mm.id;
            boolean pikir = set.berpikir() && mm.pikir;
            boolean web = set.cariWeb() && mm.web;
            List<Giliran> dasar = bangunRiwayat(job, prov, mm, teksUser, lampiran);
            Hasil h = putaran(job, prov, kunci, mm, dasar, pikir, web);
            if (h.ok) break;
            boolean adaIsi = job.panjangTeks() > 0;
            if (!adaIsi && !job.batal.get() && set.otomatisAlih() && h.bolehAlih() && alih < 6) {
                gagal.add(mm.id);
                InfoModel baru = pilihPengganti(prov, gagal, adaGambar);
                if (baru != null) {
                    String ringkas = h.galat == null ? "" : (h.galat.length() > 120 ? h.galat.substring(0, 120) + "…" : h.galat);
                    job.status = "Model " + mm.id + " gagal (" + ringkas + "). Beralih ke " + baru.id + "…";
                    modelId = baru.id;
                    alih++;
                    continue;
                }
            }
            job.galat = h.galat;
            break;
        }
    }

    private InfoModel pilihPengganti(String prov, Set<String> gagal, boolean perluGambar) {
        for (InfoModel m : set.daftarModel(prov)) {
            if (gagal.contains(m.id)) continue;
            if (perluGambar && !m.gambar) continue;
            return m;
        }
        return null;
    }

    private Hasil putaran(Pekerjaan job, String prov, String kunci, InfoModel m, List<Giliran> dasar, boolean pikir, boolean web) {
        int ronde = 0;
        while (true) {
            List<Giliran> kerja = new ArrayList<>(dasar);
            if (ronde > 0) {
                kerja.add(new Giliran("assistant", job.semuaTeks()));
                kerja.add(new Giliran("user", LANJUT));
            }
            job.mulaiRonde(ronde);
            job.status = ronde == 0 ? "Menunggu respons dari " + m.id + "…" : "Melanjutkan jawaban (bagian " + (ronde + 1) + ")…";
            Hasil h;
            try {
                h = Penyedia.GEMINI.equals(prov)
                        ? KlienGemini.kirim(job, kunci, m, kerja, pikir, web)
                        : KlienOpenAi.kirim(job, prov, kunci, m, kerja, pikir, web);
            } catch (Throwable t) {
                h = Hasil.gagal(0, "Koneksi bermasalah: " + pesan(t));
            }
            if (job.batal.get()) return Hasil.sukses();
            if (!h.ok) {
                if (job.panjangTeks() > 0 && h.galat != null) h.galat = h.galat + " (teks yang sudah masuk tetap disimpan)";
                return h;
            }
            if (h.terpotong) {
                if (++ronde > MAKS_RONDE) return Hasil.sukses();
                continue;
            }
            if (job.panjangTeks() == 0) return Hasil.gagal(0, "Model tidak memberi respons (kosong)");
            return h;
        }
    }

    private List<Giliran> bangunRiwayat(Pekerjaan job, String prov, InfoModel m, String teksUser, List<Lampiran> baru) {
        long batas = anggaran(prov, m);
        long dipakai = teksUser.length();
        for (Lampiran l : baru) if (l.teksInline != null) dipakai += l.teksInline.length();
        ArrayDeque<Giliran> tumpukan = new ArrayDeque<>();
        long sebelum = job.pesanUserId;
        boolean penuh = false;
        while (!penuh) {
            List<Basisdata.Baris> baris = db.muat(job.obrolanId, sebelum, 40);
            if (baris.isEmpty()) break;
            for (int i = baris.size() - 1; i >= 0; i--) {
                Basisdata.Baris r = baris.get(i);
                if (r.id == job.pesanAiId) continue;
                String t = Penyimpan.bacaTeks(app, r.id);
                boolean ai = "assistant".equals(r.peran);
                if (ai && t.isEmpty()) continue;
                if (dipakai + t.length() > batas) { penuh = true; break; }
                dipakai += t.length();
                Giliran g;
                if (ai) {
                    g = new Giliran("assistant", t);
                } else {
                    StringBuilder cat = new StringBuilder();
                    List<Lampiran> lama = new ArrayList<>();
                    for (Lampiran l : lampiranDariJson(r.lampiran)) {
                        boolean sah = Penyedia.GEMINI.equals(prov) && l.geminiUri != null
                                && System.currentTimeMillis() - l.waktuUnggah < TUJUH_BELAS_JAM_MS;
                        if (sah) lama.add(l);
                        else { if (cat.length() > 0) cat.append(", "); cat.append(l.nama); }
                    }
                    g = new Giliran("user", t + (cat.length() > 0 ? "\n\n[Lampiran pada pesan ini: " + cat + "]" : ""));
                    g.lampiran.addAll(lama);
                }
                tumpukan.addFirst(g);
            }
            if (!penuh) sebelum = baris.get(0).id;
        }
        while (!tumpukan.isEmpty() && "assistant".equals(tumpukan.peekFirst().peran)) tumpukan.pollFirst();
        List<Giliran> hasil = new ArrayList<>(tumpukan);
        Giliran sekarang = new Giliran("user", teksUser);
        sekarang.lampiran.addAll(baru);
        hasil.add(sekarang);
        return hasil;
    }
}
