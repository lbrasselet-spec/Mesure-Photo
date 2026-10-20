package android.webkit;

/**
 * Talon de compilation uniquement : la vraie classe est fournie par Android 8 et +.
 * Ce fichier n'est pas inclus dans l'APK (voir build.sh).
 */
public abstract class RenderProcessGoneDetail {
    public abstract boolean didCrash();
    public abstract int rendererPriorityAtExit();
}
