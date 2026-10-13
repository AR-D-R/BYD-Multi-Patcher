# Spotify 9.1.82.2161 UI scaling

## Regression after the APKM migration

The APKM engine already scales `resources.arsc` and literal dimensions in binary XML. That continues to affect resource-backed/classic surfaces such as the large player.

Spotify 9.1.82 moved more of Home/Library to Jetpack Compose. The active Compose density factory is:

- `classes9.dex`
- `Lp/i1m;->a(Landroid/content/Context;)Lp/xgq;`
- code item `0x308a44`, instructions `0x308a54`

The returned `Lp/xgq;` is `DensityWithConverter`. `Lp/xgq;->j()F` returns density and `Lp/xgq;->W0()F` returns font scale.

The old 9.1.78 runtime font-scale hook was not ported when 9.1.82 APKM support was added, which explains why the resource-backed player could respond to the preset while Home text did not. More importantly, 9.1.82 Home uses Compose `Dp` geometry that is not represented by Android dimension resources, so a font-only port would still leave shortcut/card geometry near stock.

## 9.1.82 fix

For every slider value above 100%, `Spotify91ComposeScaleUtil` modifies the exact supported `classes9.dex` factory:

1. Reuses the old `Configuration.fontScale` read register as the selected interface factor.
2. Loads that factor as an exact 32-bit float (`const`), so 1% slider positions such as 115% are not rounded to a coarse `const/high16` value.
3. Multiplies the real `DisplayMetrics.density` by that factor.
4. Sets the Compose density object's `fontScale` and linear converter to `1.0`, preventing text from receiving the factor twice (`density × fontScale`).
5. Reclaims one code unit from the converter block, so the exact constant plus `mul-float/2addr` still increases the method by only one code unit and fits the factory's existing two-byte alignment pad. No later DEX offsets move.
6. Repairs the DEX SHA-1 and Adler32 headers.

At 100% the helper is not called, so the original Spotify DEX remains untouched apart from the existing package-identity edit.

The patch is fail-closed: the complete stock factory header/body/padding must match the verified 9.1.82.2161 base APK before any edit is made.
