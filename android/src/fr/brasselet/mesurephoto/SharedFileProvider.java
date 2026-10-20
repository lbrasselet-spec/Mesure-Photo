package fr.brasselet.mesurephoto;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Donne accès, ponctuellement et fichier par fichier, au dossier cache/shared de l'appli :
 *  - à l'appareil photo, qui y écrit la photo prise ;
 *  - à l'appli choisie pour l'envoi (mail, WhatsApp…), qui y lit la photo annotée.
 * Non exporté : aucune autre appli n'y accède sans autorisation explicite (Intent).
 */
public class SharedFileProvider extends ContentProvider {

    static final String AUTHORITY = "fr.brasselet.mesurephoto.files";

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "shared");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static Uri uriFor(String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 1) throw new FileNotFoundException("chemin invalide");
        String name = seg.get(0);
        if (name.isEmpty() || name.startsWith(".") || name.contains("/")) throw new FileNotFoundException("nom invalide");
        return new File(dir(getContext()), name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.parseMode(mode));
    }

    /** Nom et taille du fichier (affichés par les applis de mail). */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        ArrayList<String> cols = new ArrayList<>();
        ArrayList<Object> vals = new ArrayList<>();
        for (String col : projection) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                cols.add(col);
                vals.add(f.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                cols.add(col);
                vals.add(f.length());
            }
        }
        MatrixCursor c = new MatrixCursor(cols.toArray(new String[0]), 1);
        c.addRow(vals.toArray());
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n == null) return null;
        n = n.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("lecture seule");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("lecture seule");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}
