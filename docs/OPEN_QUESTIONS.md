# Open Questions

Decisions that are intentionally not frozen yet. Update this file when an
answer becomes an ADR or roadmap phase.

| Question | Current position | Needed by |
|---|---|---|
| Do we keep continuous Auto-Live screen translation? | Currently unreachable; retained pending decision | L10 cleanup |
| Do we support Android 26-28 screen export? | Release exporter has a legacy path; verify and fix or document limitation | E12 |
| Which model families can be redistributed? | None assumed; users download separately | any catalog change |
| Should screen OCR always run in shortcut mode? | No. FAST_ONLY is accessibility-first and falls back to OCR only when the accessibility tree is empty; FULL_SCAN adds raster OCR explicitly. Measure both. | S10/C11 |
| Do we return to full-page document reflow? | Current renderer uses regions; benchmark before reintroducing | C11 |
| Is TalkBack virtual-node support a stable-release blocker? | Yes for screen/camera overlays | A10 |
| Do we add Rust for any component? | No until a profiled isolated hot path exists | R12 |
| What is the minimum supported device RAM? | Current tuning is model-dependent; define after device matrix | M11/P11 |
| Should dictionaries/word packs be user-importable? | Existing TSV path; decide product scope | E12 |
| Stable release target date for 1.6? | Not committed; gate on Horizon A exit criteria | roadmap review |

## Decision log

- Public repo + MIT code: ADR-0003.
- Camera pipeline and auto source: ADR-0001.
- No Rust rewrite now: ADR-0002.
