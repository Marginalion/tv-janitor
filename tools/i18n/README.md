# Translations

The app's Russian text is the source. `src/keys.json` lists every string the app shows; `src/core.json` is the
same list without surrounding spaces, and `<lang>.json` is its translation, line for line (same order, same
count). `python3 gen.py ../../app/src/main/assets/i18n` writes the `<lang>.tsv` files the app reads.

The app uses the TV's language when a file exists for it, English otherwise.

**Add or fix a language:** copy `en.json` to `<language code>.json` (two letters, as Android reports it:
`de`, `pl`, `ko`…), translate each line, keep emoji and the punctuation at the start and end of a line (many
lines are joined with numbers or names in the app), run `gen.py`, and open a pull request.

The first translations were made with an AI and checked only lightly: corrections from native speakers are
very welcome.
