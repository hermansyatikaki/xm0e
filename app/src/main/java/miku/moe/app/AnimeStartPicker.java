package miku.moe.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Memilih episode PALING AWAL untuk tombol "Mulai Nonton" pada Detail Animeku dan Detail Miku.
 * Sebelumnya tombol memakai episodesList.get(0), padahal isi list berubah mengikuti tombol urutan
 * (Baru/Lama) dan belum lengkap selama episode masih dirender bertahap, sehingga yang dibuka bisa
 * episode terbaru atau episode acak.
 */
final class AnimeStartPicker {
    private static final Pattern[] PATTERNS = new Pattern[] {
            Pattern.compile("(?i)\\b(?:episode|eps|ep)\\s*[.\\-_:]*\\s*0*(\\d+(?:\\.\\d+)?)\\b"),
            Pattern.compile("(?i)(?:^|[\\s\\-_\\[(])e\\s*[.\\-_:]*\\s*0*(\\d+(?:\\.\\d+)?)\\b"),
            Pattern.compile("(?i)(?:^|[\\s\\-_])0*(\\d+(?:\\.\\d+)?)\\s*(?:sub\\s*indo|subtitle\\s*indonesia)?\\s*$")
    };

    private AnimeStartPicker() {}

    static float episodeNumber(String title) {
        if (title == null) return Float.NaN;
        String raw = title.trim().replace('_', ' ').replace(',', '.');
        if (raw.isEmpty()) return Float.NaN;
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(raw);
            String last = null;
            while (matcher.find()) last = matcher.group(1);
            if (last != null) {
                try {
                    return Float.parseFloat(last);
                } catch (NumberFormatException ignored) {
                    // lanjut ke pola berikutnya
                }
            }
        }
        return Float.NaN;
    }

    /**
     * @param loaded          episode yang sudah tampil di daftar
     * @param pending         episode yang belum sempat dirender (urutannya sama dengan loaded)
     * @param newestFirstList true jika daftar sedang diurutkan baru -> lama
     * @return channelId episode awal, atau -1 jika daftar kosong
     */
    static int pickStartChannelId(List<Episode> loaded, List<Episode> pending, boolean newestFirstList) {
        ArrayList<Episode> all = new ArrayList<>();
        if (loaded != null) all.addAll(loaded);
        if (pending != null) all.addAll(pending);
        if (all.isEmpty()) return -1;

        Episode best = null;
        float bestNumber = Float.MAX_VALUE;
        Episode zero = null;
        for (Episode episode : all) {
            float number = episodeNumber(episode.channelName);
            if (Float.isNaN(number)) continue;
            if (number > 0f) {
                if (number < bestNumber) {
                    bestNumber = number;
                    best = episode;
                }
            } else if (zero == null) {
                zero = episode;
            }
        }
        if (best != null) return best.channelId;
        if (zero != null) return zero.channelId;

        // Judul tanpa nomor: data dari server lama -> baru. Jika daftar dibalik (baru -> lama), episode awal ada di ujung.
        Episode fallback = newestFirstList ? all.get(all.size() - 1) : all.get(0);
        return fallback.channelId;
    }
}
