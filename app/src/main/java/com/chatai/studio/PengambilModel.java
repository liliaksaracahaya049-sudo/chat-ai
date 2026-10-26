package com.chatai.studio;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Mengambil daftar model asli dari API tiap provider (tidak mengarang). */
final class PengambilModel {
    private PengambilModel() { }

    static List<InfoModel> ambil(String prov, String kunci) throws Exception {
        List<InfoModel> hasil;
        switch (prov) {
            case Penyedia.GEMINI: hasil = gemini(kunci); break;
            case Penyedia.OPENROUTER: hasil = openRouter(kunci); break;
            case Penyedia.GROQ: hasil = groq(kunci); break;
            default: hasil = mistral(kunci); break;
        }
        if (hasil.isEmpty()) throw new IOException("Tidak ada model aktif yang bisa dipakai dari " + Penyedia.nama(prov));
        Collections.sort(hasil, (a, b) -> a.id.compareToIgnoreCase(b.id));
        return hasil;
    }

    private static boolean memuat(JSONArray a, String s) {
        for (int i = 0; i < a.length(); i++) if (s.equals(a.optString(i))) return true;
        return false;
    }

    private static boolean ada(String x, String... kata) {
        for (String k : kata) if (x.contains(k)) return true;
        return false;
    }

    private static List<InfoModel> gemini(String kunci) throws Exception {
        if (kunci.isEmpty()) throw new IOException("Isi API key Gemini dulu.");
        List<InfoModel> hasil = new ArrayList<>();
        Set<String> lihat = new HashSet<>();
        String token = null;
        int hal = 0;
        do {
            String url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"
                    + (token != null ? "&pageToken=" + URLEncoder.encode(token, "UTF-8") : "");
            JSONObject j = Jaringan.ambilJson(url, "x-goog-api-key", kunci);
            JSONArray arr = j.optJSONArray("models");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.getJSONObject(i);
                    String nama = m.optString("name", "");
                    String id = nama.startsWith("models/") ? nama.substring(7) : nama;
                    if (id.isEmpty() || !lihat.add(id)) continue;
                    JSONArray metode = m.optJSONArray("supportedGenerationMethods");
                    if (metode == null || !memuat(metode, "generateContent")) continue;
                    String x = id.toLowerCase(Locale.ROOT);
                    if (ada(x, "tts", "embedding", "aqa", "imagen", "veo", "-live", "native-audio", "flash-image",
                            "image-generation", "lyria", "robotics", "computer-use")) continue;
                    InfoModel im = InfoModel.tebak(Penyedia.GEMINI, id);
                    if (m.has("thinking")) im.pikir = m.optBoolean("thinking");
                    long k = m.optLong("inputTokenLimit", 0);
                    long o = m.optLong("outputTokenLimit", 0);
                    if (k > 0) im.konteks = k;
                    if (o > 0) im.keluaran = o;
                    hasil.add(im);
                }
            }
            String nx = Jaringan.s(j, "nextPageToken");
            token = (nx == null || nx.isEmpty()) ? null : nx;
        } while (token != null && ++hal < 20);
        return hasil;
    }

    private static List<InfoModel> openRouter(String kunci) throws Exception {
        JSONObject j = Jaringan.ambilJson("https://openrouter.ai/api/v1/models",
                kunci.isEmpty() ? null : "Authorization", "Bearer " + kunci);
        JSONArray d = j.optJSONArray("data");
        List<InfoModel> hasil = new ArrayList<>();
        Set<String> lihat = new HashSet<>();
        if (d == null) return hasil;
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.getJSONObject(i);
            String id = Jaringan.s(o, "id");
            if (id == null || id.contains("embed") || !lihat.add(id)) continue;
            JSONObject ars = o.optJSONObject("architecture");
            if (ars != null) {
                JSONArray out = ars.optJSONArray("output_modalities");
                if (out != null && !memuat(out, "text")) continue;
            }
            InfoModel m = InfoModel.tebak(Penyedia.OPENROUTER, id);
            m.konteks = o.optLong("context_length", 0);
            JSONArray sp = o.optJSONArray("supported_parameters");
            if (sp != null) m.pikir = memuat(sp, "reasoning") || memuat(sp, "include_reasoning");
            if (ars != null) {
                JSONArray in = ars.optJSONArray("input_modalities");
                if (in != null) m.gambar = memuat(in, "image");
            }
            JSONObject tp = o.optJSONObject("top_provider");
            if (tp != null && !tp.isNull("max_completion_tokens")) m.keluaran = tp.optLong("max_completion_tokens", 0);
            hasil.add(m);
        }
        return hasil;
    }

    private static List<InfoModel> groq(String kunci) throws Exception {
        if (kunci.isEmpty()) throw new IOException("Isi API key Groq dulu.");
        JSONObject j = Jaringan.ambilJson("https://api.groq.com/openai/v1/models", "Authorization", "Bearer " + kunci);
        JSONArray d = j.optJSONArray("data");
        List<InfoModel> hasil = new ArrayList<>();
        Set<String> lihat = new HashSet<>();
        if (d == null) return hasil;
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.getJSONObject(i);
            String id = Jaringan.s(o, "id");
            if (id == null || !lihat.add(id) || !o.optBoolean("active", true)) continue;
            String x = id.toLowerCase(Locale.ROOT);
            if (ada(x, "whisper", "tts", "guard", "orpheus", "embed")) continue;
            InfoModel m = InfoModel.tebak(Penyedia.GROQ, id);
            m.konteks = o.optLong("context_window", 0);
            m.keluaran = 0;
            hasil.add(m);
        }
        return hasil;
    }

    private static List<InfoModel> mistral(String kunci) throws Exception {
        if (kunci.isEmpty()) throw new IOException("Isi API key Mistral dulu.");
        JSONObject j = Jaringan.ambilJson("https://api.mistral.ai/v1/models", "Authorization", "Bearer " + kunci);
        JSONArray d = j.optJSONArray("data");
        List<InfoModel> hasil = new ArrayList<>();
        Set<String> lihat = new HashSet<>();
        if (d == null) return hasil;
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.getJSONObject(i);
            String id = Jaringan.s(o, "id");
            if (id == null || !lihat.add(id)) continue;
            String x = id.toLowerCase(Locale.ROOT);
            JSONObject cap = o.optJSONObject("capabilities");
            if (cap != null) {
                if (!cap.optBoolean("completion_chat", false)) continue;
            } else if (ada(x, "embed", "moderation", "ocr")) continue;
            if (!o.isNull("deprecation") && o.has("deprecation")) continue;
            InfoModel m = InfoModel.tebak(Penyedia.MISTRAL, id);
            m.konteks = o.optLong("max_context_length", 0);
            if (cap != null && cap.has("vision")) m.gambar = cap.optBoolean("vision");
            hasil.add(m);
        }
        return hasil;
    }
}
