# Known issues

## 1.4.0 — the bottom edge print is missing from a large full-size export

**What you see.** The preview is correct: the film edge above and below the
picture both carry their print (the maker's name, the frame number, the bar
code, the arrow). Export at *full size* and the bottom band comes out as bare
black with its perforations only — the print is gone, not misplaced.

**When.** Only when the *rebate canvas* is larger than ~16.7 M pixels
(16,777,216). A 3904 x 5208 negative with a 135 half-frame rebate is
4094 x 7595 = 31.1 MP, so every full-size export of that frame is affected.
The preview tier is 1609 x 2987 = 4.8 MP and never reaches the limit.

**Measured.** Bottom 7 % of the canvas, orange pixels (the print):

    2674 x 4958   (13.8 MP)   38.0 %
    2936 x 5443   (16.0 MP)   38.0 %
    3146 x 5833   (18.4 MP)    0.0 %
    3356 x 6221   (20.9 MP)    0.0 %

The boundary is the engine's dispatch wall, 1 << 24 elements.

**Why it happens.** Above that wall the canvas is rendered in bands. The
overscan node rasterises its edge-print groups for the whole canvas and
applies each one against the band it is currently drawing. A group that lies
outside the band (the bottom band, when the first band is the top of the
picture) is written past the end of the band's buffer and lost. The top band
lies inside the first band, which is why only the bottom edge is missing.

**Workaround until 1.4.1.** Export at the preview size, or turn off
*边框 → 片边 → 片边文字* (the print is the part affected; the film base,
perforations, fog and leaks all still render).

**Fix planned for 1.4.1.** Clip each edge-print group to the current band
(offset into the coverage mask, translate its origin by the band's first row)
and composite it band by band, then regress both the 13.8 MP and the 31.1 MP
cases — the bottom band must read 38 % orange at both sizes, and the 135
landscape path must stay bit-identical.
