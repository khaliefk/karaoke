package com.khalief.karaoke.tv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.SurfaceHolder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pemutar file media lokal dengan TRANSPOSE.
 *
 * Prinsip: android.media.MediaPlayer + PlaybackParams.setPitch().
 * Pitch = pengali frekuensi nada; speed dipertahankan 1.0 jadi tempo
 * TETAP, cuma nadanya naik/turun. Semitone n -> pitch = 2^(n/12).
 * Pitch berlaku buat trek audio, termasuk audio di dalam file video;
 * video tetap sinkron karena speed tidak berubah.
 *
 * Format yang didukung (yang bisa dimainkan MediaPlayer perangkat):
 * audio MP3, M4A, OGG, WAV, FLAC, AAC; video MP4, MKV, WEBM, 3GP.
 * Video tampil fullscreen di TV lewat SurfaceView; ketuk video untuk
 * menyembunyikan layarnya (audio tetap jalan), tombol "Tampilkan
 * video" di modal File Lokal memunculkannya lagi.
 *
 * Folder lagu: user bisa menunjuk folder sendiri lewat pemilih folder
 * sistem (Storage Access Framework, ACTION_OPEN_DOCUMENT_TREE). URI
 * folder disimpan persisten supaya tidak pilih ulang tiap buka
 * aplikasi. Folder dipilih itu di-scan (termasuk subfolder, maks
 * kedalaman 3, maks 300 file) dan semua file media yang didukung
 * muncul sebagai daftar judul. Kalau belum ada folder yang dipilih,
 * yang dipakai folder music bawaan aplikasi
 * (/sdcard/Android/data/<package>/files/music/) + lagu contoh.
 * SAF tidak butuh izin MANAGE_EXTERNAL_STORAGE; kalau pemilih folder
 * tidak ada di TV tertentu, aplikasi tetap jalan dengan folder bawaan.
 *
 * Kenapa ini cuma bisa untuk file lokal, bukan lagu YouTube:
 * musik YouTube diputar lewat YT.Player di dalam WebView (iframe
 * cross-origin) - API-nya tidak punya kontrol pitch sama sekali, dan
 * audio di dalam iframe tidak bisa di-route ke pemroses apa pun.
 *
 * Mic HP TIDAK kena efek: mic diputar lewat MicPlayer (AudioTrack
 * terpisah di KaraokeService), bukan lewat MediaPlayer ini.
 */
public class LocalPlayer {

    private static final String TAG = "LocalPlayer";

    /** Batas transpose: -6 sampai +6 semitone. */
    public static final int MIN_SEMITONES = -6;
    public static final int MAX_SEMITONES = 6;

    /** Nama file contoh yang dibundel di assets APK TV. */
    public static final String SAMPLE_ASSET = "sample.mp3";
    public static final String SAMPLE_ID = "sample";
    public static final String SAMPLE_TITLE = "Contoh - akor C-Am-F-G (32 dtk, loop)";

    /** Format audio + video yang dicoba dimainkan. MediaPlayer tiap
        perangkat bisa beda dukungan codec-nya; file yang tidak bisa
        dibuka dilaporkan gagal putar, bukan crash. */
    private static final String[] AUDIO_EXT = {".mp3", ".m4a", ".ogg", ".wav", ".flac", ".aac"};
    private static final String[] VIDEO_EXT = {".mp4", ".mkv", ".webm", ".3gp"};

    /** Folder pilihan user (SAF), disimpan persisten. */
    private static final String PREFS = "karaoke_local_prefs";
    private static final String KEY_FOLDER_URI = "folder_uri";

    /** Batas scan folder pilihan: maks file + kedalaman subfolder. */
    private static final int SCAN_MAX_FILES = 300;
    private static final int SCAN_MAX_DEPTH = 3;
    /** Hasil scan di-cache sebentar supaya modal tidak scan ulang
        tiap dibuka; folder baru tetap ke-scan karena cache dibuang
        saat folder diganti. */
    private static final long SCAN_CACHE_MS = 10000;

    /** Callback video buat MainActivity (tampilkan/sembunyikan layar). */
    public interface VideoListener {
        void onVideoStarted(int videoW, int videoH);
        void onVideoStopped();
        void onVideoSizeChanged(int videoW, int videoH);
    }

    private final Context ctx;
    private MediaPlayer mp;
    private int semitones = 0;
    private int volume = 80; // 0-100, ikut volume musik aplikasi
    private String currentId = null;
    private String currentTitle = null;
    private boolean currentIsVideo = false;
    private int lastVideoW = 0;
    private int lastVideoH = 0;
    private volatile boolean destroyed = false;

    private SurfaceHolder videoHolder = null;
    private VideoListener videoListener = null;

    /** Izin folder pilihan hilang (user mencabut dari Pengaturan):
        daftar jatuh balik ke folder bawaan + contoh. */
    private boolean folderLost = false;

    private ScanResult cachedScan = null;
    private long cachedScanAt = 0;

    public LocalPlayer(Context context) {
        this.ctx = context.getApplicationContext();
    }

    /** Faktor pitch untuk n semitone. +12 = 2.0 (satu oktaf naik). */
    public static float pitchFor(int semitones) {
        return (float) Math.pow(2.0, semitones / 12.0);
    }

    public static int clampSemitones(int n) {
        if (n < MIN_SEMITONES) return MIN_SEMITONES;
        if (n > MAX_SEMITONES) return MAX_SEMITONES;
        return n;
    }

    public static boolean isVideoName(String lower) {
        if (lower == null) return false;
        for (String e : VIDEO_EXT) if (lower.endsWith(e)) return true;
        return false;
    }

    public static boolean isAudioName(String lower) {
        if (lower == null) return false;
        for (String e : AUDIO_EXT) if (lower.endsWith(e)) return true;
        return false;
    }

    public static boolean isMediaName(String lower) {
        return isVideoName(lower) || isAudioName(lower);
    }

    /** Judul tampil = nama file tanpa ekstensi. */
    public static String titleOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) return fileName.substring(0, dot);
        return fileName;
    }

    public void setVideoListener(VideoListener l) {
        videoListener = l;
    }

    public void setSurfaceHolder(SurfaceHolder holder) {
        videoHolder = holder;
    }

    /** Surface video sudah siap: kalau lagi memutar video, pasang
        display-nya supaya gambar muncul. Dipanggil MainActivity dari
        SurfaceHolder.Callback. */
    public synchronized void onSurfaceReady() {
        if (mp != null && currentIsVideo) {
            attachDisplay();
            if (videoListener != null && isPlaying()) {
                videoListener.onVideoStarted(lastVideoW, lastVideoH);
            }
        }
    }

    /** Tampilkan lagi layar video yang sempat disembunyikan (ketuk).
        Audio video tetap jalan selama itu. */
    public synchronized void showVideo() {
        if (mp == null || !currentIsVideo) return;
        attachDisplay();
        if (videoListener != null && isPlaying()) {
            videoListener.onVideoStarted(lastVideoW, lastVideoH);
        }
    }

    private void attachDisplay() {
        if (mp == null || videoHolder == null) return;
        try {
            if (videoHolder.getSurface() != null && videoHolder.getSurface().isValid()) {
                mp.setDisplay(videoHolder);
            }
        } catch (Exception e) {
            Log.w(TAG, "setDisplay gagal: " + e);
        }
    }

    /** Terapkan pitch + speed 1.0 ke MediaPlayer yang sedang jalan. */
    private void applyParams() {
        if (mp == null) return;
        try {
            PlaybackParams pp = mp.getPlaybackParams();
            pp.setSpeed(1.0f);
            pp.setPitch(pitchFor(semitones));
            mp.setPlaybackParams(pp);
            Log.i(TAG, "pitch diterapkan: " + semitones + " semitone -> x"
                    + pitchFor(semitones) + " (speed 1.0)");
        } catch (Exception e) {
            Log.w(TAG, "setPlaybackParams gagal: " + e);
        }
    }

    private void applyVolume() {
        if (mp == null) return;
        try {
            float v = Math.max(0, Math.min(100, volume)) / 100f;
            mp.setVolume(v, v);
        } catch (Exception ignored) {}
    }

    /* ---------- Folder pilihan (SAF) ---------- */

    public synchronized Uri getFolderUri() {
        try {
            String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_FOLDER_URI, null);
            return s == null ? null : Uri.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void setFolderUri(Uri uri) {
        try {
            SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (uri == null) ed.remove(KEY_FOLDER_URI);
            else ed.putString(KEY_FOLDER_URI, uri.toString());
            ed.apply();
        } catch (Exception ignored) {}
        folderLost = false;
        invalidateScan();
    }

    public synchronized void clearFolderUri() {
        setFolderUri(null);
    }

    private synchronized void invalidateScan() {
        cachedScan = null;
        cachedScanAt = 0;
    }

    /** Nama folder pilihan user (buat tampil di modal). */
    private String queryFolderName(Uri treeUri) {
        ContentResolver cr = ctx.getContentResolver();
        Cursor c = null;
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            c = cr.query(docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && n.length() > 0) return n;
            }
        } catch (Exception e) {
            Log.w(TAG, "nama folder gagal dibaca: " + e);
        } finally {
            if (c != null) c.close();
        }
        try {
            String last = Uri.decode(treeUri.getLastPathSegment());
            if (last != null) {
                int colon = last.lastIndexOf(':');
                if (colon >= 0 && colon + 1 < last.length()) return last.substring(colon + 1);
                return last;
            }
        } catch (Exception ignored) {}
        return "";
    }

    /* ---------- Scan daftar media ---------- */

    private static class Item {
        String id;
        String title;
        String source; // asset | file | folder
        boolean isVideo;
    }

    private static class ScanResult {
        List<Item> items = new ArrayList<>();
        int count = 0;          // jumlah file media (tanpa contoh)
        String folderName = ""; // nama folder pilihan, kosong kalau tidak ada
        boolean hasFolder = false;
        boolean folderLost = false;
    }

    private ScanResult scanAll() {
        long now = SystemClock.elapsedRealtime();
        if (cachedScan != null && now - cachedScanAt < SCAN_CACHE_MS) return cachedScan;
        ScanResult r = new ScanResult();
        List<Item> files = new ArrayList<>();
        Set<String> fileNames = new HashSet<>();

        // Lagu contoh bawaan, selalu pertama.
        Item s = new Item();
        s.id = SAMPLE_ID;
        s.title = SAMPLE_TITLE;
        s.source = "asset";
        s.isVideo = false;
        r.items.add(s);

        // Folder music bawaan aplikasi (level atas saja).
        File dir = musicDir();
        if (dir != null && dir.isDirectory()) {
            File[] list = dir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (!f.isFile()) continue;
                    String name = f.getName();
                    String lower = name.toLowerCase();
                    if (!isMediaName(lower)) continue;
                    Item it = new Item();
                    it.id = "file:" + name;
                    it.title = titleOf(name);
                    it.source = "file";
                    it.isVideo = isVideoName(lower);
                    files.add(it);
                    fileNames.add(name.toLowerCase());
                    r.count++;
                }
            }
        }

        // Folder pilihan user (SAF), scan rekursif.
        Uri tree = getFolderUri();
        if (tree != null) {
            r.hasFolder = true;
            try {
                r.folderName = queryFolderName(tree);
                scanTree(tree, DocumentsContract.getTreeDocumentId(tree), 0, files, fileNames, r);
            } catch (SecurityException se) {
                // Izin folder dicabut user dari Pengaturan: buang
                // simpanan, jatuh balik ke folder bawaan.
                Log.w(TAG, "izin folder hilang, kembali ke folder bawaan: " + se);
                clearFolderUri();
                r.hasFolder = false;
                r.folderName = "";
                r.folderLost = true;
                folderLost = true;
            } catch (Exception e) {
                Log.w(TAG, "scan folder gagal: " + e);
            }
        }

        // Judul terurut A-Z, contoh tetap di atas.
        Collections.sort(files, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        r.items.addAll(files);
        r.folderLost = folderLost;
        cachedScan = r;
        cachedScanAt = now;
        return r;
    }

    /** Scan satu folder SAF + subfoldernya (rekursif, ada batas). */
    private void scanTree(Uri treeUri, String parentDocId, int depth,
                          List<Item> out, Set<String> fileNames, ScanResult r) {
        if (r.count >= SCAN_MAX_FILES) return;
        ContentResolver cr = ctx.getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        Cursor c = null;
        try {
            c = cr.query(childrenUri, new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null);
            if (c == null) return;
            int idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (c.moveToNext()) {
                if (r.count >= SCAN_MAX_FILES) break;
                String docId = c.getString(idCol);
                String name = c.getString(nameCol);
                String mime = c.getString(mimeCol);
                if (docId == null || name == null || mime == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < SCAN_MAX_DEPTH) {
                        scanTree(treeUri, docId, depth + 1, out, fileNames, r);
                    }
                } else if (isMediaName(name.toLowerCase())) {
                    // File yang sama dengan folder music bawaan jangan dobel.
                    if (fileNames.contains(name.toLowerCase())) continue;
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    Item it = new Item();
                    it.id = "saf:" + docUri.toString();
                    it.title = titleOf(name);
                    it.source = "folder";
                    it.isVideo = isVideoName(name.toLowerCase());
                    out.add(it);
                    fileNames.add(name.toLowerCase());
                    r.count++;
                }
            }
        } finally {
            if (c != null) c.close();
        }
    }

    /** Daftar lagu/video lokal: contoh + folder music bawaan + folder
        pilihan user (kalau ada). JSON array [{id, title, source,
        isVideo}]. */
    public String listJson() {
        ScanResult r = scanAll();
        JSONArray arr = new JSONArray();
        try {
            for (Item it : r.items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("title", it.title);
                o.put("source", it.source);
                o.put("isVideo", it.isVideo);
                arr.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "listJson gagal: " + e);
        }
        return arr.toString();
    }

    /** Info folder buat modal: {"hasFolder":bool,"folder":"nama",
        "count":n,"folderLost":bool}. */
    public String folderInfoJson() {
        ScanResult r = scanAll();
        try {
            JSONObject o = new JSONObject();
            o.put("hasFolder", r.hasFolder);
            o.put("folder", r.folderName);
            o.put("count", r.count);
            o.put("folderLost", r.folderLost);
            return o.toString();
        } catch (Exception e) {
            return "{\"hasFolder\":false,\"folder\":\"\",\"count\":0,\"folderLost\":false}";
        }
    }

    /** Nama folder pilihan (buat broadcast state ke HP). */
    public String folderName() {
        return scanAll().folderName;
    }

    private File musicDir() {
        try {
            File d = new File(ctx.getExternalFilesDir(null), "music");
            if (!d.exists()) d.mkdirs();
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    /** Cari judul + jenis video dari id (buat play saf:). */
    private Item lookupItem(String id) {
        for (Item it : scanAll().items) {
            if (it.id.equals(id)) return it;
        }
        return null;
    }

    /** Putar media lokal. id "sample" = contoh bawaan; "file:<nama>" =
        file di folder music bawaan; "saf:<uri>" = file di folder
        pilihan user. Video tampil di SurfaceView TV. Loop terus
        supaya user bisa utak-atik transpose sambil lagu jalan. */
    public synchronized boolean play(String id) {
        if (destroyed) return false;
        stopLocked();
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            String title;
            boolean video;
            if (SAMPLE_ID.equals(id)) {
                AssetFileDescriptor fd = ctx.getAssets().openFd(SAMPLE_ASSET);
                p.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                try { fd.close(); } catch (Exception ignored) {}
                title = SAMPLE_TITLE;
                video = false;
            } else if (id != null && id.startsWith("file:")) {
                String name = id.substring(5);
                // cuma boleh file yang ada di folder music (tanpa ../)
                if (name.contains("/") || name.contains("\\") || name.contains("..")) return false;
                File f = new File(musicDir(), name);
                if (!f.isFile()) return false;
                p.setDataSource(f.getAbsolutePath());
                title = titleOf(name);
                video = isVideoName(name.toLowerCase());
            } else if (id != null && id.startsWith("saf:")) {
                Uri u = Uri.parse(id.substring(4));
                p.setDataSource(ctx, u);
                Item it = lookupItem(id);
                if (it != null) {
                    title = it.title;
                    video = it.isVideo;
                } else {
                    String raw = Uri.decode(u.getLastPathSegment());
                    title = titleOf(raw == null ? "" : raw);
                    video = isVideoName(u.toString().toLowerCase());
                }
            } else {
                return false;
            }
            p.setLooping(true);
            if (video) {
                final LocalPlayer self = this;
                p.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                    @Override public void onVideoSizeChanged(MediaPlayer mp2, int w, int h) {
                        self.lastVideoW = w;
                        self.lastVideoH = h;
                        if (self.videoListener != null) {
                            self.videoListener.onVideoSizeChanged(w, h);
                        }
                    }
                });
                // Pasang surface kalau sudah siap; kalau belum, gambar
                // muncul begitu surface jadi (onSurfaceReady).
                if (videoHolder != null && videoHolder.getSurface() != null
                        && videoHolder.getSurface().isValid()) {
                    p.setDisplay(videoHolder);
                }
            }
            p.prepare();
            mp = p;
            currentId = id;
            currentTitle = title;
            currentIsVideo = video;
            applyVolume();
            applyParams();
            p.start();
            if (video) {
                if (videoHolder != null && videoHolder.getSurface() != null
                        && videoHolder.getSurface().isValid() && videoListener != null) {
                    videoListener.onVideoStarted(lastVideoW, lastVideoH);
                }
            }
            Log.i(TAG, "memutar lokal: " + title + " (pitch " + semitones + ")");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "play gagal (" + id + "): " + e);
            if (mp != null) {
                try { mp.release(); } catch (Exception ignored) {}
                mp = null;
            }
            currentId = null;
            currentTitle = null;
            currentIsVideo = false;
            return false;
        }
    }

    /** Ubah transpose. Berlaku langsung kalau sedang memutar, dan
        dipakai saat lagu berikutnya diputar. Mengembalikan nilai
        yang diterapkan (sudah di-clamp -6..+6). */
    public synchronized int setTranspose(int n) {
        semitones = clampSemitones(n);
        applyParams();
        return semitones;
    }

    public synchronized int getTranspose() {
        return semitones;
    }

    public synchronized void setVolume(int v) {
        volume = Math.max(0, Math.min(100, v));
        applyVolume();
    }

    public synchronized boolean isPlaying() {
        try {
            return mp != null && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized boolean isCurrentVideo() {
        return currentIsVideo;
    }

    /** Status buat UI web + Remote: JSON. */
    public synchronized String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("playing", isPlaying());
            o.put("id", currentId == null ? JSONObject.NULL : currentId);
            o.put("title", currentTitle == null ? "" : currentTitle);
            o.put("isVideo", currentIsVideo);
            o.put("semitones", semitones);
            o.put("pitch", (double) pitchFor(semitones));
            return o.toString();
        } catch (Exception e) {
            return "{\"playing\":false,\"semitones\":" + semitones + "}";
        }
    }

    public synchronized void stop() {
        stopLocked();
    }

    private void stopLocked() {
        boolean wasVideo = currentIsVideo;
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        currentId = null;
        currentTitle = null;
        currentIsVideo = false;
        lastVideoW = 0;
        lastVideoH = 0;
        if (wasVideo && videoListener != null) {
            try { videoListener.onVideoStopped(); } catch (Exception ignored) {}
        }
    }

    public synchronized void destroy() {
        destroyed = true;
        stopLocked();
    }
}
