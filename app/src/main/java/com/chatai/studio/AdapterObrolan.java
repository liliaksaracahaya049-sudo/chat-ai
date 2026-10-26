package com.chatai.studio;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

/** Adapter daftar obrolan. Tiap pesan panjang dipecah jadi banyak item kecil. */
final class AdapterObrolan extends RecyclerView.Adapter<AdapterObrolan.VH> {
    interface Aksi {
        void salin(long id);
        void bagikan(long id);
        void pilihTeks(long id);
        void muatLama();
    }

    static final class VH extends RecyclerView.ViewHolder {
        TextView label, isi;
        Button b1, b2, b3;
        VH(View v) { super(v); }
    }

    private final Context ctx;
    private final List<ItemObrolan> data;
    private final Aksi aksi;

    AdapterObrolan(Context ctx, List<ItemObrolan> data, Aksi aksi) {
        this.ctx = ctx;
        this.data = data;
        this.aksi = aksi;
    }

    private int dp(int v) { return Sisi.dp(ctx, v); }

    private void latar(View v, int warna) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(warna);
        g.setCornerRadius(dp(14));
        v.setBackground(g);
    }

    private TextView teksView(int sp, int warna) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTextColor(warna);
        return t;
    }

    @Override public int getItemViewType(int p) { return data.get(p).jenis; }
    @Override public int getItemCount() { return data.size(); }

    @Override public VH onCreateViewHolder(ViewGroup parent, int jenis) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(10), dp(4), dp(10), dp(4));
        box.setLayoutParams(lp);
        final VH vh = new VH(box);
        switch (jenis) {
            case ItemObrolan.PENGGUNA:
            case ItemObrolan.AI:
            case ItemObrolan.PIKIR: {
                int warna = jenis == ItemObrolan.PENGGUNA ? 0xFFDCEBFF : (jenis == ItemObrolan.AI ? 0xFFF1F1F1 : 0xFFFFF4D6);
                latar(box, warna);
                box.setPadding(dp(12), dp(8), dp(12), dp(8));
                vh.label = teksView(13, 0xFF333333);
                vh.label.setTypeface(null, Typeface.BOLD);
                ViewCompat.setAccessibilityHeading(vh.label, true);
                box.addView(vh.label);
                vh.isi = teksView(16, 0xFF111111);
                box.addView(vh.isi);
                if (jenis == ItemObrolan.PIKIR) {
                    box.setOnClickListener(v -> {
                        int p = vh.getBindingAdapterPosition();
                        if (p == RecyclerView.NO_POSITION) return;
                        data.get(p).terbuka = !data.get(p).terbuka;
                        notifyItemChanged(p);
                    });
                }
                break;
            }
            case ItemObrolan.AKSI: {
                box.setOrientation(LinearLayout.HORIZONTAL);
                vh.b1 = Sisi.tombol(ctx, "Salin");
                vh.b2 = Sisi.tombol(ctx, "Bagikan");
                vh.b3 = Sisi.tombol(ctx, "Pilih teks");
                vh.b1.setContentDescription("Salin jawaban");
                vh.b2.setContentDescription("Bagikan jawaban");
                vh.b3.setContentDescription("Pilih teks jawaban");
                for (Button b : new Button[]{vh.b1, vh.b2, vh.b3}) {
                    b.setTextSize(13);
                    box.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                }
                vh.b1.setOnClickListener(v -> { long id = idAksi(vh); if (id > 0) aksi.salin(id); });
                vh.b2.setOnClickListener(v -> { long id = idAksi(vh); if (id > 0) aksi.bagikan(id); });
                vh.b3.setOnClickListener(v -> { long id = idAksi(vh); if (id > 0) aksi.pilihTeks(id); });
                break;
            }
            case ItemObrolan.MUAT: {
                vh.b1 = Sisi.tombol(ctx, "Muat pesan lebih lama");
                vh.b1.setOnClickListener(v -> aksi.muatLama());
                box.addView(vh.b1);
                break;
            }
            default: {
                latar(box, jenis == ItemObrolan.GALAT ? 0xFFFFE3E3 : 0xFFEFEFEF);
                box.setPadding(dp(12), dp(8), dp(12), dp(8));
                vh.isi = teksView(14, jenis == ItemObrolan.GALAT ? 0xFF8A0000 : 0xFF333333);
                box.addView(vh.isi);
                break;
            }
        }
        return vh;
    }

    private long idAksi(VH vh) {
        int p = vh.getBindingAdapterPosition();
        return p == RecyclerView.NO_POSITION ? -1 : data.get(p).pesanId;
    }

    @Override public void onBindViewHolder(VH vh, int pos) {
        ItemObrolan it = data.get(pos);
        switch (it.jenis) {
            case ItemObrolan.PENGGUNA:
            case ItemObrolan.AI:
                vh.label.setVisibility(it.pertama ? View.VISIBLE : View.GONE);
                vh.label.setText(it.label);
                if (it.jenis == ItemObrolan.AI) {
                    if (it.teks.isEmpty()) {
                        vh.isi.setText("…");
                        vh.isi.setContentDescription("Menunggu jawaban AI");
                    } else {
                        vh.isi.setContentDescription(null);
                        vh.isi.setText(Markdown.render(it.teks, it.awalKode));
                    }
                } else {
                    vh.isi.setText(it.teks);
                }
                break;
            case ItemObrolan.PIKIR:
                vh.label.setVisibility(View.VISIBLE);
                vh.label.setText(it.terbuka ? "Proses berpikir (ketuk untuk menutup)" : "Proses berpikir (ketuk untuk membuka)");
                if (it.terbuka) {
                    vh.isi.setVisibility(View.VISIBLE);
                    String t = it.teks;
                    vh.isi.setText(t.length() >= 30000 ? t + "\n…(dipotong)" : t);
                } else {
                    vh.isi.setVisibility(View.GONE);
                }
                break;
            case ItemObrolan.AKSI:
            case ItemObrolan.MUAT:
                break;
            default:
                vh.isi.setText(it.teks);
                break;
        }
    }
}
