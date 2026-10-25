using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace BetterRNP.Core;

public enum BackdropMode
{
    Fill,
    Fit,
    Stretch,
    Center,
    Crop
}

public sealed class ThemeImage
{
    public ThemeImage(string id, string path, string displayName)
    {
        Id = id;
        Path = path;
        DisplayName = displayName;
        Preview = LoadPreview(path);
    }

    public string Id { get; }
    public string Path { get; }
    public string DisplayName { get; }
    public BitmapImage? Preview { get; }
    public bool HasPreview => Preview is not null;

    private static BitmapImage? LoadPreview(string path)
    {
        try
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.CreateOptions = BitmapCreateOptions.IgnoreImageCache;
            bitmap.DecodePixelWidth = 180;
            bitmap.UriSource = new Uri(path, UriKind.Absolute);
            bitmap.EndInit();
            bitmap.Freeze();
            return bitmap;
        }
        catch
        {
            return null;
        }
    }
}

public sealed record ThemeStyle(string ImagePath, BackdropMode Mode, int MaskAlpha, Rect Crop);

public sealed class ThemeService
{
    public const string CategoryMain = "main";
    public const string CategoryConfig = "config";
    public const string CategoryScheme = "scheme";
    public const string CategoryNumber = "number";
    public const string CategorySeat = "seat";
    public const string CategorySettings = "settings";
    public const string CategoryTheme = "theme";
    public const string CategoryOther = "other";

    public static readonly string[] CategoryIds =
    {
        CategoryMain, CategoryConfig, CategoryScheme, CategoryNumber,
        CategorySeat, CategorySettings, CategoryOther
    };

    public static readonly IReadOnlyDictionary<string, string> CategoryLabelKeys =
        new Dictionary<string, string>
        {
            [CategoryMain] = UiText.ThemeMainWindow,
            [CategoryConfig] = UiText.ThemeConfig,
            [CategoryScheme] = UiText.ThemeSchemes,
            [CategoryNumber] = UiText.ThemeNumber,
            [CategorySeat] = UiText.ThemeSeat,
            [CategorySettings] = UiText.ThemeSettings,
            [CategoryOther] = UiText.ThemeOther
        };

    private sealed class Attachment
    {
        public required Window Window { get; init; }
        public required string Category { get; init; }
        public required Grid Root { get; init; }
        public required Image Image { get; init; }
        public required Border Mask { get; init; }
    }

    private readonly AppPaths _paths;
    private readonly ISettingsStore _settings;
    private readonly LogService _log;
    private readonly Dictionary<Window, Attachment> _attachments = new();

    public ThemeService(AppPaths paths, ISettingsStore settings, LogService log)
    {
        _paths = paths;
        _settings = settings;
        _log = log;
    }

    public bool IsEnabled
    {
        get => bool.TryParse(GetTheme("enabled"), out var enabled) && enabled;
        set
        {
            SetTheme("enabled", value.ToString().ToLowerInvariant());
            SaveAndApply();
        }
    }

    public int MaskAlpha
    {
        get => Math.Clamp(ParseInt(GetTheme("mask"), 40), 0, 180);
        set
        {
            SetTheme("mask", Math.Clamp(value, 0, 180).ToString());
            SaveAndApply();
        }
    }

    public string GlobalImageId => GetTheme("g.image", string.Empty) ?? string.Empty;

    public BackdropMode GlobalMode => ParseMode(GetTheme("g.mode"));
    public Rect GlobalCrop => ParseCrop(GetTheme("g.crop"));

    public IReadOnlyList<ThemeImage> ListImages()
    {
        Directory.CreateDirectory(_paths.ThemeImagesDir);
        return Directory.EnumerateFiles(_paths.ThemeImagesDir)
            .Where(IsSupportedImage)
            .OrderBy(path => path, StringComparer.CurrentCultureIgnoreCase)
            .Select(path =>
            {
                var id = Path.GetFileName(path);
                var alias = GetTheme("name." + id, string.Empty);
                return new ThemeImage(id, path,
                    string.IsNullOrWhiteSpace(alias) ? Path.GetFileNameWithoutExtension(path) : alias);
            })
            .ToList();
    }

    public string ImportImage(string sourcePath)
    {
        if (!IsSupportedImage(sourcePath))
        {
            throw new InvalidOperationException(
                LocalizationService.Get(UiText.ThemeUnsupportedImageFormat));
        }

        Directory.CreateDirectory(_paths.ThemeImagesDir);
        var extension = Path.GetExtension(sourcePath).ToLowerInvariant();
        var baseName = SanitizeFileName(Path.GetFileNameWithoutExtension(sourcePath));
        var id = baseName + extension;
        var counter = 2;
        while (File.Exists(Path.Combine(_paths.ThemeImagesDir, id)))
        {
            id = $"{baseName}_{counter++}{extension}";
        }
        File.Copy(sourcePath, Path.Combine(_paths.ThemeImagesDir, id));
        _log.Write($"主题图片导入: {id}", "THEME_IMPORT");
        return id;
    }

    public bool DeleteImage(string imageId)
    {
        if (string.IsNullOrWhiteSpace(imageId))
        {
            return false;
        }

        var path = Path.Combine(_paths.ThemeImagesDir, imageId);
        if (!File.Exists(path))
        {
            return false;
        }

        File.Delete(path);
        if (GlobalImageId == imageId)
        {
            SetTheme("g.image", null);
            SetTheme("g.crop", null);
        }
        foreach (var category in CategoryIds)
        {
            if (GetCategoryImageId(category) == imageId)
            {
                SetTheme(CategoryKey(category, "image"), null);
                SetTheme(CategoryKey(category, "crop"), null);
            }
        }
        SetTheme("name." + imageId, null);
        SaveAndApply();
        _log.Write($"主题图片删除: {imageId}", "THEME_DELETE");
        return true;
    }

    public void SetGlobalImage(string? imageId)
    {
        SetTheme("g.image", string.IsNullOrWhiteSpace(imageId) ? null : imageId);
        SaveAndApply();
    }

    public void SetGlobalMode(BackdropMode mode)
    {
        SetTheme("g.mode", mode.ToString());
        SaveAndApply();
    }

    public void SetGlobalCrop(Rect crop)
    {
        SetTheme("g.crop", CropToString(crop));
        SaveAndApply();
    }

    public string? GetCategoryImageId(string category)
        => GetTheme(CategoryKey(category, "image"));

    public BackdropMode GetCategoryMode(string category)
        => ParseMode(GetTheme(CategoryKey(category, "mode")) ?? string.Empty) is var mode
           && ContainsTheme(CategoryKey(category, "mode")) ? mode : GlobalMode;

    public Rect GetCategoryCrop(string category)
    {
        var key = CategoryKey(category, "crop");
        return ContainsTheme(key)
            ? ParseCrop(GetTheme(key))
            : GlobalCrop;
    }

    public void SetCategoryImage(string category, string? imageId)
    {
        SetTheme(CategoryKey(category, "image"),
            string.IsNullOrWhiteSpace(imageId) ? null : imageId);
        SaveAndApply();
    }

    public void SetCategoryMode(string category, BackdropMode mode)
    {
        SetTheme(CategoryKey(category, "mode"), mode.ToString());
        SaveAndApply();
    }

    public void SetCategoryCrop(string category, Rect crop)
    {
        SetTheme(CategoryKey(category, "crop"), CropToString(crop));
        SaveAndApply();
    }

    public void ClearCategory(string category)
    {
        SetTheme(CategoryKey(category, "image"), null);
        SetTheme(CategoryKey(category, "mode"), null);
        SetTheme(CategoryKey(category, "crop"), null);
        SaveAndApply();
    }

    public void Reset()
    {
        try
        {
            _settings.ClearSection(SettingsSections.Theme);
            _settings.SetString(SettingsSections.Theme, "enabled", "false");
            _settings.SetString(SettingsSections.Theme, "mask", "40");
        }
        catch (Exception ex)
        {
            _log.Write(LogLevel.Error, $"保存主题设置失败: {ex.Message}", "THEME_SAVE_ERROR");
        }
        SaveAndApply();
        _log.Write("主题设置已恢复默认", "THEME_RESET");
    }

    public void Attach(Window window, string category)
    {
        if (_attachments.ContainsKey(window))
        {
            return;
        }

        var original = window.Content as UIElement;
        if (original is null)
        {
            if (window.Content is object content)
            {
                original = new ContentControl { Content = content };
            }
            else
            {
                original = new Grid();
            }
        }

        var root = new Grid { Background = Brushes.Transparent };
        var image = new Image
        {
            Stretch = Stretch.UniformToFill,
            IsHitTestVisible = false,
            Visibility = Visibility.Collapsed
        };
        var mask = new Border
        {
            Background = Brushes.White,
            IsHitTestVisible = false,
            Opacity = 0,
            Visibility = Visibility.Collapsed
        };

        root.Children.Add(image);
        root.Children.Add(mask);
        window.Content = null;
        root.Children.Add(original);
        window.Content = root;

        var attachment = new Attachment
        {
            Window = window,
            Category = category,
            Root = root,
            Image = image,
            Mask = mask
        };
        _attachments[window] = attachment;
        window.Closed += OnWindowClosed;
        Apply(attachment);
    }

    public void ApplyAll()
    {
        foreach (var attachment in _attachments.Values.ToList())
        {
            Apply(attachment);
        }
    }

    public ThemeStyle? ResolveStyle(string category)
    {
        if (!IsEnabled)
        {
            return null;
        }

        var imageId = GetCategoryImageId(category);
        if (string.IsNullOrWhiteSpace(imageId))
        {
            imageId = GlobalImageId;
        }
        if (string.IsNullOrWhiteSpace(imageId))
        {
            return null;
        }

        var path = Path.Combine(_paths.ThemeImagesDir, imageId);
        if (!File.Exists(path))
        {
            return null;
        }
        return new ThemeStyle(path, GetCategoryMode(category), MaskAlpha, GetCategoryCrop(category));
    }

    private void Apply(Attachment attachment)
    {
        var style = ResolveStyle(attachment.Category);
        if (style is null)
        {
            attachment.Image.Source = null;
            attachment.Image.Visibility = Visibility.Collapsed;
            attachment.Mask.Visibility = Visibility.Collapsed;
            attachment.Root.Background = Brushes.Transparent;
            return;
        }

        try
        {
            var bitmap = new BitmapImage();
            bitmap.BeginInit();
            bitmap.CacheOption = BitmapCacheOption.OnLoad;
            bitmap.UriSource = new Uri(style.ImagePath, UriKind.Absolute);
            bitmap.EndInit();
            bitmap.Freeze();
            ImageSource source = bitmap;
            if (style.Mode == BackdropMode.Crop)
            {
                var crop = NormalizeCrop(style.Crop);
                var x = Math.Clamp((int)Math.Round(crop.X * bitmap.PixelWidth), 0, bitmap.PixelWidth - 1);
                var y = Math.Clamp((int)Math.Round(crop.Y * bitmap.PixelHeight), 0, bitmap.PixelHeight - 1);
                var width = Math.Clamp((int)Math.Round(crop.Width * bitmap.PixelWidth), 1, bitmap.PixelWidth - x);
                var height = Math.Clamp((int)Math.Round(crop.Height * bitmap.PixelHeight), 1, bitmap.PixelHeight - y);
                var cropped = new CroppedBitmap(bitmap, new Int32Rect(x, y, width, height));
                cropped.Freeze();
                source = cropped;
            }
            attachment.Image.Source = source;
            attachment.Image.Stretch = style.Mode switch
            {
                BackdropMode.Fit => Stretch.Uniform,
                BackdropMode.Stretch => Stretch.Fill,
                BackdropMode.Center => Stretch.None,
                _ => Stretch.UniformToFill
            };
            attachment.Image.HorizontalAlignment = HorizontalAlignment.Center;
            attachment.Image.VerticalAlignment = VerticalAlignment.Center;
            attachment.Image.Visibility = Visibility.Visible;
            attachment.Mask.Opacity = style.MaskAlpha / 255d;
            attachment.Mask.Visibility = style.MaskAlpha > 0 ? Visibility.Visible : Visibility.Collapsed;
            attachment.Root.Background = Brushes.Transparent;
        }
        catch
        {
            attachment.Image.Source = null;
            attachment.Image.Visibility = Visibility.Collapsed;
            attachment.Mask.Visibility = Visibility.Collapsed;
        }
    }

    private void SaveAndApply()
    {
        ApplyAll();
    }

    private string? GetTheme(string name, string? fallback = null)
        => _settings.GetString(SettingsSections.Theme, name) ?? fallback;

    private void SetTheme(string name, string? value)
    {
        try
        {
            _settings.SetString(SettingsSections.Theme, name, value);
        }
        catch (Exception ex)
        {
            _log.Write(LogLevel.Error, $"保存主题设置失败: {ex.Message}", "THEME_SAVE_ERROR");
        }
    }

    private bool ContainsTheme(string name)
        => _settings.HasValue(SettingsSections.Theme, name);

    private void OnWindowClosed(object? sender, EventArgs e)
    {
        if (sender is Window window)
        {
            window.Closed -= OnWindowClosed;
            _attachments.Remove(window);
        }
    }

    private static string CategoryKey(string category, string suffix) => $"cat.{category}.{suffix}";

    private static BackdropMode ParseMode(string? value)
        => Enum.TryParse<BackdropMode>(value, true, out var mode) ? mode : BackdropMode.Fill;

    private static Rect ParseCrop(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return new Rect(0, 0, 1, 1);
        }
        var parts = value.Split(',');
        if (parts.Length != 4
            || !double.TryParse(parts[0], System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var x)
            || !double.TryParse(parts[1], System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var y)
            || !double.TryParse(parts[2], System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var width)
            || !double.TryParse(parts[3], System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var height))
        {
            return new Rect(0, 0, 1, 1);
        }
        return NormalizeCrop(new Rect(x, y, width, height));
    }

    private static Rect NormalizeCrop(Rect crop)
    {
        var x = Math.Clamp(crop.X, 0, 0.999);
        var y = Math.Clamp(crop.Y, 0, 0.999);
        var width = Math.Clamp(crop.Width, 0.001, 1 - x);
        var height = Math.Clamp(crop.Height, 0.001, 1 - y);
        return new Rect(x, y, width, height);
    }

    private static string CropToString(Rect crop)
    {
        var normalized = NormalizeCrop(crop);
        return string.Join(",",
            normalized.X.ToString("0.######", System.Globalization.CultureInfo.InvariantCulture),
            normalized.Y.ToString("0.######", System.Globalization.CultureInfo.InvariantCulture),
            normalized.Width.ToString("0.######", System.Globalization.CultureInfo.InvariantCulture),
            normalized.Height.ToString("0.######", System.Globalization.CultureInfo.InvariantCulture));
    }

    private static int ParseInt(string? value, int fallback)
        => int.TryParse(value, out var parsed) ? parsed : fallback;

    private static bool IsSupportedImage(string path)
    {
        var extension = Path.GetExtension(path);
        return extension.Equals(".png", StringComparison.OrdinalIgnoreCase)
            || extension.Equals(".jpg", StringComparison.OrdinalIgnoreCase)
            || extension.Equals(".jpeg", StringComparison.OrdinalIgnoreCase)
            || extension.Equals(".bmp", StringComparison.OrdinalIgnoreCase)
            || extension.Equals(".gif", StringComparison.OrdinalIgnoreCase)
            || extension.Equals(".tif", StringComparison.OrdinalIgnoreCase)
            || extension.Equals(".tiff", StringComparison.OrdinalIgnoreCase);
    }

    private static string SanitizeFileName(string value)
    {
        var invalid = Path.GetInvalidFileNameChars().ToHashSet();
        var clean = new string(value.Select(c => invalid.Contains(c) ? '_' : c).ToArray()).Trim();
        return string.IsNullOrWhiteSpace(clean) ? "image" : clean;
    }
}
