package com.chatai.studio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int K_FOTO = 101, K_FILE = 102, K_DRIVE = 103;
    private static final int MAKS_LAMPIRAN = 10;
    private static final int BATAS_SALIN = 150_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService bgLampiran = Executors.newFixedThreadPool(2);

    private Pengaturan set;
    private Basisdata db;
    private MesinObrolan mesin;
    private RecyclerView rv;
    private LinearLayoutManager lm;
    private AdapterObrolan adapter;
    private final ArrayList<ItemObrolan> daftar = new ArrayList<>();
    private EditText kotak;
    private Button tombolKirim, tombolLampir;
    private TextView statusView, infoView;
    private LinearLayout panelLampiran;
    private SkrolTerbatas skrolLampiran;
    private final ArrayList<Lampiran> lampiranTertunda = new ArrayList<>();
    private long obrolanId = 0, idTertua = 0;
    private boolean adaLebihLama, memuatLama;
    private TampilanJob tj;

    /** ScrollView dengan tinggi maksimum. */
    static final class SkrolTerbatas extends ScrollView {
        int maks;
        SkrolTerbatas(Context c, int maks) { super(c); this.maks = maks; }
        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maks, View.MeasureSpec.AT_MOST));
        }
    }

    /** Keadaan tampilan untuk satu pekerjaan AI yang sedang berjalan. */
    private static final class TampilanJob {
        final MesinObrolan.Pekerjaan job;
        int offTeks, offPikir;
        final StringBuilder ekor = new StringBuilder();
        ItemObrolan itemEkor, itemPikir, itemAwalAi;
        String statusTerakhir = "";
        TampilanJob(MesinObrolan.Pekerjaan j) { job = j; }
    }

    private static final class Halaman {
        final List<ItemObrolan> items = new ArrayList<>();
        long idTertua;
        boolean lagi;
    }

    private int dp(int v) { return Sisi.dp(this, v); }

    // ------------------------------------------------------------ siklus hidup
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TTGuard.periksa(this);
        set = new Pengaturan(this);
        db = Basisdata.dapat(this);
        mesin = MesinObrolan.dapat(this);
        bangunUi();
        obrolanId = set.obrolanAktif();
        if (obrolanId != 0 && !db.adaObrolan(obrolanId)) obrolanId = 0;
        if (obrolanId == 0) obrolanId = db.obrolanTerakhir();
        MesinObrolan.Pekerjaan a = mesin.aktif;
        if (a != null && !a.selesai) obrolanId = a.obrolanId;
        set.setObrolanAktif(obrolanId);
        muatAwal();
    }

    @Override protected void onResume() {
        super.onResume();
        perbaruiInfo();
        if (tj != null) { ui.removeCallbacks(pollJalan); ui.post(pollJalan); }
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(pollJalan);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        bg.shutdown();
        bgLampiran.shutdown();
    }

    // ------------------------------------------------------------ tampilan
    private void bangunUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout kepala = new LinearLayout(this);
        kepala.setOrientation(LinearLayout.HORIZONTAL);
        kepala.setGravity(Gravity.CENTER_VERTICAL);
        kepala.setPadding(dp(12), dp(4), dp(4), 0);
        TextView judul = new TextView(this);
        judul.setText("Chat AI");
        judul.setTextSize(22);
        judul.setTypeface(null, Typeface.BOLD);
        judul.setTextColor(0xFF111111);
        ViewCompat.setAccessibilityHeading(judul, true);
        kepala.addView(judul, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button bRiwayat = tombolKecil("Riwayat");
        Button bBaru = tombolKecil("Baru");
        Button bAtur = tombolKecil("Pengaturan");
        bBaru.setContentDescription("Obrolan baru");
        kepala.addView(bRiwayat);
        kepala.addView(bBaru);
        kepala.addView(bAtur);
        root.addView(kepala);

        infoView = new TextView(this);
        infoView.setTextSize(13);
        infoView.setTextColor(0xFF555555);
        infoView.setPadding(dp(12), 0, dp(12), dp(4));
        root.addView(infoView);

        rv = new RecyclerView(this);
        lm = new LinearLayoutManager(this);
        rv.setLayoutManager(lm);
        adapter = new AdapterObrolan(this, daftar, aksi);
        rv.setAdapter(adapter);
        rv.setItemAnimator(null);
        rv.setItemViewCacheSize(8);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView r, int dx, int dy) {
                if (dy < 0 && adaLebihLama && !memuatLama && !r.canScrollVertically(-1)) muatLebihLama();
            }
        });
        root.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusView.setTextColor(0xFF333333);
        statusView.setPadding(dp(12), dp(2), dp(12), dp(2));
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusView.setVisibility(View.GONE);
        root.addView(statusView);

        panelLampiran = new LinearLayout(this);
        panelLampiran.setOrientation(LinearLayout.VERTICAL);
        skrolLampiran = new SkrolTerbatas(this, dp(190));
        skrolLampiran.addView(panelLampiran);
        skrolLampiran.setVisibility(View.GONE);
        root.addView(skrolLampiran, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout baris = new LinearLayout(this);
        baris.setOrientation(LinearLayout.HORIZONTAL);
        baris.setGravity(Gravity.BOTTOM);
        baris.setPadding(dp(4), dp(4), dp(4), dp(4));
        tombolLampir = tombolKecil("Lampirkan");
        kotak = new EditText(this);
        kotak.setHint("Tulis pesan");
        kotak.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        kotak.setMaxLines(6);
        kotak.setMinHeight(dp(48));
        kotak.setHorizontallyScrolling(false);
        tombolKirim = tombolKecil("Kirim");
        baris.addView(tombolLampir);
        baris.addView(kotak, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        baris.addView(tombolKirim);
        root.addView(baris);

        setContentView(root);
        Sisi.terapkanInset(root);

        bAtur.setOnClickListener(v -> startActivity(new Intent(this, PengaturanActivity.class)));
        bRiwayat.setOnClickListener(v -> tampilRiwayat());
        bBaru.setOnClickListener(v -> obrolanBaru());
        tombolLampir.setOnClickListener(v -> tampilMenuLampir());
        tombolKirim.setOnClickListener(v -> tanganiKirim());
    }

    private Button tombolKecil(String t) {
        Button b = Sisi.tombol(this, t);
        b.setTextSize(14);
        b.setPadding(dp(10), 0, dp(10), 0);
        return b;
    }

    private void perbaruiInfo() {
        String prov = set.provider();
        InfoModel m = set.modelTerpilih(prov);
        String s = Penyedia.nama(prov) + " • " + m.id;
        if (set.kunci(prov).isEmpty()) s += " • API key belum diisi (buka Pengaturan)";
        infoView.setText(s);
    }

    private void tampilStatus(String s) {
        if (s == null || s.isEmpty()) { statusView.setVisibility(View.GONE); return; }
        statusView.setText(s);
        statusView.setVisibility(View.VISIBLE);
    }

    private void pengumuman(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
        tampilStatus(s);
    }

    private void setSibuk(boolean sibuk) {
        tombolKirim.setText(sibuk ? "Hentikan" : "Kirim");
        tombolKirim.setContentDescription(sibuk ? "Hentikan AI menulis" : "Kirim pesan");
    }

    private void keBawah(final boolean awal) {
        rv.post(() -> {
            int n = daftar.size();
            if (n == 0) return;
            if (awal) lm.scrollToPositionWithOffset(n - 1, 0);
            rv.post(() -> rv.scrollBy(0, 100000));
        });
    }

    // ------------------------------------------------------------ memuat riwayat
    private final AdapterObrolan.Aksi aksi = new AdapterObrolan.Aksi() {
        @Override public void salin(long id) { salinPesan(id); }
        @Override public void bagikan(long id) { bagikanPesan(id); }
        @Override public void pilihTeks(long id) {
            Intent i = new Intent(MainActivity.this, PilihTeksActivity.class);
            i.putExtra("id", id);
            startActivity(i);
        }
        @Override public void muatLama() { muatLebihLama(); }
    };

    private List<ItemObrolan> itemPesan(Basisdata.Baris r, String teks, String pikir) {
        List<ItemObrolan> out = new ArrayList<>();
        if ("user".equals(r.peran)) {
            List<Lampiran> lamp = MesinObrolan.lampiranDariJson(r.lampiran);
            if (!lamp.isEmpty()) {
                StringBuilder sb = new StringBuilder("Lampiran: ");
                for (int i = 0; i < lamp.size(); i++) { if (i > 0) sb.append(", "); sb.append(lamp.get(i).nama); }
                ItemObrolan info = new ItemObrolan(ItemObrolan.INFO);
                info.teks = sb.toString();
                out.add(info);
            }
            List<String> segs = Segmen.potongSemua(teks, Segmen.UKURAN);
            for (int i = 0; i < segs.size(); i++) {
                ItemObrolan it = new ItemObrolan(ItemObrolan.PENGGUNA);
                it.pesanId = r.id; it.teks = segs.get(i); it.pertama = i == 0; it.label = "Kamu";
                out.add(it);
            }
        } else {
            if (pikir != null && !pikir.isEmpty()) {
                ItemObrolan p = new ItemObrolan(ItemObrolan.PIKIR);
                p.pesanId = r.id;
                p.teks = pikir.length() > 30000 ? pikir.substring(0, 30000) : pikir;
                out.add(p);
            }
            boolean kode = false;
            List<String> segs = Segmen.potongSemua(teks, Segmen.UKURAN);
            for (int i = 0; i < segs.size(); i++) {
                ItemObrolan it = new ItemObrolan(ItemObrolan.AI);
                it.pesanId = r.id; it.teks = segs.get(i); it.pertama = i == 0;
                it.label = "AI" + (r.model != null && !r.model.isEmpty() ? " (" + r.model + ")" : "");
                it.awalKode = kode;
                kode = Segmen.akhirKode(segs.get(i), kode);
                out.add(it);
            }
            if (r.galat != null && !r.galat.isEmpty()) {
                ItemObrolan g = new ItemObrolan(ItemObrolan.GALAT);
                g.teks = r.galat;
                out.add(g);
            }
            if (!teks.isEmpty()) {
                ItemObrolan a = new ItemObrolan(ItemObrolan.AKSI);
                a.pesanId = r.id;
                out.add(a);
            }
        }
        return out;
    }

    private Halaman muatHalaman(long oid, long sebelum, int jumlah, long idAiAktif) {
        List<Basisdata.Baris> rows = db.muat(oid, sebelum, jumlah);
        Halaman h = new Halaman();
        if (rows.isEmpty()) return h;
        String[] t = new String[rows.size()];
        String[] p = new String[rows.size()];
        long total = 0;
        int mulai = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            Basisdata.Baris r = rows.get(i);
            if (r.id == idAiAktif) { t[i] = ""; continue; }
            t[i] = Penyimpan.bacaTeks(this, r.id);
            p[i] = "assistant".equals(r.peran) ? Penyimpan.bacaPikir(this, r.id) : "";
            total += t[i].length();
            if (total > 1_500_000L && i < rows.size() - 1) { mulai = i + 1; break; }
        }
        for (int i = mulai; i < rows.size(); i++) {
            Basisdata.Baris r = rows.get(i);
            if (r.id == idAiAktif) continue;
            h.items.addAll(itemPesan(r, t[i], p[i]));
        }
        h.idTertua = rows.get(mulai).id;
        h.lagi = db.adaSebelum(oid, h.idTertua);
        return h;
    }

    private ItemObrolan itemMuat() {
        ItemObrolan m = new ItemObrolan(ItemObrolan.MUAT);
        return m;
    }

    private void muatAwal() {
        daftar.clear();
        adapter.notifyDataSetChanged();
        tj = null;
        setSibuk(false);
        if (obrolanId == 0) { tampilStatus("Obrolan kosong. Tulis pesan di bawah."); return; }
        final long oid = obrolanId;
        MesinObrolan.Pekerjaan a = mesin.aktif;
        final MesinObrolan.Pekerjaan jobSaatIni = (a != null && !a.selesai && a.obrolanId == oid) ? a : null;
        tampilStatus("Memuat obrolan…");
        bg.execute(() -> {
            final Halaman h = muatHalaman(oid, Long.MAX_VALUE, 30, jobSaatIni != null ? jobSaatIni.pesanAiId : -1);
            runOnUiThread(() -> {
                if (oid != obrolanId) return;
                idTertua = h.idTertua;
                adaLebihLama = h.lagi;
                if (h.lagi) daftar.add(itemMuat());
                daftar.addAll(h.items);
                adapter.notifyDataSetChanged();
                tampilStatus(daftar.isEmpty() ? "Obrolan kosong. Tulis pesan di bawah." : "");
                keBawah(true);
                if (jobSaatIni != null) lekat(jobSaatIni);
            });
        });
    }

    private void muatLebihLama() {
        if (memuatLama || !adaLebihLama || obrolanId == 0) return;
        memuatLama = true;
        final long oid = obrolanId, sebelum = idTertua;
        MesinObrolan.Pekerjaan a = mesin.aktif;
        final long idAi = (a != null && !a.selesai && a.obrolanId == oid) ? a.pesanAiId : -1;
        bg.execute(() -> {
            final Halaman h = muatHalaman(oid, sebelum, 30, idAi);
            runOnUiThread(() -> {
                memuatLama = false;
                if (oid != obrolanId) return;
                if (h.items.isEmpty()) { adaLebihLama = false; return; }
                View first = rv.getChildAt(0);
                int off = first == null ? 0 : first.getTop();
                int pos = lm.findFirstVisibleItemPosition();
                int lama = 0;
                if (!daftar.isEmpty() && daftar.get(0).jenis == ItemObrolan.MUAT) { daftar.remove(0); lama = 1; }
                idTertua = h.idTertua;
                adaLebihLama = h.lagi;
                int tambah = h.items.size();
                daftar.addAll(0, h.items);
                if (h.lagi) { daftar.add(0, itemMuat()); tambah++; }
                adapter.notifyDataSetChanged();
                if (pos >= 0) lm.scrollToPositionWithOffset(Math.max(0, pos - lama + tambah), off);
                tampilStatus("Pesan lebih lama dimuat.");
            });
        });
    }

    // ------------------------------------------------------------ kirim
    private void tanganiKirim() {
        if (mesin.sibuk()) {
            MesinObrolan.Pekerjaan j = mesin.aktif;
            if (j != null) { j.batalkan(); tampilStatus("Menghentikan…"); }
            return;
        }
        String teks = kotak.getText().toString().trim();
        if (teks.isEmpty() && lampiranTertunda.isEmpty()) {
            Toast.makeText(this, "Tulis pesan dulu", Toast.LENGTH_SHORT).show();
            return;
        }
        for (Lampiran l : lampiranTertunda) {
            if (l.tahap == Lampiran.GAGAL) { pengumuman("Lampiran " + l.nama + " gagal. Hapus dulu lampiran itu."); return; }
            if (l.tahap != Lampiran.SIAP) { pengumuman("Tunggu, lampiran " + l.nama + " belum selesai diunggah."); return; }
        }
        String prov = set.provider();
        if (set.kunci(prov).isEmpty()) { pengumuman("API key " + Penyedia.nama(prov) + " belum diisi. Buka Pengaturan."); return; }
        InfoModel im = set.modelTerpilih(prov);
        for (Lampiran l : lampiranTertunda) {
            String e = cekLampiran(l, prov, im);
            if (e != null) { pengumuman(e); return; }
        }
        if (Build.VERSION.SDK_INT >= 33 && !set.notifDiminta()
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            set.setNotifDiminta();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
        final String isi = teks.isEmpty() ? "Tolong jelaskan isi lampiran ini." : teks;
        if (obrolanId == 0) {
            String j = isi.replace('\n', ' ');
            obrolanId = db.buatObrolan(j.length() > 60 ? j.substring(0, 60) + "…" : j);
            set.setObrolanAktif(obrolanId);
        }
        ArrayList<Lampiran> kirimList = new ArrayList<>(lampiranTertunda);
        lampiranTertunda.clear();
        panelLampiran.removeAllViews();
        skrolLampiran.setVisibility(View.GONE);
        kotak.setText("");
        tampilStatus("");
        MesinObrolan.Pekerjaan job = mesin.mulai(obrolanId, isi, kirimList);

        int awal = daftar.size();
        if (!kirimList.isEmpty()) {
            StringBuilder sb = new StringBuilder("Lampiran: ");
            for (int i = 0; i < kirimList.size(); i++) { if (i > 0) sb.append(", "); sb.append(kirimList.get(i).nama); }
            ItemObrolan info = new ItemObrolan(ItemObrolan.INFO);
            info.teks = sb.toString();
            daftar.add(info);
        }
        List<String> segs = Segmen.potongSemua(isi, Segmen.UKURAN);
        for (int i = 0; i < segs.size(); i++) {
            ItemObrolan it = new ItemObrolan(ItemObrolan.PENGGUNA);
            it.pesanId = job.pesanUserId; it.teks = segs.get(i); it.pertama = i == 0; it.label = "Kamu";
            daftar.add(it);
        }
        adapter.notifyItemRangeInserted(awal, daftar.size() - awal);
        lekat(job);
    }

    private String cekLampiran(Lampiran l, String prov, InfoModel im) {
        if (Penyedia.GEMINI.equals(prov)) return null;
        if (PengelolaLampiran.miripTeks(l)) return null;
        String mime = l.mime == null ? "" : l.mime;
        if (mime.startsWith("image/")) {
            return im.gambar ? null : "Model " + im.id + " tidak mendukung gambar. Pilih model yang mendukung gambar, atau pakai Gemini.";
        }
        if (mime.equals("application/pdf") && Penyedia.OPENROUTER.equals(prov)) {
            return l.ukuran > 25L * 1024 * 1024 ? "PDF terlalu besar untuk Open Router (maksimal 25 MB). Pakai Gemini." : null;
        }
        return "Provider " + Penyedia.nama(prov) + " belum mendukung lampiran jenis ini (" + l.nama + "). Pakai Gemini untuk video, audio, atau PDF.";
    }

    // ------------------------------------------------------------ pekerjaan AI berjalan
    private final Runnable pollJalan = new Runnable() { @Override public void run() { poll(); } };

    private void lekat(MesinObrolan.Pekerjaan job) {
        tj = new TampilanJob(job);
        ItemObrolan ekor = new ItemObrolan(ItemObrolan.AI);
        ekor.pesanId = job.pesanAiId;
        ekor.pertama = true;
        ekor.label = "AI (" + job.modelAktif + ")";
        tj.itemEkor = ekor;
        tj.itemAwalAi = ekor;
        daftar.add(ekor);
        adapter.notifyItemInserted(daftar.size() - 1);
        setSibuk(true);
        tampilStatus(job.status);
        keBawah(false);
        ui.removeCallbacks(pollJalan);
        ui.post(pollJalan);
    }

    private void poll() {
        final TampilanJob t = tj;
        if (t == null) return;
        final MesinObrolan.Pekerjaan j = t.job;
        final boolean fin = j.selesai;
        boolean dibawah = !rv.canScrollVertically(1);
        boolean berubah = false;
        String d = j.ambilTeks(t.offTeks);
        if (!d.isEmpty()) { t.offTeks += d.length(); tambahTeksAi(t, d); berubah = true; }
        int pl = j.panjangPikir();
        if (pl != t.offPikir) { t.offPikir = pl; perbaruiPikir(t, j.ambilPikir(30000)); berubah = true; }
        if (!fin) {
            String st = (j.panjangTeks() == 0 && pl > 0) ? "AI sedang berpikir…" : j.status;
            if (!st.equals(t.statusTerakhir)) { t.statusTerakhir = st; tampilStatus(st); }
            lekatLabel(t);
        }
        if (fin) { selesaikan(t, dibawah); return; }
        if (berubah && dibawah) keBawah(false);
        ui.postDelayed(pollJalan, 150);
    }

    private void lekatLabel(TampilanJob t) {
        String lbl = "AI (" + t.job.modelAktif + ")";
        if (!lbl.equals(t.itemAwalAi.label)) {
            t.itemAwalAi.label = lbl;
            int i = daftar.indexOf(t.itemAwalAi);
            if (i >= 0) adapter.notifyItemChanged(i);
        }
    }

    private void tambahTeksAi(TampilanJob t, String d) {
        t.ekor.append(d);
        while (t.ekor.length() > Segmen.BATAS_EKOR) {
            String s = t.ekor.toString();
            int end = Segmen.potongSatu(s, 0, Segmen.UKURAN + 200);
            String seg = s.substring(0, end);
            String sisa = s.substring(end);
            t.itemEkor.teks = seg;
            int idx = daftar.indexOf(t.itemEkor);
            ItemObrolan baru = new ItemObrolan(ItemObrolan.AI);
            baru.pesanId = t.job.pesanAiId;
            baru.awalKode = Segmen.akhirKode(seg, t.itemEkor.awalKode);
            baru.teks = sisa;
            daftar.add(idx + 1, baru);
            adapter.notifyItemChanged(idx);
            adapter.notifyItemInserted(idx + 1);
            t.itemEkor = baru;
            t.ekor.setLength(0);
            t.ekor.append(sisa);
        }
        t.itemEkor.teks = t.ekor.toString();
        int idx = daftar.size() - 1;
        if (idx < 0 || daftar.get(idx) != t.itemEkor) idx = daftar.indexOf(t.itemEkor);
        if (idx >= 0) adapter.notifyItemChanged(idx);
    }

    private void perbaruiPikir(TampilanJob t, String teks) {
        if (t.itemPikir == null) {
            ItemObrolan p = new ItemObrolan(ItemObrolan.PIKIR);
            p.pesanId = t.job.pesanAiId;
            p.teks = teks;
            t.itemPikir = p;
            int pos = daftar.indexOf(t.itemAwalAi);
            if (pos < 0) pos = daftar.size();
            daftar.add(pos, p);
            adapter.notifyItemInserted(pos);
        } else {
            t.itemPikir.teks = teks;
            int i = daftar.indexOf(t.itemPikir);
            if (i >= 0) adapter.notifyItemChanged(i);
        }
    }

    private void selesaikan(TampilanJob t, boolean dibawah) {
        MesinObrolan.Pekerjaan j = t.job;
        if (t.ekor.length() == 0) {
            int idx = daftar.indexOf(t.itemEkor);
            if (idx >= 0) { daftar.remove(idx); adapter.notifyItemRemoved(idx); }
        }
        if (j.galat != null) {
            ItemObrolan g = new ItemObrolan(ItemObrolan.GALAT);
            g.teks = j.galat;
            daftar.add(g);
            adapter.notifyItemInserted(daftar.size() - 1);
        }
        if (j.panjangTeks() > 0) {
            ItemObrolan a = new ItemObrolan(ItemObrolan.AKSI);
            a.pesanId = j.pesanAiId;
            daftar.add(a);
            adapter.notifyItemInserted(daftar.size() - 1);
        }
        tj = null;
        setSibuk(false);
        String s = j.galat == null ? "Jawaban selesai." : "Berhenti: " + j.galat;
        tampilStatus(s);
        rv.announceForAccessibility(s);
        if (dibawah) keBawah(false);
    }

    // ------------------------------------------------------------ aksi pesan
    private void bagikanPesan(final long id) {
        bg.execute(() -> {
            final String t = Penyimpan.bacaTeks(this, id);
            Uri uri = null;
            if (t.length() > BATAS_SALIN) {
                try { uri = tulisBerkasBagikan(id, t); } catch (Throwable ignored) { }
            }
            final Uri u = uri;
            runOnUiThread(() -> {
                if (t.isEmpty()) return;
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    if (t.length() <= BATAS_SALIN) {
                        i.putExtra(Intent.EXTRA_TEXT, t);
                    } else if (u != null) {
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_STREAM, u);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        Toast.makeText(this, "Teks sangat panjang, dibagikan sebagai berkas .txt", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Gagal menyiapkan berkas untuk dibagikan", Toast.LENGTH_LONG).show();
                        return;
                    }
                    startActivity(Intent.createChooser(i, "Bagikan jawaban"));
                } catch (Throwable e) {
                    Toast.makeText(this, "Tidak bisa membagikan: " + MesinObrolan.pesan(e), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private Uri tulisBerkasBagikan(long id, String t) throws Exception {
        File dir = new File(getCacheDir(), "bagikan");
        dir.mkdirs();
        File f = new File(dir, "jawaban-ai-" + id + ".txt");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            for (int i = 0; i < t.length(); i += 8192) w.write(t, i, Math.min(8192, t.length() - i));
        }
        return FileProvider.getUriForFile(this, getPackageName() + ".berkas", f);
    }

    private void salinPesan(final long id) {
        bg.execute(() -> {
            final String t = Penyimpan.bacaTeks(this, id);
            runOnUiThread(() -> {
                if (t.isEmpty()) return;
                if (t.length() > BATAS_SALIN) {
                    Toast.makeText(this, "Teks terlalu panjang untuk papan klip, dibagikan sebagai berkas.", Toast.LENGTH_LONG).show();
                    bagikanPesan(id);
                    return;
                }
                try {
                    ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Jawaban AI", t));
                    Toast.makeText(this, "Jawaban tersalin", Toast.LENGTH_SHORT).show();
                } catch (Throwable e) {
                    bagikanPesan(id);
                }
            });
        });
    }

    // ------------------------------------------------------------ riwayat
    private void obrolanBaru() {
        if (mesin.sibuk()) { pengumuman("Tunggu AI selesai atau hentikan dulu."); return; }
        obrolanId = 0;
        set.setObrolanAktif(0);
        muatAwal();
    }

    private void tampilRiwayat() {
        if (mesin.sibuk()) { pengumuman("Tunggu AI selesai atau hentikan dulu."); return; }
        final ArrayList<Basisdata.RowObrolan> list = new ArrayList<>(db.daftarObrolan(0, 50));
        if (list.isEmpty()) { pengumuman("Belum ada riwayat obrolan."); return; }
        final ArrayList<String> teks = new ArrayList<>();
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, teks);
        final ListView lv = new ListView(this);
        final Button lagi = Sisi.tombol(this, "Muat lebih banyak");
        lv.addFooterView(lagi);
        lv.setAdapter(ad);
        final DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        final Runnable isi = () -> {
            teks.clear();
            for (Basisdata.RowObrolan r : list) teks.add((r.judul == null ? "Obrolan" : r.judul) + "\n" + df.format(new Date(r.diubah)));
            ad.notifyDataSetChanged();
            lagi.setVisibility(list.size() % 50 == 0 ? View.VISIBLE : View.GONE);
        };
        isi.run();
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Riwayat obrolan").setView(lv)
                .setNegativeButton("Tutup", null).create();
        lagi.setOnClickListener(v -> { list.addAll(db.daftarObrolan(list.size(), 50)); isi.run(); });
        lv.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= list.size()) return;
            final Basisdata.RowObrolan r = list.get(pos);
            dlg.dismiss();
            new AlertDialog.Builder(this).setTitle(r.judul == null ? "Obrolan" : r.judul)
                    .setItems(new String[]{"Buka obrolan", "Hapus obrolan"}, (d, which) -> {
                        if (which == 0) {
                            obrolanId = r.id;
                            set.setObrolanAktif(r.id);
                            muatAwal();
                        } else {
                            new AlertDialog.Builder(this).setMessage("Hapus obrolan ini selamanya?")
                                    .setPositiveButton("Hapus", (d2, w2) -> {
                                        bg.execute(() -> db.hapusObrolan(r.id));
                                        if (r.id == obrolanId) obrolanBaru();
                                    }).setNegativeButton("Batal", null).show();
                        }
                    }).setNegativeButton("Batal", null).show();
        });
        dlg.show();
    }

    // ------------------------------------------------------------ lampiran
    private void tampilMenuLampir() {
        PopupMenu pm = new PopupMenu(this, tombolLampir);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "Foto dan video");
        m.add(0, 2, 1, "File");
        m.add(0, 3, 2, "Drive");
        InfoModel im = set.modelTerpilih(set.provider());
        if (im.pikir) {
            MenuItem a = m.add(0, 4, 3, "Aktifkan mode berpikir");
            a.setCheckable(true);
            a.setChecked(set.berpikir());
        }
        if (im.web) {
            MenuItem b = m.add(0, 5, 4, "Pencarian web");
            b.setCheckable(true);
            b.setChecked(set.cariWeb());
        }
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: pilihBerkas(K_FOTO); return true;
                case 2: pilihBerkas(K_FILE); return true;
                case 3: pilihBerkas(K_DRIVE); return true;
                case 4:
                    set.setBerpikir(!set.berpikir());
                    Toast.makeText(this, set.berpikir() ? "Mode berpikir aktif" : "Mode berpikir nonaktif", Toast.LENGTH_SHORT).show();
                    return true;
                case 5:
                    set.setCariWeb(!set.cariWeb());
                    Toast.makeText(this, set.cariWeb() ? "Pencarian web aktif" : "Pencarian web nonaktif", Toast.LENGTH_SHORT).show();
                    return true;
                default: return false;
            }
        });
        pm.show();
    }

    private void pilihBerkas(int kode) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (kode == K_FOTO) {
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        } else {
            i.putExtra("android.content.extra.SHOW_ADVANCED", true);
            i.putExtra("android.provider.extra.SHOW_ADVANCED", true);
            i.putExtra(Intent.EXTRA_LOCAL_ONLY, false);
            if (kode == K_DRIVE) {
                boolean ada = false;
                try { ada = getPackageManager().resolveContentProvider("com.google.android.apps.docs.storage", 0) != null; } catch (Throwable ignored) { }
                Toast.makeText(this, ada ? "Pilih Drive dari menu di sisi pemilih berkas" : "Aplikasi Google Drive belum terpasang. Pasang dulu agar muncul di pemilih berkas.", Toast.LENGTH_LONG).show();
            }
        }
        try {
            startActivityForResult(i, kode);
        } catch (Throwable t) {
            pengumuman("Tidak bisa membuka pemilih berkas.");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req != K_FOTO && req != K_FILE && req != K_DRIVE) return;
        if (data.getClipData() != null) {
            ClipData cd = data.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) tambahLampiran(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            tambahLampiran(data.getData());
        }
    }

    private void tambahLampiran(Uri uri) {
        if (uri == null) return;
        if (lampiranTertunda.size() >= MAKS_LAMPIRAN) { pengumuman("Maksimal " + MAKS_LAMPIRAN + " lampiran."); return; }
        final Lampiran l = new Lampiran();
        l.sumber = uri;
        l.nama = "Memuat nama berkas…";
        lampiranTertunda.add(l);
        buatBaris(l);
        final Context app = getApplicationContext();
        bgLampiran.execute(() -> PengelolaLampiran.siapkan(app, l, set, () -> runOnUiThread(() -> perbaruiBaris(l))));
    }

    private void buatBaris(final Lampiran l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(4), dp(4), dp(4));
        row.setBackgroundColor(0xFFF4F4F4);
        LinearLayout kiri = new LinearLayout(this);
        kiri.setOrientation(LinearLayout.VERTICAL);
        l.namaView = new TextView(this);
        l.namaView.setTextColor(0xFF111111);
        l.namaView.setTextSize(15);
        l.bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        l.bar.setMax(100);
        l.bar.setIndeterminate(false);
        l.statusView = new TextView(this);
        l.statusView.setTextSize(13);
        l.statusView.setTextColor(0xFF444444);
        l.statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        kiri.addView(l.namaView);
        kiri.addView(l.bar);
        kiri.addView(l.statusView);
        row.addView(kiri, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button hapus = Sisi.tombol(this, "Hapus");
        hapus.setOnClickListener(v -> {
            l.batal = true;
            lampiranTertunda.remove(l);
            panelLampiran.removeView(l.baris);
            if (l.berkas != null) l.berkas.delete();
            if (lampiranTertunda.isEmpty()) skrolLampiran.setVisibility(View.GONE);
            tampilStatus("Lampiran dihapus.");
        });
        row.addView(hapus);
        l.baris = row;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        panelLampiran.addView(row, lp);
        skrolLampiran.setVisibility(View.VISIBLE);
        perbaruiBaris(l);
    }

    private static String ukuranTeks(long b) {
        if (b < 0) return "";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }

    private void perbaruiBaris(Lampiran l) {
        if (l.baris == null) return;
        String u = ukuranTeks(l.ukuran);
        l.namaView.setText(l.nama + (u.isEmpty() ? "" : " (" + u + ")"));
        l.bar.setProgress(l.progres);
        String teks;
        int p = (l.progres / 10) * 10;
        switch (l.tahap) {
            case Lampiran.MENYALIN: teks = "Menyalin berkas… " + p + "%"; break;
            case Lampiran.MENUNGGU: teks = "Menunggu…"; break;
            case Lampiran.MENGUNGGAH: teks = "Mengunggah ke Gemini… " + p + "%"; break;
            case Lampiran.SIAP: teks = "Selesai, siap dikirim"; break;
            default: teks = "Gagal: " + l.galat; break;
        }
        if (!teks.equals(l.statusTeksTerakhir)) {
            l.statusTeksTerakhir = teks;
            l.statusView.setText(teks);
        }
        l.baris.setContentDescription(null);
    }
}
