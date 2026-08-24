# ThorTools User Guide

ThorTools is designed around the AYN Thor's two displays. The upper display shows device state, capability checks, operation progress, results, and recovery information. The lower display contains the controls and confirmations.

## Status

The Status section reports the detected Thor variant, raw model information, firmware, build, active slot, battery, kernel, root state, Magisk state, available boot partitions, and the live upper and lower panel geometry, refresh rate, and orientation. Refresh before starting a sensitive operation. The display topology card identifies dual-display, upper-only, and lower-only states as the lid changes.

## Tweaks

Display density and animation speed are applied through Android system settings. On a rooted Thor, volume steps and boot-animation behavior are persisted through the ThorTools Magisk module and require a reboot when the dashboard says so.

The EZ Root Magisk action tracks its DownloadManager job across app restarts. Tap it once to download Magisk and tap it again after completion to open the Android installer. On the first install, Android may open the ThorTools setting for installing unknown apps; allow it there and tap the action again.

Display and animation controls require the Thor privileged root service. Volume-step and boot-animation controls additionally require Magisk and root access.

## EZ Root

The root flow checks the active slot and partition layout before every backup, patch, flash, and restore. The privileged script receives that validated slot, rechecks the image hash and partition capacity immediately before writing, and aborts if the device changes slots while the operation is starting. It requires a 35% battery level, writable app and `Download` recovery destinations, a non-empty image, and an explicit confirmation on the lower display for writes. When both partitions exist, `init_boot` is the explicit recovery target; ThorTools blocks rather than silently falling back to `boot` when that target lacks a verified stock image. A stock backup succeeds only after every discovered slot and its independent Download copy are complete.

Each recovery record is bound to its slot, partition, build fingerprint, file size, and hash. Editing, replacing, or carrying an image across an OTA invalidates it; run **Back up available slots** again after a firmware change. Preparing or flashing a root patch stays blocked until every boot slot currently exposed by the Thor has a verified stock recovery source. A fresh backup still creates both the app-local and independent `Download` copies, but if the app-local stock image is later missing or modified, patching and flashing can use the matching verified `Download` copy.

Flashing is blocked unless a stock active-slot restore source is still available. After a flash, restore, Magisk-module change, or explicit reboot request, ThorTools persists a reboot-required lock and only allows refresh or reboot until the device reports a new boot. The explicit reboot action is itself locked after the command is accepted so a second operation cannot race the restart. If a flash or restore script starts but does not complete, the same lock prevents a second write until the Thor has been rebooted. The patched-cache cleanup action only changes app-local files, including stale or cross-build patched files, and remains available even when the privileged root service is temporarily unavailable.

The lower-screen **Clear patched cache** action removes only Magisk-patched images. Stock backups remain available for restoration, and the restore flow can use the independent `Download` copy if the app-local stock image is unavailable.

While a display, module, cache, or Magisk download operation is running, the lower screen offers **Cancel**. ThorTools records the cancellation as interrupted and requires acknowledgement plus a fresh state review before retrying. Backup, patch, flash, restore, and reboot operations never expose cancellation once started.

## Recovery

Restore stock before installing an OTA. If the app is interrupted, the next launch shows the interrupted operation in the dashboard without repeating it. Reconnecting the lower display does not restart or duplicate a running operation. Keep the Download-folder copies of the stock images until the Thor has rebooted successfully.

## Scope

ThorTools v1 does not include audio changes. Those features require Thor-specific audio measurement and physical validation.
