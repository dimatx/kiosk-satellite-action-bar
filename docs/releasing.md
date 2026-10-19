# Versioning and releases

How this plugin changes without breaking kiosks that already run it. Measured on Kiosk Satellite 2026.10.31.

## Versions

- Versions follow `major.minor.patch`. A feature or a behavior change bumps the minor, a fix or a doc change bumps the patch.
- The **release tag is the version.** CI builds with `--version` taken from the tag (`v1.2.3` gives `1.2.3`), so the package can't disagree with the tag. Keep `version` in `kiosk-satellite-plugin.json` in step anyway, since local builds use it.
- **The plugin ID never changes.** Kiosk Satellite, Fleet Management and Community Plugins all key on it. A new ID is a new plugin and a new submission.

## What a user gets

- Kiosk Satellite and Community Plugins only take the **latest stable release**. Drafts and pre-releases are ignored, so nothing reaches users until a stable release is published.
- Every release must pass the Community Plugins check, or the plugin drops out of the directory until one does. Run `python3 tools/community_plugins.py check https://github.com/<owner>/<repo>` from a clone of the plugin template before tagging.
- The install review and the directory show `README.md` **from the release tag**. A README change reaches users only with a release.
- A fleet leader pushes its installed version to followers on the same Kiosk Satellite version. Followers on another version wait.

## Settings across updates

Tested by updating a kiosk in place from 0.1.3, which had a setting 0.2.0 removed, to 0.3.2. Kiosk Satellite kept every saved value whose key still exists, dropped the removed key, and the plugin ran and saved normally. A new key starts at its default. So:

- **Adding a setting is safe.** Give it a default that keeps the old behavior.
- **Removing a setting is safe.** Its saved value is dropped.
- **Never rename a key or reuse one for a different meaning.** A rename loses every user's value. Add a new key instead, and remove the old one.
- **Don't remove a `select` option or change what it means** without testing an update from a kiosk that has it chosen.
- The same rules apply to the fields inside list rows.

## Kiosk Satellite versions

- The README states the oldest Kiosk Satellite version the plugin needs.
- SDK calls added after that version are wrapped so an older kiosk keeps working without them: catch `UnsupportedOperationException` and `LinkageError`, as the code already does for content insets and icons.
- Workarounds for Kiosk Satellite bugs stay until the oldest supported version has the fix, and say which version fixed it.

## Releasing

1. Run `python3 tools/test.py`, and `python3 tools/test_views.py --serial <device>` on an adb-connected kiosk.
2. Try the build on real kiosks as a ZIP (**Plugin Manager > Developer Tools > Install from ZIP**). A ZIP can't replace a GitHub install, so remove the plugin first and restore its settings afterwards.
3. Update `CHANGELOG.md` and the manifest `version`, commit and push.
4. `gh release create vX.Y.Z --notes-file notes.md`. CI tests, builds and attaches the manifest, the ZIP and its checksum. Never attach files by hand: Kiosk Satellite and the check refuse them.
5. Update the kiosks from GitHub, which keeps their settings.