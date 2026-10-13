# ABRP first-run Automatic Day/Night default

BYD Multi Patcher v1.1.74 adds an optional ABRP-profile toggle: **Default to Automatic (Day/Night)**.

The toggle is enabled by default in the patcher. When disabled, ABRP 7.1.7 keeps its stock fresh-install `displayMode = system` value (**Device settings**). When enabled, the patch redirects that one initial-state Hermes string reference to `displayMode = automatic` (**Automatic (Day/Night)**).

This is deliberately a default-only change. ABRP's normal persisted settings are not patched, so a user can later select Light, Dark, Device settings, or another supported appearance and ABRP will retain that choice normally.

The edit is version-locked to the supported ABRP 7.1.7 Hermes v98 bundle. The patcher verifies the stock `system` string ID before editing and verifies the selected result after all Hermes edits and checksum repair.

To test the first-run behaviour, use a fresh ABRP app-data state. Updating an installation that has already persisted an Appearance choice may correctly retain that existing choice.
