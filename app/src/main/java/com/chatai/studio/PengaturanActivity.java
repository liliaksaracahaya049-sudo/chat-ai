package com.chatai.studio;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Layar pengaturan: provider, API key, model, segarkan model, peralihan otomatis. */
public class PengaturanActivity extends Activity {
    private Pengaturan set;
    private Spinner spProvider, spModel;
    private EditText etKunci;
    private CheckBox cbAlih;
    private TextView tvStatus;
    private Button bSegarkan;
    private final Map<String, String> drafKunci = new HashMap<>();
    private final Map<String, String> drafModel = new HashMap<>();
    private final Map<String, List<InfoModel>> daftar = new HashMap<>();
    private String provAktif;

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(15);
        v.setTextColor(0xFF333333);
        v.setPadding(0, Sisi.dp(this, 12), 0, Sisi.dp(this, 2));
        return v;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        set = new Pengaturan(this);
        for (String p : Penyedia.SEMUA) {
            drafKunci.put(p, set.kunci(p));
            drafModel.put(p, set.modelTerpilih(p).id);
            daftar.put(p, set.daftarModel(p));
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        ScrollView sv = new ScrollView(this);
        LinearLayout isi = new LinearLayout(this);
        isi.setOrientation(LinearLayout.VERTICAL);
        int pad = Sisi.dp(this, 16);
        isi.setPadding(pad, pad, pad, pad);
        sv.addView(isi);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView judul = new TextView(this);
        judul.setText("Pengaturan");
        judul.setTextSize(24);
        judul.setTypeface(null, Typeface.BOLD);
        judul.setTextColor(0xFF111111);
        ViewCompat.setAccessibilityHeading(judul, true);
        isi.addView(judul);

        isi.addView(label("Provider"));
        spProvider = new Spinner(this, Spinner.MODE_DROPDOWN);
        spProvider.setContentDescription("Pilih provider");
        List<String> namaProv = new ArrayList<>();
        for (String p : Penyedia.SEMUA) namaProv.add(Penyedia.nama(p));
        spProvider.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, namaProv));
        isi.addView(spProvider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Sisi.dp(this, 52)));

        isi.addView(label("API key"));
        etKunci = new EditText(this);
        etKunci.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        etKunci.setSingleLine(true);
        etKunci.setMinHeight(Sisi.dp(this, 48));
        isi.addView(etKunci, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        isi.addView(label("Model"));
        spModel = new Spinner(this, Spinner.MODE_DROPDOWN);
        spModel.setContentDescription("Pilih model");
        isi.addView(spModel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Sisi.dp(this, 52)));

        bSegarkan = Sisi.tombol(this, "Segarkan model");
        isi.addView(bSegarkan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cbAlih = new CheckBox(this);
        cbAlih.setText("Aktifkan peralihan model otomatis saat gagal");
        cbAlih.setTextSize(16);
        cbAlih.setChecked(set.otomatisAlih());
        cbAlih.setMinHeight(Sisi.dp(this, 48));
        isi.addView(cbAlih);

        Button bSimpan = Sisi.tombol(this, "Simpan pengaturan");
        isi.addView(bSimpan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvStatus = new TextView(this);
        tvStatus.setTextSize(15);
        tvStatus.setTextColor(0xFF333333);
        tvStatus.setPadding(0, Sisi.dp(this, 12), 0, 0);
        tvStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        isi.addView(tvStatus);

        setContentView(root);
        Sisi.terapkanInset(root);

        int idx = 0;
        String p0 = set.provider();
        for (int i = 0; i < Penyedia.SEMUA.length; i++) if (Penyedia.SEMUA[i].equals(p0)) idx = i;
        provAktif = Penyedia.SEMUA[idx];
        spProvider.setSelection(idx);
        tampilkanProvider();

        spProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                String baru = Penyedia.SEMUA[pos];
                if (baru.equals(provAktif)) return;
                simpanDraf();
                provAktif = baru;
                tampilkanProvider();
            }
            @Override public void onNothingSelected(AdapterView<?> a) { }
        });
        bSegarkan.setOnClickListener(v -> segarkan());
        bSimpan.setOnClickListener(v -> simpan());
    }

    private void simpanDraf() {
        drafKunci.put(provAktif, etKunci.getText().toString().trim());
        Object s = spModel.getSelectedItem();
        if (s != null) drafModel.put(provAktif, s.toString());
    }

    private void tampilkanProvider() {
        String hint = Penyedia.petunjukKunci(provAktif);
        etKunci.setHint(hint);
        etKunci.setContentDescription(hint);
        etKunci.setText(drafKunci.get(provAktif));
        isiModel(drafModel.get(provAktif));
        tvStatus.setText("");
    }

    private void isiModel(String pilih) {
        List<String> ids = new ArrayList<>();
        int idx = 0;
        List<InfoModel> l = daftar.get(provAktif);
        for (int i = 0; i < l.size(); i++) {
            ids.add(l.get(i).id);
            if (l.get(i).id.equals(pilih)) idx = i;
        }
        spModel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ids));
        spModel.setSelection(idx);
    }

    private void segarkan() {
        final String prov = provAktif;
        final String kunci = etKunci.getText().toString().trim();
        if (kunci.isEmpty() && !Penyedia.OPENROUTER.equals(prov)) {
            Toast.makeText(this, "Isi API key " + Penyedia.nama(prov) + " dulu", Toast.LENGTH_LONG).show();
            return;
        }
        bSegarkan.setEnabled(false);
        tvStatus.setText("Memuat daftar model dari " + Penyedia.nama(prov) + "…");
        new Thread(() -> {
            try {
                final List<InfoModel> l = PengambilModel.ambil(prov, kunci);
                set.simpanDaftar(prov, l);
                runOnUiThread(() -> {
                    daftar.put(prov, l);
                    if (prov.equals(provAktif)) {
                        Object s = spModel.getSelectedItem();
                        isiModel(s != null ? s.toString() : drafModel.get(prov));
                    }
                    bSegarkan.setEnabled(true);
                    tvStatus.setText("Berhasil memuat " + l.size() + " model aktif dari " + Penyedia.nama(prov) + ".");
                });
            } catch (Throwable t) {
                final String pesan = MesinObrolan.pesan(t);
                runOnUiThread(() -> {
                    bSegarkan.setEnabled(true);
                    tvStatus.setText("Gagal memuat model: " + pesan);
                });
            }
        }).start();
    }

    private void simpan() {
        simpanDraf();
        for (String p : Penyedia.SEMUA) {
            set.setKunci(p, drafKunci.get(p));
            String m = drafModel.get(p);
            if (m != null && !m.isEmpty()) set.setModel(p, m);
        }
        set.setProvider(provAktif);
        set.setOtomatisAlih(cbAlih.isChecked());
        Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show();
        finish();
    }
}
