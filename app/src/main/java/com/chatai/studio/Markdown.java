package com.chatai.studio;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/** Penampil markdown ringan (judul, tebal, miring, kode, daftar) tanpa pustaka luar. */
final class Markdown {
    private static final int SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
    private Markdown() { }

    static CharSequence render(String teks, boolean awalKode) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean kode = awalKode;
        int i = 0, n = teks.length();
        while (i < n) {
            int e = teks.indexOf('\n', i);
            boolean adaNl = e >= 0;
            if (!adaNl) e = n;
            String baris = teks.substring(i, e);
            i = e + 1;
            if (Segmen.adalahPagar(baris)) { kode = !kode; continue; }
            if (kode) {
                int a = out.length();
                out.append(baris);
                out.setSpan(new TypefaceSpan("monospace"), a, out.length(), SPAN);
                out.setSpan(new BackgroundColorSpan(0xFFE6E6E6), a, out.length(), SPAN);
            } else {
                olahBaris(out, baris);
            }
            if (adaNl) out.append('\n');
        }
        return out;
    }

    private static void olahBaris(SpannableStringBuilder out, String t) {
        int awal = out.length();
        int tingkat = 0;
        while (tingkat < t.length() && tingkat < 6 && t.charAt(tingkat) == '#') tingkat++;
        if (tingkat > 0 && tingkat < t.length() && t.charAt(tingkat) == ' ') {
            inline(out, t.substring(tingkat + 1).trim());
            out.setSpan(new StyleSpan(Typeface.BOLD), awal, out.length(), SPAN);
            float[] skala = {1f, 1.35f, 1.25f, 1.15f, 1.1f, 1.05f, 1f};
            out.setSpan(new RelativeSizeSpan(skala[tingkat]), awal, out.length(), SPAN);
            return;
        }
        String r = t.trim();
        if (r.equals("---") || r.equals("***") || r.equals("___")) { out.append("────────────"); return; }
        int sp = 0;
        while (sp < t.length() && t.charAt(sp) == ' ') sp++;
        if (t.length() - sp >= 2 && (t.charAt(sp) == '-' || t.charAt(sp) == '*' || t.charAt(sp) == '+') && t.charAt(sp + 1) == ' ') {
            out.append(t.substring(0, sp)).append("• ");
            inline(out, t.substring(sp + 2));
            return;
        }
        inline(out, t);
    }

    private static void inline(SpannableStringBuilder out, String s) {
        int i = 0, n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '`') {
                int e = s.indexOf('`', i + 1);
                if (e > i + 1) {
                    int a = out.length();
                    out.append(s, i + 1, e);
                    out.setSpan(new TypefaceSpan("monospace"), a, out.length(), SPAN);
                    out.setSpan(new BackgroundColorSpan(0xFFE6E6E6), a, out.length(), SPAN);
                    i = e + 1;
                    continue;
                }
            } else if (ch == '*' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("**", i + 2);
                if (e > i + 2) {
                    int a = out.length();
                    out.append(s, i + 2, e);
                    out.setSpan(new StyleSpan(Typeface.BOLD), a, out.length(), SPAN);
                    i = e + 2;
                    continue;
                }
            } else if (ch == '*' && i + 1 < n && s.charAt(i + 1) != ' ') {
                int e = s.indexOf('*', i + 1);
                if (e > i + 1 && s.charAt(e - 1) != ' ') {
                    int a = out.length();
                    out.append(s, i + 1, e);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), a, out.length(), SPAN);
                    i = e + 1;
                    continue;
                }
            }
            out.append(ch);
            i++;
        }
    }
}
