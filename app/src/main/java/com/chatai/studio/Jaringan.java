package com.chatai.studio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Pembantu HTTP sederhana. */
final class Jaringan {
    private Jaringan() { }

    static HttpURLConnection buka(String url, String metode, int bacaMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(metode);
        c.setConnectTimeout(30000);
        c.setReadTimeout(bacaMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "ChatAI-Android/1.0");
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    static String bacaSemua(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
                if (sb.length() > 30_000_000) break;
            }
        }
        return sb.toString();
    }

    static String bacaGalat(HttpURLConnection c) {
        try { return bacaSemua(c.getErrorStream()); } catch (Throwable t) { return ""; }
    }

    static JSONObject ambilJson(String url, String headerNama, String headerNilai) throws Exception {
        HttpURLConnection c = buka(url, "GET", 60000);
        c.setRequestProperty("Accept", "application/json");
        if (headerNama != null) c.setRequestProperty(headerNama, headerNilai);
        try {
            int k = c.getResponseCode();
            if (k >= 400) throw new IOException(ringkasGalat(k, bacaGalat(c)));
            return new JSONObject(bacaSemua(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    /** String dari JSON, null bila tidak ada atau bernilai null. */
    static String s(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        return o.optString(k, null);
    }

    static String ringkasGalat(int kode, String badan) {
        String pesan = null;
        try {
            String b = badan == null ? "" : badan.trim();
            if (b.startsWith("{")) {
                JSONObject j = new JSONObject(b);
                Object e = j.opt("error");
                if (e instanceof JSONObject) pesan = s((JSONObject) e, "message");
                else if (e instanceof String) pesan = (String) e;
                if (pesan == null) pesan = s(j, "message");
                if (pesan == null) {
                    Object d = j.opt("detail");
                    if (d != null && d != JSONObject.NULL) pesan = d.toString();
                }
            }
        } catch (Throwable ignored) { }
        if (pesan == null || pesan.isEmpty()) pesan = badan == null ? "" : badan.trim();
        if (pesan.length() > 400) pesan = pesan.substring(0, 400) + "…";
        return "HTTP " + kode + (pesan.isEmpty() ? "" : ": " + pesan);
    }
}
