package com.chatai.studio;

import java.util.ArrayList;
import java.util.List;

/** Daftar provider AI yang didukung. */
final class Penyedia {
    static final String GEMINI = "gemini";
    static final String OPENROUTER = "openrouter";
    static final String GROQ = "groq";
    static final String MISTRAL = "mistral";
    static final String[] SEMUA = {GEMINI, OPENROUTER, GROQ, MISTRAL};

    private Penyedia() { }

    static String nama(String id) {
        switch (id) {
            case GEMINI: return "Gemini";
            case OPENROUTER: return "Open Router";
            case GROQ: return "Groq";
            default: return "Mistral";
        }
    }

    static String petunjukKunci(String id) {
        switch (id) {
            case GEMINI: return "Masukan api key gemini di sini";
            case OPENROUTER: return "Masukan api key open router di sini";
            case GROQ: return "Masukan api key groq di sini";
            default: return "Masukan api key mistral di sini";
        }
    }

    static String urlObrolan(String id) {
        switch (id) {
            case OPENROUTER: return "https://openrouter.ai/api/v1/chat/completions";
            case GROQ: return "https://api.groq.com/openai/v1/chat/completions";
            default: return "https://api.mistral.ai/v1/chat/completions";
        }
    }

    /** Model bawaan (4 per provider) sebelum daftar disegarkan. */
    static List<InfoModel> bawaan(String id) {
        String[] ids;
        switch (id) {
            case GEMINI: ids = new String[]{"gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite", "gemini-2.0-flash"}; break;
            case OPENROUTER: ids = new String[]{"openrouter/auto", "google/gemini-2.5-flash", "openai/gpt-4o-mini", "meta-llama/llama-3.3-70b-instruct"}; break;
            case GROQ: ids = new String[]{"llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-20b", "openai/gpt-oss-120b"}; break;
            default: ids = new String[]{"mistral-large-latest", "mistral-medium-latest", "mistral-small-latest", "codestral-latest"}; break;
        }
        List<InfoModel> l = new ArrayList<>();
        for (String s : ids) l.add(InfoModel.tebak(id, s));
        return l;
    }
}
