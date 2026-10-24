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

The generated package contains the manifest, the entry assembly and other
runtime dependencies from the plugin output. `BetterRNP.dll`,
`BetterRNP.exe` and `BetterRNP.PluginSdk.dll` are intentionally excluded
because the host provides them.

## License

BetterRNP.PluginSdk is licensed under the Apache License 2.0. See the
`LICENSE` and `NOTICE` files in the BetterRNP repository.
