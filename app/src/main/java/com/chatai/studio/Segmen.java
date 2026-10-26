package com.chatai.studio;

import java.util.ArrayList;
import java.util.List;

/** Memotong teks sangat panjang menjadi gerbong-gerbong kecil agar ringan ditampilkan. */
final class Segmen {
    static final int UKURAN = 3000;
    static final int BATAS_EKOR = 4500;

    private Segmen() { }

    static int potongSatu(String s, int dari, int maks) {
        int n = s.length();
        if (n - dari <= maks) return n;
        int batas = dari + maks;
        int nl = s.lastIndexOf('\n', batas - 1);
        if (nl >= dari + maks / 2) return nl + 1;
        int sp = s.lastIndexOf(' ', batas - 1);
        if (sp >= dari + maks / 2) return sp + 1;
        int end = batas;
        if (end > dari + 1 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return end;
    }

    static List<String> potongSemua(String s, int maks) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        int i = 0;
        while (i < s.length()) {
            int e = potongSatu(s, i, maks);
            out.add(s.substring(i, e));
            i = e;
        }
        return out;
    }

    static boolean adalahPagar(String baris) {
        int a = 0;
        while (a < baris.length() && (baris.charAt(a) == ' ' || baris.charAt(a) == '\t')) a++;
        return baris.startsWith("```", a);
    }

    /** Apakah setelah segmen ini kita masih berada di dalam blok kode. */
    static boolean akhirKode(String s, boolean awal) {
        boolean k = awal;
        int i = 0, n = s.length();
        while (i < n) {
            int e = s.indexOf('\n', i);
            if (e < 0) e = n;
            if (adalahPagar(s.substring(i, e))) k = !k;
            i = e + 1;
        }
        return k;
    }
}
