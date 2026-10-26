# Waze 5.9 layout pass 2e — primary report launcher

## Problem

The existing Waze 5.9 report patch moved `SubmenuButtons` inside the reporting menu. It did not move the round yellow `+` launcher on the map.

## Exact 5.9.5.4 target

In `classes4.dex`, Compose function `ReportMenuButton` (`ReportMenuButton.kt:50`) builds the Android `MapReportButtonView`. After the test-tag modifier it explicitly applies:

```text
offset(x = 0dp, y = +34dp)
```

The literal is at DEX offset `0x4E7C54` in the exact supported APKM (`base.apk` SHA-256 `98ba20d667948424f18b3dbefd78ee17160e97a1353812d0477dc95c0b4c4f51`).

Stock bytes:

```text
13 0e 22 00   # const/16 v14, 34
```

Pass 2e:

```text
13 0e 00 00   # const/16 v14, 0
```

At 240dpi this raises the entire visible/clickable primary report button by `34dp = 51px`.

## Newer Waze comparison

The exact Waze 5.24.4.900 binary was checked as well. Its `ReportMenuButton` (`ReportMenuButton.kt:49`) also applies an explicit `+34dp` Y offset before the AndroidView is created. Therefore the previous `-56dp` report-menu patch in the newer profile was a submenu fix, not a primary-launcher fix.

## Scope

This patch changes only the primary round report launcher position. The existing Waze 5.9 `SubmenuButtons` `-56dp` patch and sparse `ItemsGrid` layout fix remain unchanged.
