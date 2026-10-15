# Action Bar for Kiosk Satellite

**A bar of Home Assistant controls for [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) wall panels.** Tap to switch a light, open a shade or run a scene. Each item shows its entity's live state, and Kiosk Satellite draws the bar with its own colors, type and icons, so it looks like part of the kiosk.

A companion to [Navbar for Kiosk Satellite](https://github.com/dimatx/kiosk-satellite-navbar): the navbar moves between pages, the action bar acts in place.

## Why use it

- **One tap, no dashboard.** Lights, switches, fans, shades, scripts, scenes, buttons and media players respond to a single tap, from any page.
- **Live state.** Items light up while their entity is on or open, dim while it's unavailable, and show a tap as pending until Home Assistant confirms it.
- **Safe by default.** Locks, garage doors and gates need a long press. A single tap only says *Hold to unlock*, and an unlocked lock or open garage stands out in a warning color.
- **No token to manage.** Kiosk Satellite calls Home Assistant through its own connection. The plugin never sees a token.
- **Set up without typing.** Pick each entity from Kiosk Satellite's entity picker. Its name and icon fill themselves in, and you can change them.
- **Any edge.** A rail down the right or left side, or a bar along the top or bottom. Long names wrap to two lines.
- **Out of the way.** Set how translucent it is, swipe it away behind a small tab, and hide it on pages you choose.
- **Light.** Native Android views, no WebView. Only the items that change are redrawn.

## Install

Needs Kiosk Satellite **2026.10.25** or newer, and Home Assistant set up in Kiosk Satellite.

1. In Kiosk Satellite, open **Settings > Plugin Manager > Add plugin**.
2. Paste `https://github.com/dimatx/kiosk-satellite-action-bar`, tap **Preview**, then **Trust and install**.
3. Turn on **Action Bar**, open it and add items under **Items**.

Settings are on the plugin's page, on the kiosk or in Remote Admin, and take effect as you save them.

## What a tap does

| Entity | A tap |
| --- | --- |
| Light, switch, fan, input boolean, automation, siren, humidifier | Toggles it |
| Cover (shades, blinds, curtains) | Opens it when closed, closes it when open, and stops it while it moves |
| Cover (garage door, gate, door) | Same, but on a long press |
| Lock | Locks or unlocks it, on a long press |
| Script | Starts it, without waiting for it to finish |
| Scene | Activates it |
| Button, input button | Presses it |
| Media player | Plays or pauses it |
| Anything else | Nothing. The item still shows the entity's state |

A tap is sent once and never retried, because a call that timed out may still have reached Home Assistant. If a call fails, the plugin page says why. An item whose entity is unavailable ignores taps.

**Hold for locks and garage doors** is on by default. Turn it off and those act on a tap too. A long press works on every item.

## Settings

| Setting | What it does |
| --- | --- |
| Position | Right, Left, Top or Bottom. Right and Left make a vertical rail |
| Labels | Always or Never |
| Size | 80% to 140% |
| Opacity | How much of the dashboard shows through |
| Items | Up to 16 entities, each with an optional label, icon and icon when on |
| Hold for locks and garage doors | Locks, garage doors and gates act on a long press. On by default |
| Swipe to hide | Start visible, Start hidden or Off |
| Hide after | Seconds without a tap before the bar hides. 0 keeps it visible |
| Keep the dashboard clear | The dashboard ends beside the bar instead of under it. On by default |
| Hide the bar on | Pages where the bar stays hidden, picked from your dashboards |

Leave **Label** or **Icon** empty and the bar uses the entity's name and an icon for its kind, then saves them so you can see and change them. Icons from Home Assistant icon packs other than Material Design Icons aren't available to Kiosk Satellite plugins, so those entities get an icon for their kind instead.

**Show action bar**, **Hide action bar** and **Toggle action bar** can be mapped to a gesture or added to the kiosk drawer, under the plugin's **Actions**.

<details>
<summary><b>Build from source</b></summary>

Needs a JDK, Python 3 and the Android SDK with `ANDROID_HOME` set.

```sh
python3 tools/test.py
python3 tools/build.py
```

`tools/test.py` runs the logic tests and drives the controller with a virtual clock and a fake host that records every Home Assistant call. `python3 tools/test_views.py --serial <adb-device-serial>` measures the native views on an adb-connected development device without installing an app.

The ZIP lands in `dist/`. Install it with **Plugin Manager > Developer Tools > Install from ZIP**. To release, publish a GitHub release tagged `vX.Y.Z`. CI tests, builds and attaches the files Kiosk Satellite verifies. See [CHANGELOG.md](CHANGELOG.md) for what changed in each version.

</details>

## Credits

Built for [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) by [@jxlarrea](https://github.com/jxlarrea), whose plugin SDK, overlays, pickers, icons and Home Assistant access make a bar like this possible. Started from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) and shares its overlay work with [Navbar for Kiosk Satellite](https://github.com/dimatx/kiosk-satellite-navbar). Plugin SDK limits found along the way are tracked in [docs/sdk-gaps.md](docs/sdk-gaps.md).

## License

[Apache-2.0](LICENSE). See [NOTICE](NOTICE) for what comes from the Kiosk Satellite plugin template.
