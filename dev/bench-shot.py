"""bench-shot.py REF IMG: how far a screenshot is from the finished home screen, and how black it is.
Prints "DIFF BLACK": DIFF the mean difference of the icon grid (36x78 copies, rows 6-62) to REF
(bench-home-ref.png: all icons drawn below 3, a home screen still drawing its icons 19-29), BLACK the
share of near-black pixels (Calculator open 0.64, a home screen 0.04)."""
import sys
from PIL import Image, ImageChops, ImageStat

ref = Image.open(sys.argv[1]).convert('RGB').resize((36, 78))
im = Image.open(sys.argv[2]).convert('RGB')
small = im.resize((36, 78))
diff = sum(ImageStat.Stat(ImageChops.difference(small.crop((0, 6, 36, 62)), ref.crop((0, 6, 36, 62)))).mean) / 3
grey = list(im.convert('L').resize((72, 156)).getdata())
print('%.1f %.2f' % (diff, sum(1 for v in grey if v < 30) / len(grey)))
