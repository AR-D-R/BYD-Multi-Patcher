# Waze 5.9 layout pass 2i

This pass is based on pass 2h (the restored later Waze 5.9 layout baseline), not the older pass 2f caller-only speedometer experiment.

## General-map / PreDrive speedometer

Uses the shared Waze 5.9 speedometer modifier at `classes5.dex @ 0x1C10EA`:

```text
fillMaxWidth(0.55078125)
wrapContentSize(TopCenter)
offset(x = -8dp, y = -24dp)
```

This is the shared PreDrive/MidDrive speedometer path and replaces the ineffective caller-only TopCenter changes used in pass 2f.

## Vehicle selector

Keeps the restored `VehicleTypePill` 20dp padding patch, moving the `Select vehicle type` pill about 30px upward at 240dpi so it clears the 90px system bar.

## Primary report launcher

Waze applies `-34dp` in the caller and `+34dp` in the outer `ReportMenuButton`, which cancel in stock. Pass 2i changes the caller to `-42dp`, producing a net `-8dp` lift (~12px at 240dpi).

That places the report button slightly lower than pass 2f/pass 2h (`-16dp`) but still above stock.
