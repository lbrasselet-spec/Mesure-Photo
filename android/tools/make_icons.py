"""Génère les icônes de l'appli (Pillow) : couche avant de l'icône adaptative + icône classique.

Dessin : viseur blanc (4 coins) et cote jaune (double flèche avec traits d'extrémité),
identique au logo de l'écran d'accueil (index.html).
Usage : python3 tools/make_icons.py res
"""
import os
import sys
from PIL import Image, ImageDraw

YELLOW, WHITE, BG = (255, 212, 0, 255), (255, 255, 255, 255), (21, 32, 43, 255)
SS = 4  # suréchantillonnage pour l'anticrénelage


def draw_symbol(d, cx, cy, u):
    """Logo en unités du SVG d'accueil (centre 32,32), u = pixels par unité."""
    P = lambda x, y: (cx + (x - 32) * u, cy + (y - 32) * u)
    w = 3 * u
    def line(a, b, col):
        d.line([P(*a), P(*b)], fill=col, width=int(round(w)))
        for q in (a, b):  # bouts arrondis
            x, y = P(*q); r = w / 2
            d.ellipse([x - r, y - r, x + r, y + r], fill=col)
    for a, b in [((15, 22), (15, 16)), ((15, 16), (21, 16)), ((43, 16), (49, 16)), ((49, 16), (49, 22)),
                    ((49, 42), (49, 48)), ((49, 48), (43, 48)), ((21, 48), (15, 48)), ((15, 48), (15, 42))]:
        line(a, b, WHITE)
    for a, b in [((21, 25), (21, 39)), ((43, 25), (43, 39)), ((26, 32), (38, 32))]:
        line(a, b, YELLOW)
    d.polygon([P(21.5, 32), P(28, 27.5), P(28, 36.5)], fill=YELLOW)
    d.polygon([P(42.5, 32), P(36, 27.5), P(36, 36.5)], fill=YELLOW)


def foreground(px):
    """Couche avant 108 dp (px = taille en pixels), symbole contenu dans la zone sûre."""
    big = Image.new('RGBA', (px * SS, px * SS), (0, 0, 0, 0))
    u = px * SS / 108 * 50 / 36          # 36 unités de logo -> 50 dp
    draw_symbol(ImageDraw.Draw(big), px * SS / 2, px * SS / 2, u)
    return big.resize((px, px), Image.LANCZOS)


def legacy(px):
    """Icône classique 48 dp : carré arrondi foncé + symbole."""
    big = Image.new('RGBA', (px * SS, px * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    s = px * SS
    d.rounded_rectangle([s * 0.04, s * 0.04, s * 0.96, s * 0.96], radius=s * 0.22, fill=BG)
    draw_symbol(d, s / 2, s / 2, s / 64 * 1.05)
    return big.resize((px, px), Image.LANCZOS)


def main(res):
    for name, k in [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]:
        d = os.path.join(res, 'mipmap-' + name)
        os.makedirs(d, exist_ok=True)
        foreground(int(108 * k)).save(os.path.join(d, 'ic_launcher_fg.png'))
        legacy(int(48 * k)).save(os.path.join(d, 'ic_launcher.png'))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'res')
