package com.chatai.studio;

import android.net.Uri;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;

/** Satu berkas lampiran beserta status unggahnya. */
final class Lampiran {
    static final int MENYALIN = 0, MENUNGGU = 1, MENGUNGGAH = 2, SIAP = 3, GAGAL = 4;

    final long idUnik = System.nanoTime();
    Uri sumber;
    volatile String nama = "berkas";
    volatile String mime = "application/octet-stream";
    volatile long ukuran = -1;
    volatile File berkas;
    volatile int tahap = MENYALIN;
    volatile int progres;
    volatile String galat;
    volatile String geminiUri;
    volatile long waktuUnggah;
    volatile boolean batal;
    String teksInline;
    String dataUrl;

    // tampilan
    View baris;
    ProgressBar bar;
    TextView namaView, statusView;
    String statusTeksTerakhir = "";
}
