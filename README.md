# Mesure Photo

Application Android de photos cotées pour le terrain : prise de vue ou galerie, cotes à double flèche avec
valeur en mm ou cm, notes fléchées, couleurs et tailles réglables, zoom à deux doigts et loupe de précision,
puis envoi par mail, WhatsApp, Drive… ou enregistrement dans la galerie (album « Mesure Photo »).

**Télécharger l'application :** https://github.com/lbrasselet-spec/Mesure-Photo/releases/download/apk/Mesure-Photo.apk
(Android 10 ou plus récent ; aucune autorisation demandée).

- `index.html` : l'application (fonctionne aussi seule, dans un navigateur, pour les essais).
- `android/` : l'enveloppe Android (appareil photo, galerie, partage, enregistrement dans la galerie)
  et son script de compilation `build.sh` (outils Ubuntu, sans Android Studio).
- `.github/workflows/apk.yml` : compilation, publication et essai automatique sur un téléphone virtuel
  Android 15 à chaque modification ; le résultat de l'essai s'affiche sur la page de téléchargement.

La clé de signature n'est pas dans le dépôt : elle est enregistrée dans le secret GitHub `MESURE_KEYSTORE_B64`.
