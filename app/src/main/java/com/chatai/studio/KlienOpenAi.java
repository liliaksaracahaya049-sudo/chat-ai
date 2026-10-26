package com.chatai.studio;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Klien streaming untuk API gaya OpenAI: Open Router, Groq, dan Mistral. */
final class KlienOpenAi {
    private KlienOpenAi() { }

    static MesinObrolan.Hasil kirim(MesinObrolan.Pekerjaan job, String prov, String kunci, InfoModel m,
                                    List<MesinObrolan.Giliran> riwayat, boolean pikir, boolean web) throws Exception {
        MesinObrolan.Hasil h = sekali(job, prov, kunci, m, riwayat, pikir, web, true);
        if (!h.ok && h.kode == 400 && !job.batal.get() && job.panjangTeks() == 0 && !MesinObrolan.galatKunci(h.galat)) {
            job.status = "Fitur tambahan ditolak model, mencoba ulang tanpa fitur tambahan…";
            h = sekali(job, prov, kunci, m, riwayat, pikir, web, false);
        }
        return h;
    }

    private static JSONObject pesan(MesinObrolan.Giliran g) throws Exception {
        JSONObject o = new JSONObject();
        if ("assistant".equals(g.peran)) {
            o.put("role", "assistant");
            o.put("content", g.teks);
            return o;
        }
        o.put("role", "user");
        JSONArray parts = new JSONArray();
        for (Lampiran l : g.lampiran) {
            String mime = l.mime == null ? "" : l.mime;
            if (l.teksInline != null) {
                parts.put(new JSONObject().put("type", "text").put("text", "[Isi berkas " + l.nama + "]\n" + l.teksInline));
            } else if (l.dataUrl != null && mime.startsWith("image/")) {
                parts.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", l.dataUrl)));
            } else if (l.dataUrl != null) {
                parts.put(new JSONObject().put("type", "file").put("file",
                        new JSONObject().put("filename", l.nama).put("file_data", l.dataUrl)));
            }
        }
        if (parts.length() == 0) {
            o.put("content", g.teks);
        } else {
            parts.put(new JSONObject().put("type", "text").put("text", g.teks.isEmpty() ? " " : g.teks));
            o.put("content", parts);
        }
        return o;
    }

    private static void terimaIsi(MesinObrolan.Pekerjaan job, Object isi, boolean tampilPikir) throws Exception {
        if (isi instanceof String) {
            String s = (String) isi;
            if (!s.isEmpty()) job.tambahTeks(s);
        } else if (isi instanceof JSONArray) {
            JSONArray a = (JSONArray) isi;
            for (int i = 0; i < a.length(); i++) {
                JSONObject el = a.optJSONObject(i);
                if (el == null) continue;
                String tipe = Jaringan.s(el, "type");
                if ("text".equals(tipe)) {
                    String s = Jaringan.s(el, "text");
                    if (s != null && !s.isEmpty()) job.tambahTeks(s);
                } else if ("thinking".equals(tipe) && tampilPikir) {
                    Object th = el.opt("thinking");
                    if (th instanceof String) job.tambahPikir((String) th);
                    else if (th instanceof JSONArray) {
                        JSONArray ta = (JSONArray) th;
                        for (int k = 0; k < ta.length(); k++) {
                            JSONObject t = ta.optJSONObject(k);
                            String s = t == null ? null : Jaringan.s(t, "text");
                            if (s != null) job.tambahPikir(s);
                        }
                    }
                }
            }
        }
    }

    private static MesinObrolan.Hasil sekali(MesinObrolan.Pekerjaan job, String prov, String kunci, InfoModel m,
                                             List<MesinObrolan.Giliran> riwayat, boolean pikir, boolean web, boolean opsi) throws Exception {
        final boolean tampilPikir = pikir && m.pikir;
        JSONObject body = new JSONObject();
        body.put("model", m.id);
        body.put("stream", true);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", MesinObrolan.SISTEM));
        for (MesinObrolan.Giliran g : riwayat) msgs.put(pesan(g));
        body.put("messages", msgs);
        String x = m.id.toLowerCase(Locale.ROOT);
        if (opsi) {
            if (Penyedia.OPENROUTER.equals(prov)) {
                if (tampilPikir) body.put("reasoning", new JSONObject().put("effort", "medium"));
                if (web) body.put("plugins", new JSONArray().put(new JSONObject().put("id", "web")));
            } else if (Penyedia.GROQ.equals(prov)) {
                if (m.pikir) {
                    if (x.contains("gpt-oss")) {
                        if (pikir) body.put("reasoning_effort", "medium");
                        else { body.put("reasoning_effort", "low"); body.put("include_reasoning", false); }
                    } else if (x.contains("qwen3")) {
                        if (pikir) { body.put("reasoning_effort", "default"); body.put("reasoning_format", "parsed"); }
                        else body.put("reasoning_effort", "none");
                    } else if (x.contains("deepseek-r1")) {
                        body.put("reasoning_format", pikir ? "parsed" : "hidden");
                    }
                }
                if (web && x.contains("gpt-oss")) {
                    body.put("tools", new JSONArray().put(new JSONObject().put("type", "browser_search")));
                }
            }
        }

        HttpURLConnection c = Jaringan.buka(Penyedia.urlObrolan(prov), "POST", 20 * 60 * 1000);
        job.koneksi = c;
        try {
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "text/event-stream");
            c.setRequestProperty("Authorization", "Bearer " + kunci);
            if (Penyedia.OPENROUTER.equals(prov)) c.setRequestProperty("X-Title", "Chat AI");
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
                    String d;
                    if (line.startsWith("data:")) d = line.substring(5).trim();
                    else if (line.startsWith("{")) d = line.trim();
                    else continue;
                    if (d.isEmpty()) continue;
                    if (d.equals("[DONE]")) break;
                    JSONObject j = new JSONObject(d);
                    if (j.has("error") && !j.isNull("error")) {
                        Object e = j.get("error");
                        String msg = e instanceof JSONObject ? ((JSONObject) e).optString("message", e.toString()) : String.valueOf(e);
                        return MesinObrolan.Hasil.gagal(0, Penyedia.nama(prov) + ": " + msg);
                    }
                    JSONArray ch = j.optJSONArray("choices");
                    if (ch == null || ch.length() == 0) continue;
                    JSONObject c0 = ch.getJSONObject(0);
                    JSONObject delta = c0.optJSONObject("delta");
                    if (delta != null) {
                        terimaIsi(job, delta.opt("content"), tampilPikir);
                        if (tampilPikir) {
                            String rs = Jaringan.s(delta, "reasoning");
                            if (rs == null || rs.isEmpty()) rs = Jaringan.s(delta, "reasoning_content");
                            if (rs != null && !rs.isEmpty()) job.tambahPikir(rs);
                        }
                        JSONArray an = delta.optJSONArray("annotations");
                        if (an != null) {
                            for (int i = 0; i < an.length(); i++) {
                                JSONObject a = an.optJSONObject(i);
                                JSONObject uc = a == null ? null : a.optJSONObject("url_citation");
                                if (uc != null) job.tambahSumber(Jaringan.s(uc, "title"), Jaringan.s(uc, "url"));
                            }
                        }
                    }
                    String fr = Jaringan.s(c0, "finish_reason");
                    if (fr != null && !fr.isEmpty()) akhir = fr;
                }
            }
            if ("length".equals(akhir) && !job.batal.get()) return MesinObrolan.Hasil.terpotongSaja();
            if ("error".equals(akhir) && job.panjangTeks() == 0) return MesinObrolan.Hasil.gagal(0, Penyedia.nama(prov) + " melaporkan galat saat menjawab");
            if ("content_filter".equals(akhir) && job.panjangTeks() == 0) return MesinObrolan.Hasil.gagal(0, "Respons diblokir filter konten");
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
