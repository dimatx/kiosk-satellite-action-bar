# Plugin SDK gaps

Places where the Kiosk Satellite plugin SDK made a feature of the action bar hard or impossible, for the KS maintainer. Overlay gaps this plugin shares with the navbar, such as slow overlay creation and the stretched frame after a resize, are tracked once in the [navbar's list](https://github.com/dimatx/kiosk-satellite-navbar/blob/main/docs/sdk-gaps.md) and not repeated here.

Checked against **Kiosk Satellite 2026.10.25**.

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
