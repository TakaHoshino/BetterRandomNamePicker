# BetterRNP.PluginSdk

SDK for building BetterRNP `.rnpp` plugins.

Add a package reference:

```xml
<PackageReference Include="BetterRNP.PluginSdk" Version="1.0.0" />
```

Create a `plugin.json` in the plugin project root and implement `IPlugin`.
After every build, the SDK target copies `plugin.json` to the output directory
and creates `<manifest.id>.rnpp` next to the plugin assembly.

`IPluginContext` exposes `RegisterCommand` for the main window's Plugins menu,
`RegisterFloatingBallCommand` for the floating ball's right-click menu,
`RegisterMode` for custom picker modes, and `Log` for the host log.

Hosts that support the optional scheme-command capability also implement
`ISchemeCommandPluginContext`. Plugins can cast the original context to that
interface and use `RegisterSchemeCommand` to receive a fresh
`IPluginModeContext` snapshot when the command is invoked.

Hosts can additionally implement `ICurrentPickerPluginContext`. Its
`RegisterCurrentPickerCommand` method provides an `IPluginPickerSnapshot`
containing the selected mode and the exact host candidate supplier, including
custom plugin modes.

Plugins can contribute pages to the host settings window through
`IPluginSettingsContext.RegisterSettingsItem`. The view factory is invoked on
the UI thread and should return an embeddable WPF `FrameworkElement`.

`IFloatingBallStateContext` exposes the floating ball's current position and
size in physical screen pixels, its `#RRGGBB` background color, visibility,
and a change event for live overlays or diagnostics.

`IPluginLocalizationContext` provides per-plugin translation dictionaries,
language-change notifications, translation lookup, and dynamically localized
command and settings titles.

The generated package contains the manifest, the entry assembly and other
runtime dependencies from the plugin output. `BetterRNP.dll`,
`BetterRNP.exe` and `BetterRNP.PluginSdk.dll` are intentionally excluded
because the host provides them.

## License

BetterRNP.PluginSdk is licensed under the Apache License 2.0. See the
`LICENSE` and `NOTICE` files in the BetterRNP repository.
