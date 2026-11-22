# Interface translation

Chinese is the source language. Every label in the app is written as its
Chinese string, and `Lang.t(...)` maps that string to English at the moment it
is handed to a widget (`label`, `mixedLabel`, `TextViews` created through them,
toasts, dialogs, `CompareView`'s own tags).

Two tables:

| file | what it holds |
|---|---|
| `en.tsv` | whole strings. A hit is exact, nothing else. |
| `phrases.tsv` | fragments, used only when the text still holds Chinese. Messages built as `"已导出 " + fw + "x" + fh` cannot be in a dictionary, so they are translated piece by piece, longest match first. |

Format: `Chinese<TAB>English`, one pair per line, `#` starts a comment.
`\n`, `\t`, `\r` and `\\` are escaped; **do not use the csv module to read it** —
several English values begin with a `"`, which csv would treat as an opening
quote and swallow the following rows (this ate 亮度 and friends for an hour).

`build-apk.sh` copies these two files into `assets/i18n/` before packaging, so a
fresh clone ships the tables even though `app/src/main/assets/` is gitignored.

## Where the strings come from

`en.tsv` was seeded from a lexical scan of `MainActivity.java`
(every CJK string run, folded over `"a" + "b"`), then hand-corrected. The scan
lives outside this repo; to re-derive it, take every string literal run in the
Java sources that contains CJK and add the ones that are missing. The audit rule
that matters: **after translation, no CJK may remain** — that is the check
`LangTest`-style probes and `python3 audit.py` implement.

## Adding a string

1. Write the label in Chinese in the Java source, as always.
2. Add the pair to `en.tsv` (whole string) — or to `phrases.tsv` with the
   fragment form if the message is assembled around values.
3. `bash tools/i18n/install.sh` (copies both into `app/src/main/assets/i18n/`),
   then `bash build-apk.sh`.

A missing entry is not fatal: the Chinese string is shown unchanged.
