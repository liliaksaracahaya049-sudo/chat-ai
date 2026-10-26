package com.chatai.studio;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/** Penyimpanan pengaturan aplikasi. */
final class Pengaturan {
    private final SharedPreferences sp;

    Pengaturan(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("chatai", Context.MODE_PRIVATE);
    }

    String provider() {
        String p = sp.getString("provider", Penyedia.GEMINI);
        for (String s : Penyedia.SEMUA) if (s.equals(p)) return p;
        return Penyedia.GEMINI;
    }
    void setProvider(String p) { sp.edit().putString("provider", p).apply(); }

    String kunci(String prov) { return Brankas.buka(sp.getString("kunci_" + prov, "")).trim(); }
    void setKunci(String prov, String k) {
        sp.edit().putString("kunci_" + prov, Brankas.kunci(k == null ? "" : k.trim())).apply();
    }

    String idModel(String prov) { return sp.getString("model_" + prov, ""); }
    void setModel(String prov, String id) { sp.edit().putString("model_" + prov, id).apply(); }

    List<InfoModel> daftarModel(String prov) {
        try {
            String s = sp.getString("daftar_" + prov, "");
            if (!s.isEmpty()) {
                JSONArray a = new JSONArray(s);
                List<InfoModel> l = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    InfoModel m = InfoModel.dariJson(a.getJSONObject(i));
                    if (!m.id.isEmpty()) l.add(m);
                }
                if (!l.isEmpty()) return l;
            }
        } catch (Exception ignored) { }
        return Penyedia.bawaan(prov);
    }

    void simpanDaftar(String prov, List<InfoModel> l) {
        JSONArray a = new JSONArray();
        for (InfoModel m : l) a.put(m.keJson());
        sp.edit().putString("daftar_" + prov, a.toString()).apply();
    }

    InfoModel modelTerpilih(String prov) {
        List<InfoModel> l = daftarModel(prov);
        String id = idModel(prov);
        for (InfoModel m : l) if (m.id.equals(id)) return m;
        return l.get(0);
    }

    boolean otomatisAlih() { return sp.getBoolean("alih", false); }
    void setOtomatisAlih(boolean b) { sp.edit().putBoolean("alih", b).apply(); }
    boolean berpikir() { return sp.getBoolean("pikir", false); }
    void setBerpikir(boolean b) { sp.edit().putBoolean("pikir", b).apply(); }
    boolean cariWeb() { return sp.getBoolean("web", false); }
    void setCariWeb(boolean b) { sp.edit().putBoolean("web", b).apply(); }
    long obrolanAktif() { return sp.getLong("obrolan", 0); }
    void setObrolanAktif(long id) { sp.edit().putLong("obrolan", id).apply(); }
    boolean notifDiminta() { return sp.getBoolean("notif", false); }
    void setNotifDiminta() { sp.edit().putBoolean("notif", true).apply(); }
}
