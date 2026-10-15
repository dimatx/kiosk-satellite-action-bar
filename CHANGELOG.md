# Changelog

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
