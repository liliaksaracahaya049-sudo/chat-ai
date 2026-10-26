package com.chatai.studio;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

/** Layar untuk memilih (menyorot) sebagian teks jawaban AI; teks dipecah agar tetap ringan. */
public class PilihTeksActivity extends Activity {
    private final List<String> segmen = new ArrayList<>();
    private String penuh = "";
    private TextView info;
    private RecyclerView rv;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Sisi.dp(this, 12), Sisi.dp(this, 4), Sisi.dp(this, 4), 0);
        TextView judul = new TextView(this);
        judul.setText("Pilih teks");
        judul.setTextSize(20);
        judul.setTypeface(null, Typeface.BOLD);
        judul.setTextColor(0xFF111111);
        ViewCompat.setAccessibilityHeading(judul, true);
        bar.addView(judul, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button salin = Sisi.tombol(this, "Salin semua");
        Button tutup = Sisi.tombol(this, "Kembali");
        bar.addView(salin);
        bar.addView(tutup);
        root.addView(bar);
        info = new TextView(this);
        info.setPadding(Sisi.dp(this, 12), 0, Sisi.dp(this, 12), Sisi.dp(this, 4));
        info.setText("Memuat teks…");
        root.addView(info);
        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p, int t) {
                TextView tv = new TextView(PilihTeksActivity.this);
                tv.setTextSize(16);
                tv.setTextColor(0xFF111111);
                tv.setTextIsSelectable(true);
                int pad = Sisi.dp(PilihTeksActivity.this, 12);
                tv.setPadding(pad, pad / 2, pad, pad / 2);
                tv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(tv) { };
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int i) { ((TextView) h.itemView).setText(segmen.get(i)); }
            @Override public int getItemCount() { return segmen.size(); }
        });
        root.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Sisi.terapkanInset(root);
        tutup.setOnClickListener(v -> finish());
        salin.setOnClickListener(v -> {
            try {
                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Jawaban AI", penuh));
                Toast.makeText(this, "Semua teks tersalin", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "Teks terlalu panjang untuk papan klip. Pakai tombol Bagikan di layar utama.", Toast.LENGTH_LONG).show();
            }
        });
        final long id = getIntent().getLongExtra("id", -1);
        new Thread(() -> {
            final String t = Penyimpan.bacaTeks(this, id);
            final List<String> s = Segmen.potongSemua(t, 2000);
            runOnUiThread(() -> {
                penuh = t;
                segmen.addAll(s);
                rv.getAdapter().notifyDataSetChanged();
                info.setText(t.isEmpty() ? "Teks tidak ditemukan." : "Tekan lama pada teks untuk memilih. Panjang teks: " + t.length() + " karakter.");
            });
        }).start();
    }
}
