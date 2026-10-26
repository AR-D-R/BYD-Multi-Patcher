# Waze 5.9 final native-inset getter diagnostic

This diagnostic is intentionally narrow. It keeps the previous all-branches `u2.F()` test in place and adds one downstream patch to the `MainMapGeometry.carIndicationToBottom` getter in `classes5.dex`.

Patch:

- `classes5.dex @ 0x1E27C4`
- stock: `52 10 C2 0D 0F 00` (`iget v0,p1,g ; return v0`)
- diagnostic: `13 00 C8 00 0F 00` (`const/16 v0,200 ; return v0`)

`MainCanvasDelegatorImpl$c$a.g(...)` reads this getter and passes the value through `MainCanvasDelegatorImpl.O0(...)` to `nativeUpdateUserLocationInsets(int)`.

At 240 dpi, 200 px is deliberately much larger than the stock ~84 px produced by 56 dp. If active-navigation car position now stays high, this JNI inset path is confirmed and the production patch can replace the hardcoded 200 px with an adaptive value. If the car still settles low, another native/user-location update is winning after `nativeUpdateUserLocationInsets`, and the percentage-based `u2.F()` family can remain ruled out.
