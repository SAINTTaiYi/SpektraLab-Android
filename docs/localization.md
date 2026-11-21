# Add an app language

The **Language** setting lists the catalogs packaged with the app. The setting
keeps its English label in every language so you can always find it.

1. Add a UTF-8 JSON file under [`app/src/main/locales/`](../app/src/main/locales/).
   Use the language tag as the filename (`fr.json`, `pt-BR.json`, or
   `zh-Hant.json`).
2. Set `tag` to the same tag and `name` to what speakers of that language call
   it. Copy message IDs from [`en.json`](../app/src/main/locales/en.json) and
   translate the values. You can start with just a few translations.
3. Rebuild the APK and select the new language under **Settings → Language**.
   The app discovers it without a Java change. Missing messages use English.

For example, `fr.json` could start with:

```json
{
  "tag": "fr",
  "name": "Français",
  "strings": {
    "settings.app_language": "Langue de l’application",
    "file.import_photos": "Importer des photos",
    "file.export_photo": "Exporter la photo"
  }
}
```

- Keep message IDs unchanged. English copy edits belong in `en.json`; other
  languages use the same IDs.
- Keep leading/trailing spaces where a message is a fragment next to a number
  or filename. For new dynamic text, prefer entire sentences with
  `AppLanguage.format("message.id", args...)`. Translators can reorder arguments
  using `%1$s`, `%2$d`, etc. Use `%%` for a literal percent in a format string.
- Check longer translations on a small screen. Right-to-left layouts have not
  been verified. Android system dialogs and raw native diagnostics are outside
  this catalog.
- Update announcements can supply `notes_pt_BR`, `notes_pt`, etc. If no
  matching notes exist, the app uses English notes when available.

The APK build validates and packages the catalogs from this checkout. Run
`python app/build/check-locales.py` to check message IDs and format arguments
without building an APK.
