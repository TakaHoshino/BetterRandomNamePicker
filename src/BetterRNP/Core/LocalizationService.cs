using System.Globalization;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Collections;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
using System.Windows.Media;

namespace BetterRNP.Core;

public sealed record AppLanguage(string Code, string ResourceKey)
{
    public string NativeName => LocalizationService.Get(ResourceKey);
}

public static class LocalizationService
{
    public const string SimplifiedChinese = "zh-Hans";
    public const string TraditionalChinese = "zh-Hant";
    public const string Cantonese = "zh-HK";
    public const string English = "en-US";
    public const string Russian = "ru-RU";
    public const string Japanese = "ja-JP";

    private const string IgnoreTag = "__i18n_ignore__";
    private const string ComponentResourceRoot = "/BetterRNP;component/Resources/";
    private const string PackResourceRoot = "pack://application:,,,/BetterRNP;component/Resources/";
    private static readonly ConditionalWeakTable<DependencyObject, OriginalValues> Originals = new();
    private static readonly object Sync = new();
    private static readonly Dictionary<string, string> DefaultStrings = new(StringComparer.Ordinal);
    private static readonly Dictionary<string, string> SourceKeys = new(StringComparer.Ordinal);
    private static readonly Dictionary<string, string> ValueKeys = new(StringComparer.Ordinal);
    private static readonly Dictionary<string, string> Translations = new(StringComparer.Ordinal);
    private static readonly Dictionary<string, Dictionary<string, string>> LanguageCache =
        new(StringComparer.Ordinal);
    private static readonly List<ResourceDictionary> LoadedLanguageDictionaries = [];
    private static readonly List<ResourceDictionary> AppliedLanguageDictionaries = [];
    private static Dictionary<string, string> _resourceValues = new(StringComparer.Ordinal);
    private static bool _loaded;

    public static IReadOnlyList<AppLanguage> SupportedLanguages { get; } =
    [
        new(SimplifiedChinese, UiText.LanguageSimplifiedChinese),
        new(TraditionalChinese, UiText.LanguageTraditionalChinese),
        new(Cantonese, UiText.LanguageCantonese),
        new(English, UiText.LanguageEnglish),
        new(Russian, UiText.LanguageRussian),
        new(Japanese, UiText.LanguageJapanese)
    ];

    public static string CurrentLanguage { get; private set; } = SimplifiedChinese;

    public static event EventHandler? LanguageChanged;

    public static void Initialize(string? languageCode)
    {
        lock (Sync)
        {
            if (!_loaded)
            {
                EventManager.RegisterClassHandler(
                    typeof(Window),
                    FrameworkElement.LoadedEvent,
                    new RoutedEventHandler((sender, _) =>
                        ApplyTo((DependencyObject)sender)));
                EventManager.RegisterClassHandler(
                    typeof(MenuItem),
                    MenuItem.SubmenuOpenedEvent,
                    new RoutedEventHandler((sender, _) =>
                        ApplyTo((DependencyObject)sender)));
                EventManager.RegisterClassHandler(
                    typeof(ContextMenu),
                    ContextMenu.OpenedEvent,
                    new RoutedEventHandler((sender, _) =>
                        ApplyTo((DependencyObject)sender)));
                EventManager.RegisterClassHandler(
                    typeof(ComboBoxItem),
                    FrameworkElement.LoadedEvent,
                    new RoutedEventHandler((sender, _) =>
                        ApplyTo((DependencyObject)sender)));
                _loaded = true;
            }
        }
        LoadLanguage(languageCode);
    }

    public static void SetLanguage(string? languageCode)
    {
        var previous = CurrentLanguage;
        LoadLanguage(languageCode);
        if (previous == CurrentLanguage)
        {
            return;
        }

        ApplyToOpenWindows();
        LanguageChanged?.Invoke(null, EventArgs.Empty);
    }

    public static string NormalizeLanguage(string? languageCode)
    {
        if (string.IsNullOrWhiteSpace(languageCode))
        {
            return SimplifiedChinese;
        }
        if (SupportedLanguages.Any(language =>
                language.Code.Equals(languageCode, StringComparison.OrdinalIgnoreCase)))
        {
            return SupportedLanguages.First(language =>
                language.Code.Equals(languageCode, StringComparison.OrdinalIgnoreCase)).Code;
        }
        if (languageCode.StartsWith("zh", StringComparison.OrdinalIgnoreCase))
        {
            return languageCode.Contains("HK", StringComparison.OrdinalIgnoreCase)
                || languageCode.Contains("MO", StringComparison.OrdinalIgnoreCase)
                    ? Cantonese
                    : languageCode.Contains("TW", StringComparison.OrdinalIgnoreCase)
                        ? TraditionalChinese
                        : SimplifiedChinese;
        }
        if (languageCode.StartsWith("ru", StringComparison.OrdinalIgnoreCase))
        {
            return Russian;
        }
        if (languageCode.StartsWith("ja", StringComparison.OrdinalIgnoreCase))
        {
            return Japanese;
        }
        if (languageCode.StartsWith("en", StringComparison.OrdinalIgnoreCase))
        {
            return English;
        }
        return SimplifiedChinese;
    }

    public static string T(string source)
    {
        if (string.IsNullOrEmpty(source))
        {
            return source;
        }
        if (GetIfDefined(source) is { } resourceValue)
        {
            return resourceValue;
        }
        if (SourceKeys.TryGetValue(source, out var sourceKey)
            && GetIfDefined(sourceKey) is { } translated)
        {
            return translated;
        }
        if (ValueKeys.TryGetValue(source, out var localizedKey)
            && GetIfDefined(localizedKey) is { } localized)
        {
            return localized;
        }
        return Translations.TryGetValue(source, out var translatedSource)
            ? translatedSource
            : source;
    }

    public static string Get(string resourceKey)
        => GetIfDefined(resourceKey) ?? resourceKey;

    private static string? GetIfDefined(string key)
    {
        if (Application.Current is not null)
        {
            try
            {
                if (Application.Current.TryFindResource(key) is string resolved)
                {
                    return resolved;
                }
            }
            catch (InvalidOperationException)
            {
                // The application may be shutting down while a window is closing.
            }
        }
        return _resourceValues.TryGetValue(key, out var value) ? value : null;
    }

    public static string GetDefault(string resourceKey)
        => DefaultStrings.TryGetValue(resourceKey, out var text) ? text : resourceKey;

    public static string Format(string source, params object?[] arguments)
        => string.Format(CultureInfo.CurrentCulture, T(source), arguments);

    public static void SetText(TextBlock target, string resourceKey)
    {
        SetOriginal(target, TextBlock.TextProperty, resourceKey);
        target.SetCurrentValue(TextBlock.TextProperty, T(resourceKey));
    }

    public static void SetTitle(Window target, string resourceKey)
    {
        SetOriginal(target, Window.TitleProperty, resourceKey);
        target.SetCurrentValue(Window.TitleProperty, T(resourceKey));
    }

    public static void SetContent(ContentControl target, string resourceKey)
    {
        SetOriginal(target, ContentControl.ContentProperty, resourceKey);
        target.SetCurrentValue(ContentControl.ContentProperty, T(resourceKey));
    }

    public static void ApplyToApplication()
    {
        ApplyToOpenWindows();
    }

    public static void ApplyTo(DependencyObject root)
    {
        var visited = new HashSet<DependencyObject>(ReferenceEqualityComparer.Instance);
        ApplyObject(root, visited);
    }

    private static void LoadLanguage(string? languageCode)
    {
        CurrentLanguage = NormalizeLanguage(languageCode);
        try
        {
            CultureInfo.CurrentUICulture = CultureInfo.GetCultureInfo(CurrentLanguage);
        }
        catch
        {
            CultureInfo.CurrentUICulture = CultureInfo.GetCultureInfo(SimplifiedChinese);
        }

        LoadTranslations(CurrentLanguage);
    }

    private static void LoadTranslations(string languageCode)
    {
        RemoveAppliedLanguageResources();
        var defaults = LoadStringTable("Strings.xaml");
        if (defaults.Count > 0)
        {
            LanguageCache[SimplifiedChinese] = defaults;
            DefaultStrings.Clear();
            foreach (var pair in defaults)
            {
                DefaultStrings[pair.Key] = pair.Value;
            }
        }
        else if (LanguageCache.TryGetValue(SimplifiedChinese, out var cachedDefaults))
        {
            DefaultStrings.Clear();
            foreach (var pair in cachedDefaults)
            {
                DefaultStrings[pair.Key] = pair.Value;
            }
        }
        PopulateDefaultStringsFromApplication();

        _resourceValues = new Dictionary<string, string>(
            DefaultStrings,
            StringComparer.Ordinal);
        var dictionaries = new List<ResourceDictionary>();
        if (languageCode == Cantonese)
        {
            OverlayLanguage(TraditionalChinese, _resourceValues, dictionaries);
            OverlayLanguage(Cantonese, _resourceValues, dictionaries);
        }
        else if (languageCode != SimplifiedChinese)
        {
            OverlayLanguage(languageCode, _resourceValues, dictionaries);
        }

        ApplyLanguageResources(dictionaries);
        if (Application.Current is not null)
        {
            foreach (var key in DefaultStrings.Keys)
            {
                if (Application.Current.TryFindResource(key) is string resolved)
                {
                    _resourceValues[key] = resolved;
                }
            }
        }

        Translations.Clear();
        SourceKeys.Clear();
        ValueKeys.Clear();
        foreach (var pair in DefaultStrings)
        {
            SourceKeys.TryAdd(pair.Value, pair.Key);
            ValueKeys.TryAdd(pair.Value, pair.Key);
            if (_resourceValues.TryGetValue(pair.Key, out var translated)
                && !string.Equals(pair.Value, translated, StringComparison.Ordinal))
            {
                Translations[pair.Value] = translated;
            }
        }
        foreach (var language in LanguageCache.Values)
        {
            foreach (var pair in language)
            {
                ValueKeys.TryAdd(pair.Value, pair.Key);
            }
        }
    }

    private static void PopulateDefaultStringsFromApplication()
    {
        if (Application.Current is null)
        {
            return;
        }
        foreach (var field in typeof(UiText).GetFields(
                     System.Reflection.BindingFlags.Public
                     | System.Reflection.BindingFlags.Static))
        {
            if (field.GetValue(null) is not string key
                || DefaultStrings.ContainsKey(key)
                || Application.Current.TryFindResource(key) is not string source)
            {
                continue;
            }
            DefaultStrings[key] = source;
        }
    }

    private static void OverlayLanguage(
        string languageCode,
        IDictionary<string, string> target,
        ICollection<ResourceDictionary> dictionaries)
    {
        var resourceValues = LoadLanguageStrings(languageCode);
        var dictionary = LoadResourceDictionary($"Strings.{languageCode}.xaml");
        dictionaries.Add(dictionary);
        foreach (var pair in resourceValues)
        {
            target[pair.Key] = pair.Value;
        }
    }

    private static Dictionary<string, string> LoadLanguageStrings(string languageCode)
    {
        var loaded = LoadStringTable($"Strings.{languageCode}.xaml");
        if (loaded.Count > 0)
        {
            LanguageCache[languageCode] = loaded;
            return loaded;
        }
        return LanguageCache.TryGetValue(languageCode, out var cached)
            ? cached
            : new Dictionary<string, string>(StringComparer.Ordinal);
    }

    private static Dictionary<string, string> LoadStringTable(string fileName)
        => ReadStrings(LoadResourceDictionary(fileName));

    private static ResourceDictionary LoadResourceDictionary(string fileName)
    {
        var resourceUri = new Uri(
            ComponentResourceRoot + fileName,
            UriKind.Relative);
        try
        {
            if (Application.LoadComponent(resourceUri) is ResourceDictionary { Count: > 0 } loaded)
            {
                return loaded;
            }
        }
        catch
        {
            // Fall back to assigning the source URI directly below.
        }

        if (Application.Current is not null)
        {
            try
            {
                var packed = new ResourceDictionary
                {
                    Source = new Uri(PackResourceRoot + fileName, UriKind.Absolute)
                };
                if (packed.Count > 0)
                {
                    return packed;
                }
            }
            catch
            {
                // Try a relative component URI below.
            }
        }

        try
        {
            return new ResourceDictionary
            {
                Source = new Uri(ComponentResourceRoot + fileName, UriKind.Relative)
            };
        }
        catch
        {
            return new ResourceDictionary();
        }
    }

    private static Dictionary<string, string> ReadStrings(ResourceDictionary dictionary)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (DictionaryEntry entry in dictionary)
        {
            if (entry.Key is string key && entry.Value is string value)
            {
                values[key] = value;
            }
        }
        return values;
    }

    private static void ApplyLanguageResources(
        IReadOnlyCollection<ResourceDictionary> dictionaries)
    {
        LoadedLanguageDictionaries.Clear();
        LoadedLanguageDictionaries.AddRange(dictionaries);
        if (Application.Current is null)
        {
            return;
        }

        var merged = Application.Current.Resources.MergedDictionaries;
        foreach (var dictionary in LoadedLanguageDictionaries)
        {
            merged.Add(dictionary);
            AppliedLanguageDictionaries.Add(dictionary);
        }
    }

    private static void RemoveAppliedLanguageResources()
    {
        if (Application.Current is null)
        {
            AppliedLanguageDictionaries.Clear();
            return;
        }
        var merged = Application.Current.Resources.MergedDictionaries;
        foreach (var previous in AppliedLanguageDictionaries)
        {
            merged.Remove(previous);
        }
        AppliedLanguageDictionaries.Clear();
    }

    private static void ApplyToOpenWindows()
    {
        if (Application.Current is null)
        {
            return;
        }
        foreach (Window window in Application.Current.Windows)
        {
            ApplyTo(window);
        }
    }

    private static void ApplyObject(DependencyObject target, HashSet<DependencyObject> visited)
    {
        if (!visited.Add(target)
            || target is FrameworkElement { Tag: IgnoreTag })
        {
            return;
        }

        if (target is Window window)
        {
            ApplyProperty(window, Window.TitleProperty);
        }
        if (target is TextBlock textBlock)
        {
            ApplyProperty(textBlock, TextBlock.TextProperty);
        }
        if (target is ContentControl contentControl && contentControl.Content is string)
        {
            ApplyProperty(contentControl, ContentControl.ContentProperty);
        }
        if (target is HeaderedContentControl headeredContent
            && headeredContent.Header is string)
        {
            ApplyProperty(headeredContent, HeaderedContentControl.HeaderProperty);
        }
        if (target is HeaderedItemsControl headeredItems
            && headeredItems.Header is string)
        {
            ApplyProperty(headeredItems, HeaderedItemsControl.HeaderProperty);
        }
        if (target is FrameworkElement element && element.ToolTip is string)
        {
            ApplyProperty(element, FrameworkElement.ToolTipProperty);
        }
        if (target is DataGrid dataGrid)
        {
            foreach (var column in dataGrid.Columns)
            {
                ApplyProperty(column, DataGridColumn.HeaderProperty);
            }
        }

        foreach (var child in LogicalTreeHelper.GetChildren(target).OfType<DependencyObject>())
        {
            ApplyObject(child, visited);
        }
        if (target is Visual or System.Windows.Media.Media3D.Visual3D)
        {
            for (var index = 0; index < VisualTreeHelper.GetChildrenCount(target); index++)
            {
                var child = VisualTreeHelper.GetChild(target, index);
                ApplyObject(child, visited);
            }
        }
    }

    private static void ApplyProperty(DependencyObject target, DependencyProperty property)
    {
        if (BindingOperations.IsDataBound(target, property))
        {
            return;
        }
        var original = GetOriginal(target, property);
        if (original is not string source || string.IsNullOrWhiteSpace(source))
        {
            return;
        }

        var translated = T(source);
        if (!string.Equals(target.GetValue(property) as string, translated, StringComparison.Ordinal))
        {
            target.SetCurrentValue(property, translated);
        }
    }

    private static object? GetOriginal(DependencyObject target, DependencyProperty property)
    {
        var values = Originals.GetOrCreateValue(target).Values;
        if (values.TryGetValue(property, out var original))
        {
            return original;
        }
        original = target.GetValue(property);
        values[property] = original;
        return original;
    }

    private static void SetOriginal(
        DependencyObject target,
        DependencyProperty property,
        object? value)
        => Originals.GetOrCreateValue(target).Values[property] = value;

    private sealed class OriginalValues
    {
        public Dictionary<DependencyProperty, object?> Values { get; } = new();
    }
}
