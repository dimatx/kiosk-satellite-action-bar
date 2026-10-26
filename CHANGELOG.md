# Changelog

## 0.4.1

- The **Entity** picker also lists entities Home Assistant hides, such as a light group hidden from dashboards, with the hidden icon. Needs Kiosk Satellite 2026.10.41 or newer. Older versions ignore this and keep leaving hidden entities out, and everything else works as before.

## 0.4.0

- A tap on an **input select** or **select** opens a menu of its options, with the current one marked. Picking one sets it, and a Home Assistant automation can do the rest (issue 1). A long list scrolls.
- The manifest declares `minAppVersion` 2026.10.25, so Kiosk Satellite tells an older kiosk what it needs.

## 0.3.4

- An amber icon tile, so the icon stands out against the teal Community Plugins page.

## 0.3.3

- A simpler icon that reads at the small sizes Community Plugins and Browse plugins use.

## 0.3.2

- README: up to date on where menus open, Fleet Management and Scale UI.

## 0.3.1

- A menu longer than the bar is centered on the bar instead of on its item, so a tall menu beside a short rail hangs evenly around it. Shorter menus still open beside their item.

## 0.3.0

- An icon for Community Plugins and **Browse plugins**.
- On Kiosk Satellite 2026.10.30 and newer the bar and its menus follow each kiosk's **Scale UI**, and **Size** acts on top of it.
- The README has a one-click install button and says what the plugin does with your data.
- Plugin SDK updated to Kiosk Satellite 2026.10.31.
- A menu's card is as wide as the item's name, so a short name such as *Porch door* is no longer cut off.

## 0.2.2

- The bar and its menus are no longer cut off on a kiosk whose **UI scale** is above 100%. Kiosk Satellite sizes a plugin overlay for the scaled UI and made the window smaller than the bar, so its far end and bottom were cut off. The bar now detects this and sizes itself so the window fits (SDK gap 3).

## 0.2.1

- A cover's menu marks Open only when the cover is all the way open or opening, and Close only when it's closed or closing. A shade part way open marks neither.
- While its entity is unavailable, an open menu dims and ignores touches, and comes back when the entity does.
- A jammed lock shows its state in the error color.
- An upright slider has room at each end, so its thumb never touches the Open or Closed label, and letting go of it gives a haptic tick.
## 0.2.0

- A tap on a shade, blind or other cover opens a menu with Open, Stop and Close, and a slider for its position when the cover reports one. A tap on a lock opens Lock and Unlock. The current state is highlighted and stays live while the menu is open.
- Beside a rail the menu stands upright, its slider open at the top and its buttons nearest the bar. Above or below a bar it lies flat. Menu buttons look and size like the bar's own.
- A choice acts and closes the menu. Letting go of the slider moves the cover and keeps the menu open. A tap anywhere else, a page change or 30 seconds without a touch closes it, and the bar does not hide while a menu is open.
- A long press still acts at once, without a menu.
- The *Hold for locks and garage doors* setting and the *Hold to unlock* hint are gone: the menu replaces them.
## 0.1.3

- A tap that Kiosk Satellite delivers more than 150 ms late is noted in the KS log, as in the navbar, to help tell kiosk lag from Home Assistant lag.
- The resize workaround is now the navbar's exact copy, with its measurements documented.

## 0.1.2

- Buttons have more space between them, so each reads as its own button.
- Every button has the same width, so the rail stays straight and a name or a *Hold to unlock* hint never resizes it. A hint too wide for a one-line name shows *Hold*.
- The README has screenshots and a walkthrough for new users.

## 0.1.0

First release. Needs Kiosk Satellite 2026.10.25 or newer.

- A rail or bar of up to 16 Home Assistant entities, each showing its live state.
- A tap toggles lights, switches and fans, opens, closes or stops covers, runs scripts and scenes, presses buttons and plays or pauses media players.
- Locks, garage doors and gates act on a long press. A tap shows *Hold to unlock* instead. Unlocked locks and open garage doors use a warning color.
- A tap shows as pending until Home Assistant confirms it. Failed calls are reported on the plugin page and never retried.
- Labels and icons fill themselves in from Home Assistant. Long names wrap to two lines.
- Swipe to hide behind a tab, Hide after, Keep the dashboard clear and Hide the bar on, as in the navbar.
