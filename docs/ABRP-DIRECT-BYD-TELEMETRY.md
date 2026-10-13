# ABRP 7.1.7 direct BYD telemetry proof

## Purpose

This replaces the previous BYD ABRP Link companion/token/upload chain with a local path inside patched ABRP.

```text
BYD battery API
  -> BydBatteryReader (inside patched ABRP)
  -> local ABRP /2/tlm SSE interception
  -> ABRP existing message queue / vehicle telemetry reducer
```

The patch does **not** upload the local SOC to Iternio and does not require a Generic telemetry token/API key.

## Why the stream hook

ABRP 7.1.7 already subscribes to the v2 telemetry stream using `/2/tlm?vehicleIds=...`. Replacing only that response lets ABRP continue to parse and dispatch telemetry through its own existing JavaScript path. This avoids adding a custom Java-to-Hermes bridge for the first proof.

The synthetic point is intentionally small:

```json
{"vehicleId":123,"soc":{"frac":0.7300,"time":"2026-10-06T09:54:00.000Z","provider":"TLM_API"}}
```

It is sent as an SSE `data:` event every 2 seconds. `frac` is a fraction, so 73% is `0.73`.

## BlueStacks test

1. Build BYD Multi Patcher in Android Studio.
2. Select ABRP 7.1.7 and leave **Direct BYD battery data** enabled.
3. Patch/install the supplied ABRP APKM in BlueStacks.
5. Open ABRP and select/configure one vehicle.
6. Look for:
   - `Direct BYD telemetry hook installed`
   - `Intercepting ABRP /2/tlm stream locally for vehicleId=...`
   - `Injected SOC 73.0% (emulator fixed-SOC proof value)`
7. Confirm ABRP shows live/current 73% telemetry and route planning uses it.
8. Change `EMULATOR_TEST_SOC` to `41.0`, rebuild the patcher, repatch ABRP and repeat. Then try `18.0`.

If the install log appears but the `/2/tlm` interception log never appears, the remaining uncertainty is the network-client path used by ABRP's SSE implementation; collect logcat before changing the BYD reader.

## BYD test

On non-emulator builds the hook reads, in order:

- `android.hardware.bydauto.statistic.BYDAutoStatisticDevice`
  - `getElecPercentageValue()`
  - `getSOCBatteryPercentage()`
- `android.hardware.bydauto.instrument.BYDAutoInstrumentDevice`
  - `getBatteryPercent()`

The patched ABRP manifest receives the same four read-only BYD permissions used by the old companion and visibility for `com.byd.data.collect`. If the BYD SDK lives in that verified system package instead of the boot class path, the reader can load it from there.

A value must be finite and `> 0` and `<= 100`. Zero is deliberately rejected because an uninitialised BYD API returning zero must not make ABRP believe the traction battery is empty.

## Safety / current limitation

Direct mode takes over only a telemetry stream containing exactly one vehicle ID. If ABRP requests multiple vehicle IDs, the interceptor logs the condition and uses the original network stream. This avoids copying one physical car's SOC onto several saved ABRP vehicles while the proof is still being validated.
