package com.chatai.studio;

/** Satu baris di daftar obrolan (potongan pesan, tombol aksi, info, dll). */
final class ItemObrolan {
    static final int PENGGUNA = 0, AI = 1, PIKIR = 2, AKSI = 3, INFO = 4, GALAT = 5, MUAT = 6;

    final int jenis;
    long pesanId;
    String teks = "";
    String label = "";
    boolean awalKode;
    boolean pertama;
    boolean terbuka;

    ItemObrolan(int jenis) { this.jenis = jenis; }
}
