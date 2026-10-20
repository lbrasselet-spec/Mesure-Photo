package fr.brasselet.mesurephoto;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Mesure Photo — partie Android.
 *
 * L'interface complète (photo, cotes, notes, envoi) est la page assets/index.html, affichée dans
 * une WebView. Cette classe ne fait que ce que la page ne peut pas faire seule :
 *  - ouvrir l'appareil photo ou la galerie du téléphone, puis préparer la photo (orientation, taille) ;
 *  - partager la photo annotée (mail, WhatsApp…) ou l'enregistrer dans la galerie ;
 *  - relayer le bouton « retour » d'Android.
 * Aucune permission n'est demandée : l'appareil photo et la galerie passent par les applis du téléphone.
 */
public class MainActivity extends Activity {

    /** Adresse locale réservée à ce type d'usage : elle n'existe pas sur Internet. */
    static final String HOST = "appassets.androidplatform.net";
    static final String APP_URL = "https://" + HOST + "/app/index.html";
    static final int REQ_CAMERA = 1, REQ_PICK = 2;
    static final int MAX_SIDE = 4096;            // côté max de la photo de travail, en pixels
    static final String CAMERA_FILE = "camera.jpg";
    static final int BG = 0xFF0F1317;

    private FrameLayout root;
    private WebView web;
    private boolean pageReady = false;           // la page a signalé qu'elle est prête
    private String pendingJs = null;             // appels mis en attente tant qu'elle ne l'est pas
    private OutputStream exportOut = null;       // fichier en cours de réception depuis la page
    private String exportName = null;

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        // Android 15 et + : l'appli s'affiche sous les barres système ; on réserve leur place et celle du clavier.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                        in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                return in.consumeSystemWindowInsets();
            }
        });
        setContentView(root);
        cleanOldExports();
        createWebView();
    }

    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);            // mémorise réglages et photo en cours
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);                      // mise en page indépendante de la taille de police système
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new AppClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "MPNative");
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        synchronized (this) { pageReady = false; }
        web.loadUrl(APP_URL);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        synchronized (this) { closeExport(); }
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Bouton ou geste « retour » : la page ferme d'abord ses fenêtres, puis revient à l'accueil. */
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.MP && MP.onBack()) ? 1 : 0", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"1".equals(value)) MainActivity.super.onBackPressed();
            }
        });
    }

    // ------------------------------------------------------------------ page et fichiers locaux

    private class AppClient extends WebViewClient {
        /** Sert la page (assets) et la photo de travail ; rien n'est demandé à Internet. */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String scheme = u.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) return null;   // blob:, data: : gérés par la WebView
            String path = u.getPath();
            if (!HOST.equals(u.getHost()) || path == null) return notFound();
            try {
                if (path.startsWith("/app/")) {
                    String name = path.substring(5);
                    if (name.isEmpty() || name.contains("..")) return notFound();
                    String mime = mimeOf(name);
                    return new WebResourceResponse(mime, mime.startsWith("text/") ? "utf-8" : null, getAssets().open(name));
                }
                if (path.startsWith("/work/")) {
                    File f = new File(workDir(), path.substring(6));
                    if (path.substring(6).contains("/") || !f.isFile()) return notFound();
                    return new WebResourceResponse("image/jpeg", null, new FileInputStream(f));
                }
            } catch (IOException e) {
                // fichier introuvable
            }
            return notFound();
        }

        /** Aucune navigation hors de l'appli. */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !HOST.equals(Uri.parse(url).getHost());
        }

        /** Android 8 et + : si l'affichage s'arrête (manque de mémoire), on le relance au lieu de fermer l'appli.
         *  (pas d'@Override : méthode absente de la plateforme de compilation, appelée par Android 8+) */
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view == web) {
                root.removeView(web);
                web.destroy();
                web = null;
                createWebView();      // la page reprend la photo en cours grâce à sa sauvegarde
            }
            return true;
        }
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private File workDir() {
        File d = new File(getFilesDir(), "work");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------------ pont avec la page (window.MPNative)

    private class Bridge {
        @JavascriptInterface
        public void ready() {
            final String js;
            synchronized (MainActivity.this) {
                pageReady = true;
                js = pendingJs;
                pendingJs = null;
            }
            if (js != null) evalJs(js);
        }

        @JavascriptInterface
        public void takePhoto() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { launchCamera(); }
            });
        }

        @JavascriptInterface
        public void pickPhoto() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { launchPicker(); }
            });
        }

        /** Réception de la photo annotée, en plusieurs paquets base64 (début, morceaux, fin). */
        @JavascriptInterface
        public boolean beginExport(String name) {
            synchronized (MainActivity.this) {
                closeExport();
                exportName = safeName(name);
                try {
                    exportOut = new BufferedOutputStream(new FileOutputStream(new File(SharedFileProvider.dir(MainActivity.this), exportName)));
                    return true;
                } catch (IOException e) {
                    exportOut = null;
                    return false;
                }
            }
        }

        @JavascriptInterface
        public boolean appendExport(String base64) {
            synchronized (MainActivity.this) {
                if (exportOut == null) return false;
                try {
                    exportOut.write(Base64.decode(base64, Base64.DEFAULT));
                    return true;
                } catch (Exception e) {
                    closeExport();
                    return false;
                }
            }
        }

        /** action = "share" (envoyer) ou "save" (galerie). Renvoie "ok" ou un message d'erreur. */
        @JavascriptInterface
        public String finishExport(String action, String subject) {
            final File f;
            synchronized (MainActivity.this) {
                if (exportOut == null) return "export interrompu";
                try {
                    exportOut.close();
                } catch (IOException e) {
                    exportOut = null;
                    return "écriture impossible";
                }
                exportOut = null;
                f = new File(SharedFileProvider.dir(MainActivity.this), exportName);
            }
            if ("save".equals(action)) return saveToGallery(f);
            share(f, subject);
            return "ok";
        }
    }

    private void closeExport() {
        if (exportOut != null) {
            try { exportOut.close(); } catch (IOException ignored) { }
            exportOut = null;
        }
    }

    private static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.isEmpty() || n.startsWith(".")) n = "photo" + n;
        if (!n.toLowerCase(Locale.ROOT).endsWith(".jpg")) n += ".jpg";
        return n;
    }

    private void jsCall(String js) {
        synchronized (this) {
            if (!pageReady) {
                pendingJs = pendingJs == null ? js : pendingJs + ";" + js;
                return;
            }
        }
        evalJs(js);
    }

    private void jsError(String message) {
        jsCall("MP.onNativeError(" + JSONObject.quote(message) + ")");
    }

    private void evalJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null && !isDestroyed()) web.evaluateJavascript(js, null);
            }
        });
    }

    // ------------------------------------------------------------------ appareil photo et galerie

    private void launchCamera() {
        File f = new File(SharedFileProvider.dir(this), CAMERA_FILE);
        try {
            if (f.exists()) f.delete();
            f.createNewFile();
        } catch (IOException e) {
            jsError("Stockage du téléphone indisponible.");
            return;
        }
        Uri out = SharedFileProvider.uriFor(CAMERA_FILE);
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        i.putExtra(MediaStore.EXTRA_OUTPUT, out);
        i.setClipData(ClipData.newRawUri("", out));
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            jsError("Aucune application appareil photo n'est disponible.");
        } catch (SecurityException e) {
            jsError("L'appareil photo est bloqué sur ce téléphone.");
        }
    }

    private void launchPicker() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent("android.provider.action.PICK_IMAGES");   // sélecteur de photos d'Android 13+
            i.setType("image/*");
        } else {
            i = galleryIntent();
        }
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            try {
                startActivityForResult(galleryIntent(), REQ_PICK);
            } catch (ActivityNotFoundException e2) {
                jsError("Aucune galerie n'est disponible.");
            }
        }
    }

    private static Intent galleryIntent() {
        Intent g = new Intent(Intent.ACTION_GET_CONTENT);
        g.addCategory(Intent.CATEGORY_OPENABLE);
        g.setType("image/*");
        return g;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_CAMERA) {
            File f = new File(SharedFileProvider.dir(this), CAMERA_FILE);
            if (result == RESULT_OK && f.length() > 0) {
                processPhoto(null, f);
            } else {
                f.delete();
                if (result == RESULT_OK) jsError("La photo n'a pas pu être récupérée.");
                else jsCall("MP.onNativeCancel()");
            }
        } else if (request == REQ_PICK) {
            Uri u = result == RESULT_OK && data != null ? data.getData() : null;
            if (u != null) processPhoto(u, null);
            else jsCall("MP.onNativeCancel()");
        }
    }

    /** Copie la photo, la remet à l'endroit et la ramène à 4096 px max, puis la donne à la page. */
    private void processPhoto(final Uri source, final File sourceFile) {
        jsCall("MP.onNativeBusy(true)");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File tmp = sourceFile;
                try {
                    if (source != null) {
                        tmp = new File(SharedFileProvider.dir(MainActivity.this), "import.tmp");
                        InputStream in = getContentResolver().openInputStream(source);
                        if (in == null) throw new IOException("photo illisible");
                        copy(in, new FileOutputStream(tmp));
                    }
                    String name = "photo_" + System.currentTimeMillis() + ".jpg";
                    prepare(tmp, new File(workDir(), name));
                    clearWork(name);
                    jsCall("MP.onNativePhoto(" + JSONObject.quote("https://" + HOST + "/work/" + name) + ")");
                } catch (Throwable t) {
                    jsError("Impossible de charger la photo (" + t.getClass().getSimpleName() + ").");
                } finally {
                    if (tmp != null) tmp.delete();
                }
            }
        }).start();
    }

    private static void prepare(File in, File out) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(in.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("format d'image non reconnu");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > MAX_SIDE) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(in.getPath(), o);
        if (bmp == null) throw new IOException("décodage impossible");
        Matrix m = orientation(in);
        if (m != null) {
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) {
                bmp.recycle();
                bmp = r;
            }
        }
        FileOutputStream fos = new FileOutputStream(out);
        try {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)) throw new IOException("compression impossible");
        } finally {
            fos.close();
            bmp.recycle();
        }
    }

    /** Rotation ou miroir à appliquer d'après l'EXIF de la photo (null si aucun). */
    private static Matrix orientation(File f) {
        int o;
        try {
            o = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception e) {
            return null;
        }
        Matrix m = new Matrix();
        switch (o) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setRotate(180); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(-90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(-90); break;
            default: return null;
        }
        return m;
    }

    /** Ne garde que la photo de travail courante. */
    private void clearWork(String keep) {
        File[] files = workDir().listFiles();
        if (files == null) return;
        for (File f : files) if (!f.getName().equals(keep)) f.delete();
    }

    /** Supprime les photos envoyées depuis plus de 3 jours (copies temporaires). */
    private void cleanOldExports() {
        File[] files = SharedFileProvider.dir(this).listFiles();
        if (files == null) return;
        long limit = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
        for (File f : files) if (f.lastModified() < limit) f.delete();
    }

    // ------------------------------------------------------------------ partage et galerie

    private void share(final File f, final String subject) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Uri uri = SharedFileProvider.uriFor(f.getName());
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/jpeg");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                if (subject != null && !subject.isEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, subject);
                send.setClipData(ClipData.newRawUri(f.getName(), uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(Intent.createChooser(send, "Envoyer la photo"));
                } catch (ActivityNotFoundException e) {
                    jsError("Aucune application de partage n'est disponible.");
                }
            }
        });
    }

    /** Enregistre dans Images › Mesure Photo (Android 10 et +, sans permission). */
    private String saveToGallery(File f) {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
        v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        v.put("relative_path", "Pictures/Mesure Photo");   // MediaStore.MediaColumns.RELATIVE_PATH
        v.put("is_pending", 1);                              // MediaStore.MediaColumns.IS_PENDING
        Uri item = null;
        try {
            item = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (item == null) return "galerie indisponible";
            InputStream in = new FileInputStream(f);
            OutputStream out = cr.openOutputStream(item);
            if (out == null) {
                in.close();
                throw new IOException("galerie indisponible");
            }
            copy(in, out);
            ContentValues done = new ContentValues();
            done.put("is_pending", 0);
            cr.update(item, done, null, null);
            return "ok";
        } catch (Exception e) {
            if (item != null) {
                try { cr.delete(item, null, null); } catch (Exception ignored) { }
            }
            return "enregistrement impossible";
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            out.close();
        }
    }
}
