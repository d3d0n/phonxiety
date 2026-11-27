# phonxiety — brand

The arrow is the mark: a diagonal to the upper right with a corner bracket. Everything else is
quiet around it. The app's Material theme and the design-system tokens carry these rules.

## Palette

| Name | Hex | Role |
|---|---|---|
| Void | `#0A0710` | Main dark background and surfaces |
| Signal | `#FF4FD8` | The arrow mark only: never text, never a background |
| Trace | `#F6D8FF` | Name and headings on dark |
| Plate | `#5B2A86` | Icon plate and primary containers |
| Mute | `#B99CCC` | Captions and secondary text on dark |
| Paper | `#F4F1F6` | Main light background |
| Signal on light | `#B3128F` | The arrow on light backgrounds |
| Ink | `#140A1E` | Wordmark on light backgrounds (from the kit) |

Layout share: Void about 70 %, Trace about 18 %, Plate about 7 %, Signal about 5 %.

### Derived tones

The dark scheme grades Void toward Plate (10 %, 16 %, 22 %, 30 %) for the surface-container
steps. The light scheme grades Paper toward Plate for the same roles. Captions on Paper use
`#5C4570`; the light outline is `#7D6B8B`. Status tones (success `#07402F` on `#C6F0DF`, warning
`#6B3D00` on `#FFE3BF`, error `#6B1B12` on `#FFDAD6`, and their dark counterparts) are fixed
pairs: none is magenta, so status never competes with Signal.

## Type

- **Chakra Petch SemiBold** — the name and every heading. Name tracking −1.6 %.
- **JetBrains Mono Regular** — body text (15 sp, line height 1.65), captions (12 sp, tracking
  2.5 sp, uppercase at the render site while accessibility reads the original casing) and code.
- **JetBrains Mono Medium** — labels.

Both faces ship under the SIL Open Font License; the texts are in `docs/brand/fonts/`.

## Logo

- Clear space: half the arrow's height on every side. This is a placement rule for whoever puts
  the mark on a surface; the composables keep the arrow's own box but do not reserve that margin.
- Word at least 18 dp, sign at least 16 dp.
- Glow only on the arrow, only on dark backgrounds.
- Never rotate, recolour or stretch the mark. Use the dark version on dark, the light version on
  light.

The SVGs in `docs/brand/logo/` are the kit's own files.
