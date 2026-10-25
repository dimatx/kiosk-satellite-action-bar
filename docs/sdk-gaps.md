# Plugin SDK gaps

Places where the Kiosk Satellite plugin SDK made a feature of the action bar hard or impossible, for the KS maintainer. Overlay gaps this plugin shares with the navbar, such as slow overlay creation and the stretched frame after a resize, are tracked once in the [navbar's list](https://github.com/dimatx/kiosk-satellite-navbar/blob/main/docs/sdk-gaps.md) and not repeated here.

Checked against **Kiosk Satellite 2026.10.31**. Gap numbers never change, so resolved gaps leave holes.

| # | Gap | Kind | What users notice |
| --- | --- | --- | --- |
| 1 | Icons from Home Assistant icon packs are not available | Product | An entity with a custom icon gets a generic one on the bar |
| 2 | A tap on an `onTop` overlay dismisses the screensaver | Product | The bar can't be used over the screensaver without waking the dashboard |

## 1. Icons from Home Assistant icon packs are not available

*Product.*

**Need:** Show the icon an entity has in Home Assistant, the way the dashboard does.

**Today:** Home Assistant entities often carry icons from community packs, such as `phu:elgato-ring-light`. `KsTheme.icon` only knows Material Design Icons, so it returns null, and the `icon` picker refuses the name: `saveSettings` with it fails with *Expected an MDI icon name*, which also rejects every other value in the same save. Measured on 2026.10.25.

**Workaround:** The plugin uses Home Assistant's icon only when it starts with `mdi:`, and otherwise an icon picked for the entity's kind.

**Suggestion:** Let `KsTheme.icon` draw icons the dashboard's frontend already has, such as packs registered through `window.customIcons`, or document that only `mdi:` names work so plugins filter them up front.

## 2. A tap on an `onTop` overlay dismisses the screensaver

*Product.*

**Need:** Keep the bar on screen over the clock or photo screensaver, so a light can be switched without waking the dashboard.

**Today:** An overlay shown with `onTop(true)` draws over the screensaver, as documented, and its views receive taps. But the same tap also dismisses the screensaver. Measured on 2026.10.25 with a ThinkSmart View: the screensaver was active, one tap on a bar item called the light's service, and `isScreensaverActive` read false a second later with the dashboard back on screen. The docs say an `onTop` overlay "does not stop or dismiss the screensaver", which holds for showing it, not for touching it.

**Workaround:** None that is worth shipping. The plugin could call `startScreensaver` again after a tap, but the dashboard would flash in between. The bar stays below the screensaver, as an ordinary overlay does.

**Suggestion:** Treat touches that an `onTop` overlay's views consume as the plugin's own, so they neither dismiss the screensaver nor reset its idle timer. Or offer an `OverlaySpec` option for it, such as `keepScreensaver(true)`.

## 4. Plugin overlays are garbled with Impeller on Mesa and minigbm

*Product.*

**Need:** Show the bar on an x86 kiosk with an Intel GPU.

**Today:** On a Surface Pro 6 running Bliss OS 14 (Android 11, Mesa 24.0.8 on Intel UHD 620, `minigbm` gralloc), every plugin overlay draws as horizontal stripes: each row shows two squashed copies of the content, as if 32-bit pixels were read as 16-bit. Every overlay width from 198 to 329 px looked the same, so it is not an alignment problem. KS's own widgets and the dashboard draw correctly. Turning on **Legacy renderer** (`render.disable_impeller`) and restarting the app fixes it, so the fault is in how Impeller composites platform views on this driver.

**Workaround:** None in the plugin. The kiosk runs with **Legacy renderer** on.

**Suggestion:** Detect the bad combination and use Skia for platform views, or turn on **Legacy renderer** automatically when a plugin overlay is shown on Mesa with `minigbm`, the way it already does after startup crashes.

## 5. The entity picker leaves out hidden entities

*Product.*

**Need:** Put a light group on the bar, such as one that switches every art light in the house.

**Today:** The `entity` picker lists every entity except those Home Assistant marks hidden: `home_assistant_manager.dart` keeps a state only when its registry entry has no `hidden_by`. A user can hide a group helper so its members, not the group, show in Home Assistant's area views, and a hidden entity can still be one they want to control from a wall panel. Home Assistant's own entity pickers still offer hidden entities; hiding only keeps them off generated dashboards. Found with a light group hidden by the user, which the picker never offered.

**Workaround:** Unhide the entity in Home Assistant, which is enough while this is rare. Setting the item over the remote API with `configurePlugin` also works.

**Suggestion:** List hidden entities too, perhaps after the others or behind a "Show hidden" toggle, as Home Assistant does. Not filed upstream: it was filed as issue 1157 and closed, because the workaround is enough. Reopen it if more people hit it. A plugin could list entities itself with `render_template` and plugin choices, but that rebuilds a picker KS already has.

## 7. Fleet sync holds a plugin update without saying so

*Product.*

**Need:** Update a plugin on the leader and see every follower get it, or learn why one did not.

**Today:** A follower whose Kiosk Satellite version differs from the leader's takes no plugin updates at all until it is on the same version. The fleet page says only *Needs 2026.10.31* on the follower's row, and the follower's Plugin Manager says nothing. Measured on 2026.10.31 with 11 of 12 followers on 2026.10.29: after a navbar release only the follower on the leader's version moved up, and the rest stayed one or two releases behind with the plugin still marked as synced.

**Workaround:** Update every follower's Kiosk Satellite first, then the plugins.

**Suggestion:** Let plugin packages sync across app versions when the plugin's own manifest allows the follower's version, or at least say on the follower's plugin page and on the leader's Plugins row that the plugin is waiting for an app update.

## 8. A fleet install cannot replace a repository install of the same plugin

*Product.*

**Need:** Move kiosks that already run a plugin from GitHub under fleet management.

**Today:** A follower that installed the plugin from its GitHub repository refuses the leader's package: the leader's row shows *Could not install Navbar: This plugin ID belongs to another repository. Uninstall it before changing sources.* The packages are the same release from the same repository, checked by SHA-256. Seen on a Fire Max 11 on 2026.10.31.

**Workaround:** Remove the plugin on that follower, which deletes its settings, and sync again.

**Suggestion:** When the leader's package comes from the same repository as the follower's install, take the install over and keep its settings, the way an update does.

## 9. The Community Plugins check fails on Windows

*Tooling.*

**Need:** Run the submission check locally before submitting, as the template's README asks.

**Today:** On Windows, `python tools/community_plugins.py check <url>` runs every check and then crashes printing the report: `UnicodeEncodeError: 'charmap' codec can't encode character '\u2705'`. The console uses cp1252 and the report starts each line with an emoji.

**Workaround:** Set `PYTHONIOENCODING=utf-8` first.

**Suggestion:** Reconfigure stdout to UTF-8 in the script (`sys.stdout.reconfigure(encoding='utf-8')`), as the other template tools already do for Windows.

## Resolved

| # | Gap | Resolved by | How the bar uses it |
| --- | --- | --- | --- |
| 3 | A wrapped overlay was cut off when the UI scale was above 100% | 2026.10.30 sizes the window with the unscaled pixel ratio | The workaround in the resize gate stays for older versions and never triggers on 2026.10.30 and newer |
| 6 | A fleet-synced plugin could not be sized per kiosk | 2026.10.30: plugins follow each kiosk's **Scale UI** (upstream issue 1037) | **Size** is a fleet-wide multiplier on top of each kiosk's Scale UI |
