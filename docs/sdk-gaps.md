# Plugin SDK gaps

Places where the Kiosk Satellite plugin SDK made a feature of the action bar hard or impossible, for the KS maintainer. Overlay gaps this plugin shares with the navbar, such as slow overlay creation and the stretched frame after a resize, are tracked once in the [navbar's list](https://github.com/dimatx/kiosk-satellite-navbar/blob/main/docs/sdk-gaps.md) and not repeated here.

Checked against **Kiosk Satellite 2026.10.25**, and still open in 2026.10.29: no plugin icon or overlay code changed in 2026.10.26 to 2026.10.29.

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

## 3. A wrapped overlay is cut off when the UI scale is above 100%

*Product.*

**Need:** Show the whole bar on a kiosk with **UI scale** above 100%.

**Today:** `PluginOverlayViews.OverlayFrame` measures a `WRAP` overlay in physical pixels at the display density, and `plugin_overlay.dart` divides that by `MediaQuery.devicePixelRatioOf(context)`, which already includes the UI scale. The window comes out smaller than the view by exactly the UI scale and cuts off its far side. Measured on a Fire Max 11 at 115%: the bar measured 139 x 523 and got a 121 x 455 window, and the navbar measured 600 x 117 and got 522 x 102, both 0.87. Dimensions given in dp, such as insets and offsets, land where expected, so only the measured size is affected. The plugin's views also ignore the UI scale and stay at 100%.

**Workaround:** The resize gate learns the shrink from the first window KS gives, once, and the overlay reports a size that much larger, so the window fits the content. Learning only once matters: the window lags behind each new report and is then smaller on both sides too, and learning from that enlarged the bar without end and made it flash.

**Suggestion:** Divide by the unscaled pixel ratio (`View.of(context).devicePixelRatio`) when converting a measured overlay, or measure overlays at the scaled density and give plugins the scaled density in `KsTheme`, so they follow the UI scale like the rest of the kiosk.

## 4. Plugin overlays are garbled with Impeller on Mesa and minigbm

*Product.*

**Need:** Show the bar on an x86 kiosk with an Intel GPU.

**Today:** On a Surface Pro 6 running Bliss OS 14 (Android 11, Mesa 24.0.8 on Intel UHD 620, `minigbm` gralloc), every plugin overlay draws as horizontal stripes: each row shows two squashed copies of the content, as if 32-bit pixels were read as 16-bit. Every overlay width from 198 to 329 px looked the same, so it is not an alignment problem. KS's own widgets and the dashboard draw correctly. Turning on **Legacy renderer** (`render.disable_impeller`) and restarting the app fixes it, so the fault is in how Impeller composites platform views on this driver.

**Workaround:** None in the plugin. The kiosk runs with **Legacy renderer** on.

**Suggestion:** Detect the bad combination and use Skia for platform views, or turn on **Legacy renderer** automatically when a plugin overlay is shown on Mesa with `minigbm`, the way it already does after startup crashes.

## 5. The entity picker leaves out hidden entities

*Product.*

**Need:** Put a light group on the bar, such as one that switches every art light in the house.

**Today:** The `entity` picker lists every entity except those Home Assistant marks hidden: `home_assistant_manager.dart` keeps a state only when its registry entry has no `hidden_by`. Users often hide group helpers so the members, not the group, show in Home Assistant's area views, and hidden entities are exactly the ones they still want to control from a wall panel. Home Assistant's own entity pickers still offer hidden entities; hiding only keeps them off generated dashboards. Found with a light group hidden by the user, which the picker never offered.

**Workaround:** Unhide the entity in Home Assistant, or set the item over the remote API with `configurePlugin`.

**Suggestion:** List hidden entities too, perhaps after the others or behind a "Show hidden" toggle, as Home Assistant does.

## 6. A fleet-synced plugin cannot be sized per kiosk

*Product.* Filed as [jxlarrea/kiosk-satellite#1037](https://github.com/jxlarrea/kiosk-satellite/issues/1037).

**Need:** Run the same plugin with the same settings on every kiosk through Fleet Management, while each kiosk shows it at a size that suits its panel.

**Today:** Kiosks differ in screen size, density and viewing distance, so they need different sizes. Kiosk Satellite already handles this for its own interface: **UI scale** (`ui.scale`) is set on each kiosk and is excluded from fleet sync by default. Plugins get none of it. `KsTheme` carries the device's unscaled density, so a plugin draws at 100% on a kiosk set to 115% or 125%, smaller than everything around it. A plugin can offer its own size setting, as this one does, but a fleet profile syncs a plugin's settings as one block: it can exclude individual Kiosk Satellite settings, not individual plugin settings. So a plugin's size is either the same on every follower or the plugin cannot be synced at all. Seen on a fleet where three of thirteen kiosks run a UI scale of 115% or 125%.

**Options:**
- **Plugins inherit the UI scale.** `KsTheme` (and `px`) use the kiosk's scaled density, so a plugin follows each kiosk's UI scale like the rest of the interface, and a plugin's own size setting, if any, becomes a fleet-wide multiplier on top. Simplest for users, since there is one place to set scale per kiosk. Plugins that must stay at physical size could opt out in the manifest.
- **A per-kiosk plugin scale in Kiosk Satellite.** A **Plugin scale** setting next to UI scale, per kiosk and excluded from sync by default, applied to every plugin's `KsTheme`. Lets plugins be sized apart from the rest of the interface.
- **Per-setting fleet exclusion for plugins.** A profile can exclude individual plugin settings the way it excludes Kiosk Satellite settings, or a manifest can mark a setting as per-kiosk (`"local": true`) so fleet sync never overwrites it. Useful beyond size, for anything that depends on where a kiosk hangs.

The first option alone covers sizing. The third also covers other per-kiosk plugin settings, such as which Home Assistant entities a wall panel controls.
