"""The Android launcher icon from the PC logo (ios-sweet/assets/logo.svg): its iPhone in front of its
monitor's desktop gradient. Writes the adaptive icon's vector drawables into res/ and an SVG preview."""
import os, sys

RES = sys.argv[1]
PREVIEW = sys.argv[2]

# the iPhone of logo.svg spans x 277..467, y 140..480 (with its side buttons 274..470)
S = 60 / 340                      # 60 of the icon's 108 units tall: inside the 66-unit safe circle
CX, CY = 372, 310
TX, TY = 54 - CX * S, 54 - CY * S


def rr(x, y, w, h, r):
    return (f"M{x + r},{y} H{x + w - r} A{r},{r} 0 0 1 {x + w},{y + r} V{y + h - r} "
            f"A{r},{r} 0 0 1 {x + w - r},{y + h} H{x + r} A{r},{r} 0 0 1 {x},{y + h - r} "
            f"V{y + r} A{r},{r} 0 0 1 {x + r},{y} Z")


def argb(hex6, a=1.0):
    return "#%02X%s" % (round(a * 255), hex6.lstrip("#").upper())


# (path, fill) where fill is a color or ("linear", x1, y1, x2, y2, [(offset, color)])
TILE = [(0, "#ffffff", 0.32), (0.55, "#ffffff", 0.0), (1, "#ffffff", 0.0)]
shapes = []
shapes += [(rr(274, 246, 5, 30, 2), ("#4b505c", 1)), (rr(274, 284, 5, 30, 2), ("#4b505c", 1)),
           (rr(465, 262, 5, 48, 2), ("#4b505c", 1))]
shapes.append((rr(277, 140, 190, 340, 42),
               ("linear", 277, 0, 467, 0, [(0, "#8a909d", 1), (0.12, "#3a3f4b", 1), (0.88, "#2a2e38", 1), (1, "#737986", 1)])))
shapes.append((rr(282, 145, 180, 330, 37), ("#0a0c11", 1)))
shapes.append((rr(291, 155, 158, 312, 27),
               ("linear", 291, 155, 291 + 0.35 * 158, 467, [(0, "#5a9bff", 1), (0.6, "#4a5df0", 1), (1, "#5b36cf", 1)])))
SCREEN_CLIP = rr(291, 155, 158, 312, 27)
inside = [("M339,155 H401 V162 A10,10 0 0 1 391,172 H349 A10,10 0 0 1 339,162 Z", ("#0a0c11", 1))]
rows = [["#34c759", "#f2f2f7", "#ff9f0a"], ["#ff453a", "#64d2ff", "#ffd60a"], ["#bf5af2", "#0a84ff", "#30d158"]]
for j, y in enumerate((192, 242, 292)):
    for i, x in enumerate((307, 353, 399)):
        inside.append((rr(x, y + 1.5, 34, 34, 9.18), ("#000000", 0.18)))
        inside.append((rr(x, y, 34, 34, 9.18), (rows[j][i], 1)))
        inside.append((rr(x, y, 34, 34, 9.18), ("linear", 0, y, 0, y + 34, TILE)))
inside.append((rr(301, 396, 138, 52, 17), ("#ffffff", 0.26)))
for x, c in ((316, "#34c759"), (356, "#f2f2f7"), (396, "#30d158")):
    inside.append((rr(x, 406.5, 32, 32, 8.64), ("#000000", 0.18)))
    inside.append((rr(x, 405, 32, 32, 8.64), (c, 1)))
    inside.append((rr(x, 405, 32, 32, 8.64), ("linear", 0, 405, 0, 437, TILE)))
inside.append((rr(345, 456, 50, 5, 2.5), ("#ffffff", 1)))
inside.append((rr(291, 155, 158, 312, 0.01),
               ("linear", 291, 155, 449, 467, [(0, "#ffffff", 0.22), (0.45, "#ffffff", 0.05), (0.46, "#ffffff", 0), (1, "#ffffff", 0)])))
EDGE = rr(277.75, 140.75, 188.5, 338.5, 41.25)


def vd_path(d, fill):
    if fill[0] == "linear":
        _, x1, y1, x2, y2, stops = fill
        items = "".join(f'\n                    <item android:offset="{o}" android:color="{argb(c, a)}" />' for o, c, a in stops)
        return (f'    <path android:pathData="{d}">\n'
                f'        <aapt:attr name="android:fillColor">\n'
                f'            <gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
                f'android:endX="{x2}" android:endY="{y2}">{items}\n            </gradient>\n'
                f'        </aapt:attr>\n    </path>\n')
    return f'    <path android:pathData="{d}" android:fillColor="{argb(fill[0], fill[1])}" />\n'


HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        '    android:width="108dp" android:height="108dp"\n'
        '    android:viewportWidth="108" android:viewportHeight="108">\n')
NOTE = "<!-- {}: from the PC logo (ios-sweet/assets/logo.svg), made by icon.py -->\n"

fg = HEAD + NOTE.format("Launcher icon foreground: the logo's iPhone")
fg += f'  <group android:scaleX="{S:.6f}" android:scaleY="{S:.6f}" android:translateX="{TX:.4f}" android:translateY="{TY:.4f}">\n'
fg += "".join(vd_path(d, f) for d, f in shapes)
fg += f'    <group>\n    <clip-path android:pathData="{SCREEN_CLIP}" />\n'
fg += "".join(vd_path(d, f) for d, f in inside)
fg += "    </group>\n"
fg += f'    <path android:pathData="{EDGE}" android:strokeColor="{argb("#ffffff", 0.28)}" android:strokeWidth="1.5" />\n'
fg += "  </group>\n</vector>\n"

bg = HEAD + NOTE.format("Launcher icon background: the logo monitor's desktop")
bg += vd_path("M0,0 H108 V108 H0 Z", ("linear", 0, 0, 108, 108, [(0, "#1aa7b8", 1), (0.55, "#155e8a", 1), (1, "#1b2a63", 1)]))
bg += ('    <path android:pathData="M0,0 H108 V108 H0 Z">\n'
       '        <aapt:attr name="android:fillColor">\n'
       '            <gradient android:type="radial" android:centerX="34.56" android:centerY="41.04" android:gradientRadius="59.4">\n'
       f'                <item android:offset="0" android:color="{argb("#7ef0e4", 0.55)}" />\n'
       f'                <item android:offset="1" android:color="{argb("#7ef0e4", 0)}" />\n'
       '            </gradient>\n        </aapt:attr>\n    </path>\n')
bg += "</vector>\n"

# themed icon (Android 13+): the phone's body with its screen cut out, its app grid and dock inside
mono_body = rr(277, 140, 190, 340, 42) + " " + rr(291, 155, 158, 312, 27)
mono_inside = " ".join(rr(x, y, 34, 34, 9.18) for y in (192, 242, 292) for x in (307, 353, 399))
mono_inside += " " + rr(301, 396, 138, 52, 17) + " " + rr(345, 456, 50, 5, 2.5)
mono = HEAD + NOTE.format("Themed launcher icon")
mono += f'  <group android:scaleX="{S:.6f}" android:scaleY="{S:.6f}" android:translateX="{TX:.4f}" android:translateY="{TY:.4f}">\n'
mono += f'    <path android:pathData="{mono_body}" android:fillType="evenOdd" android:fillColor="#FFFFFFFF" />\n'
mono += f'    <path android:pathData="{mono_inside}" android:fillColor="#FFFFFFFF" />\n'
mono += "  </group>\n</vector>\n"

adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
            '</adaptive-icon>\n')

os.makedirs(os.path.join(RES, "mipmap-anydpi"), exist_ok=True)
for name, text in (("drawable/ic_launcher_foreground.xml", fg), ("drawable/ic_launcher_background.xml", bg),
                   ("drawable/ic_launcher_monochrome.xml", mono), ("mipmap-anydpi/ic_launcher.xml", adaptive)):
    with open(os.path.join(RES, name), "w", newline="\n", encoding="utf-8") as f:
        f.write(text)


# the same as SVG, for a look in a browser: masks circle / squircle / rounded square, and the themed one
def svg_fill(fill, gid):
    if fill[0] == "linear":
        _, x1, y1, x2, y2, stops = fill
        st = "".join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in stops)
        return (f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{st}</linearGradient>',
                f"url(#{gid})")
    return "", f'{fill[0]}" fill-opacity="{fill[1]}'


def svg_layer(prefix):
    defs, body = [], []
    for k, (d, f) in enumerate(shapes):
        g, fl = svg_fill(f, f"{prefix}s{k}")
        defs.append(g)
        body.append(f'<path d="{d}" fill="{fl}"/>')
    defs.append(f'<clipPath id="{prefix}clip"><path d="{SCREEN_CLIP}"/></clipPath>')
    body.append(f'<g clip-path="url(#{prefix}clip)">')
    for k, (d, f) in enumerate(inside):
        g, fl = svg_fill(f, f"{prefix}i{k}")
        defs.append(g)
        body.append(f'<path d="{d}" fill="{fl}"/>')
    body.append("</g>")
    body.append(f'<path d="{EDGE}" fill="none" stroke="#ffffff" stroke-opacity="0.28" stroke-width="1.5"/>')
    return "".join(defs), f'<g transform="translate({TX} {TY}) scale({S})">' + "".join(body) + "</g>"


def icon(prefix, mask):
    d, layer = svg_layer(prefix)
    return (f'<svg viewBox="18 18 72 72" width="192" height="192"><defs>{d}'
            f'<linearGradient id="{prefix}bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1aa7b8"/>'
            f'<stop offset="0.55" stop-color="#155e8a"/><stop offset="1" stop-color="#1b2a63"/></linearGradient>'
            f'<radialGradient id="{prefix}bl" gradientUnits="userSpaceOnUse" cx="34.56" cy="41.04" r="59.4">'
            f'<stop offset="0" stop-color="#7ef0e4" stop-opacity="0.55"/><stop offset="1" stop-color="#7ef0e4" stop-opacity="0"/></radialGradient>'
            f'<clipPath id="{prefix}m">{mask}</clipPath></defs><g clip-path="url(#{prefix}m)">'
            f'<rect width="108" height="108" fill="url(#{prefix}bg)"/><rect width="108" height="108" fill="url(#{prefix}bl)"/>'
            f"{layer}</g></svg>")


masks = {"circle": '<circle cx="54" cy="54" r="36"/>',
         "squircle": '<path d="M54,18 C82,18 90,26 90,54 C90,82 82,90 54,90 C26,90 18,82 18,54 C18,26 26,18 54,18 Z"/>',
         "rounded": '<rect x="18" y="18" width="72" height="72" rx="16"/>'}
cells = "".join(f'<figure>{icon(n, m)}<figcaption>{n}</figcaption></figure>' for n, m in masks.items())
themed = (f'<svg viewBox="18 18 72 72" width="192" height="192"><circle cx="54" cy="54" r="36" fill="#d7e3ff"/>'
          f'<g transform="translate({TX} {TY}) scale({S})"><path d="{mono_body}" fill-rule="evenodd" fill="#1b3a6b"/>'
          f'<path d="{mono_inside}" fill="#1b3a6b"/></g></svg>')
small = icon("sm", masks["squircle"]).replace('width="192" height="192"', 'width="48" height="48"')
html = (f'<!doctype html><meta charset="utf-8"><title>Launcher icon</title><body style="background:#202124;color:#ddd;'
        f'font:14px sans-serif;display:flex;gap:28px;flex-wrap:wrap;padding:24px">{cells}'
        f'<figure>{themed}<figcaption>themed (Android 13+)</figcaption></figure>'
        f'<figure style="background:#e8eaed;padding:12px;border-radius:12px">{small}{small}{small}<figcaption style="color:#333">launcher size</figcaption></figure></body>')
with open(PREVIEW, "w", encoding="utf-8") as f:
    f.write(html)
print("ok")
