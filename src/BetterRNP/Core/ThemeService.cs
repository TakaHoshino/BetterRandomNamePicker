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
    Center
}

public sealed record ThemeImage(string Id, string Path, string DisplayName);

public sealed record ThemeStyle(string ImagePath, BackdropMode Mode, int MaskAlpha);

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

    public static readonly IReadOnlyDictionary<string, string> CategoryLabels =
        new Dictionary<string, string>
        {
            [CategoryMain] = "主窗口",
            [CategoryConfig] = "配置名单",
            [CategoryScheme] = "方案管理",
            [CategoryNumber] = "数字设置",
            [CategorySeat] = "座位设置",
            [CategorySettings] = "系统设置",
            [CategoryOther] = "其它对话框"
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
    private readonly LogService _log;
    private readonly string _backupPath;
    private readonly Dictionary<Window, Attachment> _attachments = new();
    private JavaProperties _properties;

    public ThemeService(AppPaths paths, LogService log)
    {
        _paths = paths;
        _log = log;
        _backupPath = Path.Combine(paths.LogDir, "theme.properties.bak");
        _properties = Load();
    }

    public bool IsEnabled
    {
        get => bool.TryParse(_properties.Get("enabled"), out var enabled) && enabled;
        set
        {
            _properties.Set("enabled", value.ToString().ToLowerInvariant());
            SaveAndApply();
        }
    }

    public int MaskAlpha
    {
        get => Math.Clamp(ParseInt(_properties.Get("mask"), 40), 0, 180);
        set
        {
            _properties.Set("mask", Math.Clamp(value, 0, 180).ToString());
            SaveAndApply();
        }
    }

    public string GlobalImageId => _properties.Get("g.image", string.Empty) ?? string.Empty;

    public BackdropMode GlobalMode => ParseMode(_properties.Get("g.mode"));

    public IReadOnlyList<ThemeImage> ListImages()
    {
        Directory.CreateDirectory(_paths.ThemeImagesDir);
        return Directory.EnumerateFiles(_paths.ThemeImagesDir)
            .Where(IsSupportedImage)
            .OrderBy(path => path, StringComparer.CurrentCultureIgnoreCase)
            .Select(path =>
            {
                var id = Path.GetFileName(path);
                var alias = _properties.Get("name." + id, string.Empty);
                return new ThemeImage(id, path,
                    string.IsNullOrWhiteSpace(alias) ? Path.GetFileNameWithoutExtension(path) : alias);
            })
            .ToList();
    }

    public string ImportImage(string sourcePath)
    {
        if (!IsSupportedImage(sourcePath))
        {
            throw new InvalidOperationException("请选择 PNG、JPG、BMP、GIF 或 TIFF 图片。");
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
            _properties.Set("g.image", null);
        }
        foreach (var category in CategoryIds)
        {
            if (GetCategoryImageId(category) == imageId)
            {
                _properties.Set(CategoryKey(category, "image"), null);
            }
        }
        _properties.Set("name." + imageId, null);
        SaveAndApply();
        _log.Write($"主题图片删除: {imageId}", "THEME_DELETE");
        return true;
    }

    public void SetGlobalImage(string? imageId)
    {
        _properties.Set("g.image", string.IsNullOrWhiteSpace(imageId) ? null : imageId);
        SaveAndApply();
    }

    public void SetGlobalMode(BackdropMode mode)
    {
        _properties.Set("g.mode", mode.ToString());
        SaveAndApply();
    }

    public string? GetCategoryImageId(string category)
        => _properties.Get(CategoryKey(category, "image"));

    public BackdropMode GetCategoryMode(string category)
        => ParseMode(_properties.Get(CategoryKey(category, "mode")) ?? string.Empty) is var mode
           && _properties.Contains(CategoryKey(category, "mode")) ? mode : GlobalMode;

    public void SetCategoryImage(string category, string? imageId)
    {
        _properties.Set(CategoryKey(category, "image"),
            string.IsNullOrWhiteSpace(imageId) ? null : imageId);
        SaveAndApply();
    }

    public void SetCategoryMode(string category, BackdropMode mode)
    {
        _properties.Set(CategoryKey(category, "mode"), mode.ToString());
        SaveAndApply();
    }

    public void ClearCategory(string category)
    {
        _properties.Set(CategoryKey(category, "image"), null);
        _properties.Set(CategoryKey(category, "mode"), null);
        SaveAndApply();
    }

    public void Reset()
    {
        _properties = new JavaProperties();
        _properties.Set("enabled", "false");
        _properties.Set("mask", "40");
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
        return new ThemeStyle(path, GetCategoryMode(category), MaskAlpha);
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
            attachment.Image.Source = bitmap;
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
        Save();
        ApplyAll();
    }

    private JavaProperties Load()
    {
        if (!File.Exists(_paths.ThemeConfigFile) && File.Exists(_backupPath))
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_paths.ThemeConfigFile)!);
            File.Copy(_backupPath, _paths.ThemeConfigFile, true);
            _log.Write("主题配置缺失，已从备份恢复", "CONFIG_RESTORE");
        }
        return JavaProperties.Load(_paths.ThemeConfigFile);
    }

    private void Save()
    {
        try
        {
            if (File.Exists(_paths.ThemeConfigFile))
            {
                File.Copy(_paths.ThemeConfigFile, _backupPath, true);
            }

            var categoryKeys = CategoryIds
                .SelectMany(category => new[]
                {
                    CategoryKey(category, "image"),
                    CategoryKey(category, "mode")
                });
            _properties.WriteWithSections(
                _paths.ThemeConfigFile,
                new[]
                {
                    "BetterRNP 主题设置（UTF-8，可手工编辑）",
                    $"生成时间：{DateTime.Now:yyyy-MM-dd HH:mm:ss}",
                    "图片位于 data/themes/images。"
                },
                new (string, IEnumerable<string>)[]
                {
                    ("总开关与全局背景", new[] { "enabled", "mask", "g.image", "g.mode" }),
                    ("按窗口类别指定", categoryKeys)
                });
        }
        catch (Exception ex)
        {
            _log.Write($"保存主题设置失败: {ex.Message}", "THEME_SAVE_ERROR");
        }
    }

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
