package com.chatai.studio;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Klien streaming untuk Gemini (streamGenerateContent). */
final class KlienGemini {
    private KlienGemini() { }

    static MesinObrolan.Hasil kirim(MesinObrolan.Pekerjaan job, String kunci, InfoModel m,
                                    List<MesinObrolan.Giliran> riwayat, boolean pikir, boolean web) throws Exception {
        MesinObrolan.Hasil h = sekali(job, kunci, m, riwayat, pikir, web, true);
        if (!h.ok && h.kode == 400 && !job.batal.get() && job.panjangTeks() == 0 && !MesinObrolan.galatKunci(h.galat)) {
            job.status = "Fitur tambahan ditolak model, mencoba ulang tanpa fitur tambahan…";
            h = sekali(job, kunci, m, riwayat, pikir, web, false);
        }
        return h;
    }

    private static MesinObrolan.Hasil sekali(MesinObrolan.Pekerjaan job, String kunci, InfoModel m,
                                             List<MesinObrolan.Giliran> riwayat, boolean pikir, boolean web, boolean opsi) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        for (MesinObrolan.Giliran g : riwayat) {
            JSONObject c = new JSONObject();
            c.put("role", "assistant".equals(g.peran) ? "model" : "user");
            JSONArray parts = new JSONArray();
            for (Lampiran l : g.lampiran) {
                if (l.teksInline != null) {
                    parts.put(new JSONObject().put("text", "[Isi berkas " + l.nama + "]\n" + l.teksInline));
                } else if (l.geminiUri != null) {
                    parts.put(new JSONObject().put("fileData", new JSONObject().put("mimeType", l.mime).put("fileUri", l.geminiUri)));
                }
            }
            parts.put(new JSONObject().put("text", g.teks.isEmpty() ? " " : g.teks));
            c.put("parts", parts);
            contents.put(c);
        }
        body.put("contents", contents);
        body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", MesinObrolan.SISTEM))));
        JSONObject gc = new JSONObject();
        gc.put("maxOutputTokens", m.keluaran > 0 ? m.keluaran : 8192);
        if (opsi && m.pikir) {
            if (pikir) gc.put("thinkingConfig", new JSONObject().put("includeThoughts", true));
            else if (m.id.contains("2.5-flash")) gc.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
        }
        body.put("generationConfig", gc);
        if (opsi && web) body.put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + m.id + ":streamGenerateContent?alt=sse";
        HttpURLConnection c = Jaringan.buka(url, "POST", 20 * 60 * 1000);
        job.koneksi = c;
        try {
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "text/event-stream");
            c.setRequestProperty("x-goog-api-key", kunci);
            c.setDoOutput(true);
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
            int kode = c.getResponseCode();
            if (kode >= 400) return MesinObrolan.Hasil.gagal(kode, Jaringan.ringkasGalat(kode, Jaringan.bacaGalat(c)));

            String akhir = null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8), 65536)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (job.batal.get()) break;
                    if (!line.startsWith("data:")) continue;
                    String d = line.substring(5).trim();
                    if (d.isEmpty()) continue;
                    JSONObject j = new JSONObject(d);
                    if (j.has("error") && !j.isNull("error")) {
                        JSONObject e = j.optJSONObject("error");
                        int ek = e != null ? e.optInt("code", 0) : 0;
                        return MesinObrolan.Hasil.gagal(ek, "Gemini: " + (e != null ? e.optString("message", d) : d));
                    }
                    JSONObject pf = j.optJSONObject("promptFeedback");
                    if (pf != null && Jaringan.s(pf, "blockReason") != null) {
                        return MesinObrolan.Hasil.gagal(0, "Permintaan diblokir Gemini: " + pf.optString("blockReason"));
                    }
                    JSONArray cands = j.optJSONArray("candidates");
                    if (cands == null || cands.length() == 0) continue;
                    JSONObject cand = cands.getJSONObject(0);
                    JSONObject content = cand.optJSONObject("content");
                    if (content != null) {
                        JSONArray parts = content.optJSONArray("parts");
                        if (parts != null) {
                            for (int i = 0; i < parts.length(); i++) {
                                JSONObject p = parts.getJSONObject(i);
                                String t = Jaringan.s(p, "text");
                                if (t == null || t.isEmpty()) continue;
                                if (p.optBoolean("thought", false)) { if (pikir) job.tambahPikir(t); }
                                else job.tambahTeks(t);
                            }
                        }
                    }
                    JSONObject gm = cand.optJSONObject("groundingMetadata");
                    if (gm != null) {
                        JSONArray gcs = gm.optJSONArray("groundingChunks");
                        if (gcs != null) {
                            for (int i = 0; i < gcs.length(); i++) {
                                JSONObject w = gcs.getJSONObject(i).optJSONObject("web");
                                if (w != null) job.tambahSumber(Jaringan.s(w, "title"), Jaringan.s(w, "uri"));
                            }
                        }
                    }
                    String fr = Jaringan.s(cand, "finishReason");
                    if (fr != null && !fr.isEmpty()) akhir = fr;
                }
            }
            if ("MAX_TOKENS".equals(akhir) && !job.batal.get()) return MesinObrolan.Hasil.terpotongSaja();
            if (akhir != null && !"STOP".equals(akhir) && !"MAX_TOKENS".equals(akhir) && job.panjangTeks() == 0) {
                return MesinObrolan.Hasil.gagal(0, "Gemini menghentikan respons: " + akhir);
            }
            if (akhir != null && !"STOP".equals(akhir) && !"MAX_TOKENS".equals(akhir)) {
                job.tambahTeks("\n\n[Respons dihentikan Gemini: " + akhir + "]");
            }
            return MesinObrolan.Hasil.sukses();
        } catch (java.io.IOException e) {
            if (job.batal.get()) return MesinObrolan.Hasil.sukses();
            return MesinObrolan.Hasil.gagal(0, "Koneksi terputus: " + MesinObrolan.pesan(e));
        } finally {
            job.koneksi = null;
            try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }
}
