package com.chatai.studio;

import java.util.Locale;
import org.json.JSONObject;

/** Data satu model AI beserta kemampuannya. */
final class InfoModel {
    String id;
    boolean pikir, web, gambar;
    long konteks, keluaran;

    InfoModel(String id) { this.id = id; }

    JSONObject keJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("i", id); o.put("p", pikir); o.put("w", web); o.put("g", gambar);
            o.put("k", konteks); o.put("o", keluaran);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    static InfoModel dariJson(JSONObject o) {
        InfoModel m = new InfoModel(o.optString("i", ""));
        m.pikir = o.optBoolean("p"); m.web = o.optBoolean("w"); m.gambar = o.optBoolean("g");
        m.konteks = o.optLong("k"); m.keluaran = o.optLong("o");
        return m;
    }

    private static boolean ada(String x, String... kata) {
        for (String k : kata) if (x.contains(k)) return true;
        return false;
    }

    /** Tebakan kemampuan dari nama model (dipakai bila API tidak memberi infonya). */
    static InfoModel tebak(String prov, String id) {
        InfoModel m = new InfoModel(id);
        String x = id.toLowerCase(Locale.ROOT);
        switch (prov) {
            case Penyedia.GEMINI: {
                boolean gemma = x.contains("gemma");
                m.pikir = x.contains("2.5") || x.contains("gemini-3") || x.contains("thinking");
                m.web = !gemma && (x.startsWith("gemini-2") || x.startsWith("gemini-3")
                        || x.startsWith("gemini-flash") || x.startsWith("gemini-pro"));
                m.gambar = x.startsWith("gemini") || x.contains("gemma-3");
                m.konteks = 1048576L;
                m.keluaran = (x.contains("2.5") || x.contains("gemini-3")) ? 65536L : 8192L;
                break;
            }
            case Penyedia.OPENROUTER:
                m.pikir = ada(x, "r1", "o1", "o3", "o4", "thinking", "reasoning", "gpt-oss", "qwen3",
                        "gemini-2.5", "gemini-3", "claude-3.7", "claude-sonnet-4", "claude-opus-4", "grok-3-mini", "grok-4", "gpt-5");
                m.web = true;
                m.gambar = ada(x, "gpt-4o", "gpt-4.1", "gpt-5", "claude", "gemini", "llama-4", "pixtral", "vision", "-vl");
                break;
            case Penyedia.GROQ:
                m.pikir = ada(x, "gpt-oss", "qwen3", "deepseek-r1", "qwq");
                m.web = ada(x, "compound", "gpt-oss");
                m.gambar = ada(x, "llama-4", "vision", "scout", "maverick");
                break;
            default:
                m.pikir = x.contains("magistral");
                m.web = false;
                m.gambar = ada(x, "pixtral", "medium", "small", "large");
                break;
        }
        return m;
    }
}
