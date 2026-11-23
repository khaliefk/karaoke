package com.khalief.karaoke.tv;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
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
    /** Folder perangkat (MediaStore) pilihan user, disimpan persisten.
        Jalan fallback kalau TV tidak punya pemilih folder sistem
        (DocumentsUI): user memilih folder dari daftar hasil scan
        MediaStore, file dibaca lewat content:// URI, bukan path
        mentah (scoped storage memblokir File mentah di /sdcard). */
    private static final String KEY_MEDIA_FOLDER = "media_folder";

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
    /** Lagu dijeda (pause) tapi belum di-stop: dipakai tombol
        play/pause di Remote HP. MediaPlayer tetap pegang file,
        start() melanjutkan dari posisi yang sama. */
    private boolean paused = false;
    private int lastVideoW = 0;
    private int lastVideoH = 0;
    private volatile boolean destroyed = false;

    private SurfaceHolder videoHolder = null;
    private VideoListener videoListener = null;

    /** Izin folder pilihan hilang (user mencabut dari Pengaturan):
        daftar jatuh balik ke folder bawaan + contoh. */
    private boolean folderLost = false;

    /** Izin media dicabut (atau folder media tidak kebaca lagi):
        daftar jatuh balik ke folder bawaan + contoh. */
    private boolean mediaFolderLost = false;

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

    /* ---------- Folder perangkat (MediaStore, fallback SAF) ---------- */

    public synchronized String getMediaFolder() {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_MEDIA_FOLDER, null);
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void setMediaFolder(String path) {
        try {
            SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (path == null) ed.remove(KEY_MEDIA_FOLDER);
            else ed.putString(KEY_MEDIA_FOLDER, path);
            ed.apply();
        } catch (Exception ignored) {}
        mediaFolderLost = false;
        invalidateScan();
    }

    public synchronized void clearMediaFolder() {
        setMediaFolder(null);
    }

    /** Izin baca media yang dibutuhkan buat scan MediaStore.
        API 33+: READ_MEDIA_AUDIO + READ_MEDIA_VIDEO; di bawah itu
        READ_EXTERNAL_STORAGE. */
    public static String[] neededMediaPerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{"android.permission.READ_MEDIA_AUDIO",
                    "android.permission.READ_MEDIA_VIDEO"};
        }
        return new String[]{"android.permission.READ_EXTERNAL_STORAGE"};
    }

    /** "granted" kalau semua izin media ada, "partial" kalau cuma
        sebagian (mis. audio saja), "denied" kalau belum ada. */
    public synchronized String mediaPermState() {
        String[] perms = neededMediaPerms();
        int granted = 0;
        for (String p : perms) {
            try {
                if (ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) granted++;
            } catch (Exception ignored) {}
        }
        if (granted >= perms.length) return "granted";
        if (granted > 0) return "partial";
        return "denied";
    }

    public synchronized boolean hasMediaPermission() {
        return "granted".equals(mediaPermState()) || "partial".equals(mediaPermState());
    }

    /** Daftar folder di perangkat yang berisi file media, dikelompokkan
        dari MediaStore (audio + video). Dipakai modal buat memilih
        folder tanpa pemilih folder sistem. JSON: {"ok":true,
        "folders":[{"path":...,"name":...,"count":n,"audio":n,
        "video":n}]}. Kalau izin media belum ada:
        {"ok":false,"reason":"no_perm"}. */
    public String scanDeviceFoldersJson() {
        if (!hasMediaPermission()) return "{\"ok\":false,\"reason\":\"no_perm\"}";
        java.util.LinkedHashMap<String, int[]> byFolder =
                new java.util.LinkedHashMap<String, int[]>();
        ContentResolver cr = ctx.getContentResolver();
        Uri[] uris = new Uri[]{MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI};
        String[] proj = new String[]{MediaStore.MediaColumns.DATA};
        for (int t = 0; t < uris.length; t++) {
            Cursor c = null;
            try {
                c = cr.query(uris[t], proj, null, null, null);
                if (c == null) continue;
                int dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                while (c.moveToNext()) {
                    String data = dataCol >= 0 ? c.getString(dataCol) : null;
                    if (data == null || data.length() == 0) continue;
                    int slash = data.lastIndexOf('/');
                    if (slash <= 0) continue;
                    String folder = data.substring(0, slash);
                    int[] n = byFolder.get(folder);
                    if (n == null) {
                        n = new int[]{0, 0, 0};
                        byFolder.put(folder, n);
                    }
                    n[0]++;
                    if (t == 0) n[1]++; else n[2]++;
                }
            } catch (SecurityException se) {
                Log.w(TAG, "scan folder perangkat ditolak: " + se);
                return "{\"ok\":false,\"reason\":\"no_perm\"}";
            } catch (Exception e) {
                Log.w(TAG, "scan folder perangkat gagal: " + e);
            } finally {
                if (c != null) c.close();
            }
        }
        // Folder paling banyak file dulu, lalu nama A-Z. Maks 50.
        List<String> paths = new ArrayList<String>(byFolder.keySet());
        final java.util.Map<String, int[]> counts = byFolder;
        Collections.sort(paths, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int ca = counts.get(a)[0];
                int cb = counts.get(b)[0];
                if (ca != cb) return cb - ca;
                return a.compareToIgnoreCase(b);
            }
        });
        try {
            JSONArray arr = new JSONArray();
            int shown = 0;
            for (String p : paths) {
                if (shown >= 50) break;
                int[] n = byFolder.get(p);
                JSONObject o = new JSONObject();
                o.put("path", p);
                o.put("name", p.substring(p.lastIndexOf('/') + 1));
                o.put("count", n[0]);
                o.put("audio", n[1]);
                o.put("video", n[2]);
                arr.put(o);
                shown++;
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("folders", arr);
            return out.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"reason\":\"error\"}";
        }
    }

    /** Scan file media di bawah satu folder perangkat (MediaStore),
        dipakai buat folder pilihan user. File dikenali dari kolom
        DATA (path), tapi diputar lewat content:// URI supaya lolos
        scoped storage. */
    private void scanMediaStoreFolder(String folderPath, List<Item> out,
                                      Set<String> fileNames, ScanResult r) {
        if (folderPath == null || folderPath.length() == 0) return;
        String prefix = folderPath.endsWith("/") ? folderPath : folderPath + "/";
        ContentResolver cr = ctx.getContentResolver();
        Uri[] uris = new Uri[]{MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI};
        String[] proj = new String[]{MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA};
        for (int t = 0; t < uris.length; t++) {
            Cursor c = null;
            try {
                c = cr.query(uris[t], proj, null, null, null);
                if (c == null) continue;
                int idCol = c.getColumnIndex(MediaStore.MediaColumns._ID);
                int nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                int dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA);
                while (c.moveToNext()) {
                    if (r.count >= SCAN_MAX_FILES) break;
                    String data = dataCol >= 0 ? c.getString(dataCol) : null;
                    String name = nameCol >= 0 ? c.getString(nameCol) : null;
                    if (data == null || name == null) continue;
                    if (!data.startsWith(prefix)) continue;
                    if (!isMediaName(name.toLowerCase())) continue;
                    if (fileNames.contains(name.toLowerCase())) continue;
                    long id = idCol >= 0 ? c.getLong(idCol) : -1;
                    if (id < 0) continue;
                    Uri contentUri = ContentUris.withAppendedId(uris[t], id);
                    Item it = new Item();
                    it.id = "ms:" + contentUri.toString();
                    it.title = titleOf(name);
                    it.source = "media";
                    it.isVideo = isVideoName(name.toLowerCase());
                    out.add(it);
                    fileNames.add(name.toLowerCase());
                    r.count++;
                }
            } catch (SecurityException se) {
                // Izin media dicabut: buang folder media, jatuh balik.
                Log.w(TAG, "izin media hilang: " + se);
                clearMediaFolder();
                r.mediaFolder = "";
                r.mediaLost = true;
                mediaFolderLost = true;
                return;
            } catch (Exception e) {
                Log.w(TAG, "scan MediaStore gagal: " + e);
            } finally {
                if (c != null) c.close();
            }
        }
    }

    /** Folder unduhan (VideoDownloader): file MP4 hasil unduh dari
        HP. Dibaca sebagai sumber lokal "download", muncul di daftar
        File Lokal dan di tab Download Remote. */
    public File downloadDir() {
        return VideoDownloader.downloadDir(ctx);
    }

    /** Hapus satu file unduhan dari TV. id bentuk "download:<nama>".
        Nama dibatasi ke satu file datar (tanpa garis miring) supaya
        tidak bisa keluar dari folder unduhan. */
    public synchronized boolean deleteDownload(String id) {
        if (id == null || !id.startsWith("download:")) return false;
        String name = id.substring("download:".length());
        if (name.length() == 0 || name.contains("/") || name.contains("\\")
                || name.contains("..")) return false;
        File f = new File(downloadDir(), name);
        boolean ok = f.isFile() && f.delete();
        if (ok) invalidateScan();
        return ok;
    }

    public synchronized void invalidateScan() {
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
        String source; // asset | file | folder | download | media
        boolean isVideo;
        long size = -1; // byte, -1 kalau tidak diketahui (asset/SAF)
    }

    private static class ScanResult {
        List<Item> items = new ArrayList<>();
        int count = 0;          // jumlah file media (tanpa contoh)
        String folderName = ""; // nama folder pilihan, kosong kalau tidak ada
        boolean hasFolder = false;
        boolean folderLost = false;
        String mediaFolder = ""; // nama folder perangkat pilihan
        boolean mediaLost = false;
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
                    it.size = f.length();
                    files.add(it);
                    fileNames.add(name.toLowerCase());
                    r.count++;
                }
            }
        }

        // Folder unduhan (MP4 hasil unduh dari HP): level atas saja,
        // sumber "download" supaya tab Download bisa menyaringnya.
        // File yang sama namanya dengan folder music tidak didobel.
        File dlDir = downloadDir();
        if (dlDir != null && dlDir.isDirectory()) {
            File[] list = dlDir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (!f.isFile()) continue;
                    String name = f.getName();
                    if (name.startsWith(".")) continue; // file .part
                    String lower = name.toLowerCase();
                    if (!isMediaName(lower)) continue;
                    if (fileNames.contains(lower)) continue;
                    Item it = new Item();
                    it.id = "download:" + name;
                    it.title = titleOf(name);
                    it.source = "download";
                    it.isVideo = isVideoName(lower);
                    it.size = f.length();
                    files.add(it);
                    fileNames.add(lower);
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

        // Folder perangkat pilihan user (MediaStore), fallback kalau
        // TV tidak punya pemilih folder sistem. Di-scan terakhir
        // supaya folder SAF (kalau ada) tetap menang.
        String mediaPath = getMediaFolder();
        if (mediaPath != null && mediaPath.length() > 0) {
            String name = mediaPath.substring(mediaPath.lastIndexOf('/') + 1);
            r.mediaFolder = name;
            scanMediaStoreFolder(mediaPath, files, fileNames, r);
        }

        // Judul terurut A-Z, contoh tetap di atas.
        Collections.sort(files, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        r.items.addAll(files);
        r.folderLost = folderLost;
        r.mediaLost = mediaFolderLost;
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

    /** Daftar lagu/video lokal: contoh + folder music bawaan +
        folder unduhan + folder pilihan user (kalau ada). JSON array
        [{id, title, source, isVideo, size}]. size = byte (-1 kalau
        tidak diketahui, mis. asset/SAF). */
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
                o.put("size", it.size);
                arr.put(o);
            }
        } catch (Exception e) {
            Log.w(TAG, "listJson gagal: " + e);
        }
        return arr.toString();
    }

    /** Info folder buat modal: {"hasFolder":bool,"folder":"nama",
        "count":n,"folderLost":bool,"mediaFolder":"nama",
        "mediaLost":bool}. */
    public String folderInfoJson() {
        ScanResult r = scanAll();
        try {
            JSONObject o = new JSONObject();
            o.put("hasFolder", r.hasFolder);
            o.put("folder", r.folderName);
            o.put("count", r.count);
            o.put("folderLost", r.folderLost);
            o.put("mediaFolder", r.mediaFolder);
            o.put("mediaLost", r.mediaLost);
            return o.toString();
        } catch (Exception e) {
            return "{\"hasFolder\":false,\"folder\":\"\",\"count\":0,\"folderLost\":false,\"mediaFolder\":\"\",\"mediaLost\":false}";
        }
    }

    /** Nama folder pilihan (buat broadcast state ke HP): folder SAF
        dulu, kalau tidak ada folder perangkat, kalau tidak ada
        kosong (folder bawaan). */
    public String folderName() {
        ScanResult r = scanAll();
        if (r.folderName != null && r.folderName.length() > 0) return r.folderName;
        return r.mediaFolder;
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
            } else if (id != null && id.startsWith("download:")) {
                String name = id.substring(9);
                // cuma boleh file yang ada di folder download (tanpa ../)
                if (name.contains("/") || name.contains("\\") || name.contains("..")) return false;
                File f = new File(downloadDir(), name);
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
            } else if (id != null && id.startsWith("ms:")) {
                // File MediaStore (folder perangkat): diputar lewat
                // content:// URI, bukan path mentah.
                Uri u = Uri.parse(id.substring(3));
                p.setDataSource(ctx, u);
                Item it = lookupItem(id);
                if (it != null) {
                    title = it.title;
                    video = it.isVideo;
                } else {
                    String raw = Uri.decode(u.getLastPathSegment());
                    title = titleOf(raw == null ? "" : raw);
                    video = false;
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
            paused = false;
            if (video && videoListener != null) {
                // Beri tahu listener TANPA syarat surface sudah valid.
                // Layer video mulai GONE, jadi surface memang belum
                // ada saat play() jalan; justru kabar inilah yang
                // membuat layer muncul (showVideoLayer) -> surface
                // dibuat -> onSurfaceReady -> attachDisplay -> gambar
                // muncul. Versi lama mensyaratkan surface valid dulu
                // di sini: tidak ada yang pernah memunculkan layer,
                // jadi audio jalan tapi gambar tidak pernah muncul
                // (unduhan dikira "hanya suara", padahal file-nya
                // video+audio). onSurfaceReady tetap yang memasang
                // display begitu surface benar-benar ada.
                videoListener.onVideoStarted(lastVideoW, lastVideoH);
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

    /** Jeda lagu (posisi dipertahankan). Dipakai tombol pause Remote. */
    public synchronized boolean pause() {
        try {
            if (mp != null && mp.isPlaying()) {
                mp.pause();
                paused = true;
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Lanjutkan lagu yang dijeda. */
    public synchronized boolean resume() {
        try {
            if (mp != null && !mp.isPlaying()) {
                mp.start();
                paused = false;
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Loncat ke posisi (milidetik). Jalan juga saat dijeda. */
    public synchronized boolean seekTo(int msec) {
        try {
            if (mp != null) {
                mp.seekTo(Math.max(0, msec));
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public synchronized int getPosition() {
        try {
            return mp != null ? Math.max(0, mp.getCurrentPosition()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public synchronized int getDuration() {
        try {
            return mp != null ? Math.max(0, mp.getDuration()) : 0;
        } catch (Exception e) {
            return 0;
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
            o.put("paused", paused && mp != null);
            o.put("position", getPosition() / 1000);
            o.put("duration", getDuration() / 1000);
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
        paused = false;
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
