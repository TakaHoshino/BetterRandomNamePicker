using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Controls;
using BetterRNP.Core;
using Microsoft.Win32;

namespace BetterRNP.Views;

public partial class ThemeWindow : Window
{
    private sealed record ImageChoice(string Id, string Name)
    {
        public override string ToString() => Name;
    }

    private sealed record ModeChoice(BackdropMode Mode, string ResourceKey)
    {
        public string Name => LocalizationService.Get(ResourceKey);
        public override string ToString() => Name;
    }

    private sealed record CategoryChoice(string Id, string ResourceKey)
    {
        public string Name => LocalizationService.Get(ResourceKey);
        public override string ToString() => Name;
    }

    private static readonly ModeChoice[] ModeChoices =
    {
        new(BackdropMode.Fill, UiText.FillBackdropMode),
        new(BackdropMode.Fit, UiText.FitBackdropMode),
        new(BackdropMode.Stretch, UiText.StretchBackdropMode),
        new(BackdropMode.Center, UiText.CenterBackdropMode),
        new(BackdropMode.Crop, UiText.CropBackdropMode)
    };

    private readonly AppServices _services;
    private readonly ObservableCollection<ThemeImage> _images = new();
    private readonly System.Windows.Threading.DispatcherTimer _maskLogTimer = new()
    {
        Interval = TimeSpan.FromMilliseconds(600)
    };
    private bool _syncing;

    public ThemeWindow(AppServices services)
    {
        _services = services;
        InitializeComponent();
        _maskLogTimer.Tick += (_, _) =>
        {
            _maskLogTimer.Stop();
            _services.Log.Write($"遮罩={_services.Theme.MaskAlpha}", "调整背景遮罩");
        };
        ImageList.ItemsSource = _images;
        RefreshChoiceSources();
        EnabledCheck.IsChecked = _services.Theme.IsEnabled;
        RefreshImages();
        LocalizationService.LanguageChanged += OnLanguageChanged;
        Closed += (_, _) => LocalizationService.LanguageChanged -= OnLanguageChanged;
    }

    private void OnLanguageChanged(object? sender, EventArgs e)
    {
        var categoryId = SelectedCategory?.Id;
        RefreshChoiceSources();
        if (categoryId is not null)
        {
            CategoryList.SelectedItem = CategoryList.Items
                .OfType<CategoryChoice>()
                .FirstOrDefault(choice => choice.Id == categoryId);
        }
        RefreshImages();
    }

    private void RefreshChoiceSources()
    {
        GlobalModeCombo.ItemsSource = null;
        CategoryModeCombo.ItemsSource = null;
        GlobalModeCombo.ItemsSource = ModeChoices;
        CategoryModeCombo.ItemsSource = ModeChoices;
        CategoryList.ItemsSource = ThemeService.CategoryIds
            .Select(id => new CategoryChoice(id, ThemeService.CategoryLabelKeys[id]))
            .ToList();
    }

    private void RefreshImages(string? selectId = null)
    {
        _syncing = true;
        try
        {
            selectId ??= (ImageList.SelectedItem as ThemeImage)?.Id
                         ?? _services.Theme.GlobalImageId;
            _images.Clear();
            foreach (var themeImage in _services.Theme.ListImages())
            {
                _images.Add(themeImage);
            }

            var globalChoices = new List<ImageChoice>
            {
                new(string.Empty, LocalizationService.Get(UiText.NoneOption))
            };
            globalChoices.AddRange(_images.Select(i => new ImageChoice(i.Id, i.DisplayName)));
            GlobalImageCombo.ItemsSource = globalChoices;
            var globalId = _services.Theme.GlobalImageId;
            GlobalImageCombo.SelectedItem = globalChoices.FirstOrDefault(c => c.Id == globalId)
                                            ?? globalChoices[0];

            GlobalModeCombo.SelectedItem = ModeChoices.First(m => m.Mode == _services.Theme.GlobalMode);
            MaskSlider.Value = _services.Theme.MaskAlpha;
            MaskValue.Text = _services.Theme.MaskAlpha.ToString();

            var image = _images.FirstOrDefault(i => i.Id == selectId);
            ImageList.SelectedItem = image ?? _images.FirstOrDefault();
            if (CategoryList.SelectedIndex < 0)
            {
                CategoryList.SelectedIndex = 0;
            }
            RefreshCategoryControls();
        }
        finally
        {
            _syncing = false;
        }
    }

    private CategoryChoice? SelectedCategory => CategoryList.SelectedItem as CategoryChoice;

    private void RefreshCategoryControls()
    {
        if (SelectedCategory is not { } category)
        {
            return;
        }

        var choices = new List<ImageChoice>
        {
            new(string.Empty, LocalizationService.Get(UiText.InheritGlobalOption))
        };
        choices.AddRange(_images.Select(i => new ImageChoice(i.Id, i.DisplayName)));
        CategoryImageCombo.ItemsSource = choices;
        var categoryImageId = _services.Theme.GetCategoryImageId(category.Id) ?? string.Empty;
        CategoryImageCombo.SelectedItem = choices.FirstOrDefault(c => c.Id == categoryImageId)
                                           ?? choices[0];
        CategoryModeCombo.SelectedItem =
            ModeChoices.First(m => m.Mode == _services.Theme.GetCategoryMode(category.Id));
    }

    private void Enabled_Click(object sender, RoutedEventArgs e)
    {
        if (!_syncing)
        {
            var enabled = EnabledCheck.IsChecked == true;
            _services.Theme.IsEnabled = enabled;
            _services.Log.Write($"照片背景：{(enabled ? "启用" : "停用")}", "设置照片背景");
        }
    }

    private void Import_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = LocalizationService.Get(UiText.ImportBackdropTitle),
            Filter = LocalizationService.Get(UiText.ImageFileFilter)
        };
        if (dialog.ShowDialog(this) != true)
        {
            _services.Log.Write("用户取消导入背景图片", "导入主题图片");
            return;
        }
        try
        {
            var id = _services.Theme.ImportImage(dialog.FileName);
            if (string.IsNullOrWhiteSpace(_services.Theme.GlobalImageId))
            {
                _services.Theme.SetGlobalImage(id);
            }
            RefreshImages(id);
        }
        catch (Exception ex)
        {
            _services.Log.WriteException("IMPORT_THEME_IMAGE_ERROR", ex);
            MessageBox.Show(
                this,
                LocalizationService.Format(UiText.ImportFailureFormat, ex.Message),
                UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (ImageList.SelectedItem is not ThemeImage image)
        {
            return;
        }
        var result = MessageBox.Show(
            this,
            LocalizationService.Format(UiText.DeleteImageConfirmationFormat, image.DisplayName),
            UiText.ConfirmDeletion,
            MessageBoxButton.YesNo,
            MessageBoxImage.Question);
        if (result != MessageBoxResult.Yes)
        {
            _services.Log.Write($"图片={image.DisplayName}", "取消删除主题图片");
            return;
        }
        _services.Theme.DeleteImage(image.Id);
        RefreshImages();
    }

    private void Reset_Click(object sender, RoutedEventArgs e)
    {
        var result = MessageBox.Show(
            this,
            UiText.RestoreThemeConfirmation,
            UiText.RestoreDefaults,
            MessageBoxButton.YesNo,
            MessageBoxImage.Question);
        if (result != MessageBoxResult.Yes)
        {
            _services.Log.Write("用户取消恢复默认主题", "恢复默认主题");
            return;
        }
        _services.Theme.Reset();
        EnabledCheck.IsChecked = false;
        RefreshImages();
    }

    private void ImageList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
    }

    private void ImageList_MouseDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (ImageList.SelectedItem is ThemeImage image)
        {
            _services.Theme.SetGlobalImage(image.Id);
            _services.Log.Write($"图片={image.DisplayName}", "设为全局背景");
            RefreshImages(image.Id);
        }
    }

    private void GlobalImage_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncing && GlobalImageCombo.SelectedItem is ImageChoice choice)
        {
            _services.Theme.SetGlobalImage(choice.Id);
            _services.Log.Write($"图片={choice.Name}", "选择全局背景");
        }
    }

    private void GlobalMode_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncing && GlobalModeCombo.SelectedItem is ModeChoice choice)
        {
            _services.Theme.SetGlobalMode(choice.Mode);
            _services.Log.Write($"模式={choice.Name}", "设置全局背景填充方式");
        }
    }

    private void GlobalCrop_Click(object sender, RoutedEventArgs e)
    {
        var image = _images.FirstOrDefault(item => item.Id == _services.Theme.GlobalImageId);
        if (image is null)
        {
            MessageBox.Show(this, UiText.SelectGlobalImageFirst, UiText.Crop,
                MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        _services.Log.Write("窗口=裁切图片｜类别=全局背景", "打开窗口");
        var dialog = new CropImageWindow(image.Path, _services.Theme.GlobalCrop)
        {
            Owner = this
        };
        if (dialog.ShowDialog() != true)
        {
            _services.Log.Write("用户取消全局背景裁切", "裁切背景");
            return;
        }
        _services.Theme.SetGlobalCrop(dialog.SelectedCrop);
        _services.Theme.SetGlobalMode(BackdropMode.Crop);
        _services.Log.Write(
            $"裁切区域={dialog.SelectedCrop.X:0.###},{dialog.SelectedCrop.Y:0.###},"
            + $"{dialog.SelectedCrop.Width:0.###},{dialog.SelectedCrop.Height:0.###}",
            "裁切全局背景");
        RefreshImages((ImageList.SelectedItem as ThemeImage)?.Id);
    }

    private void MaskSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_syncing)
        {
            return;
        }
        var value = (int)e.NewValue;
        MaskValue.Text = value.ToString();
        _services.Theme.MaskAlpha = value;
        _maskLogTimer.Stop();
        _maskLogTimer.Start();
    }

    private void CategoryList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncing)
        {
            RefreshCategoryControls();
        }
    }

    private void CategoryImage_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncing && SelectedCategory is { } category
                      && CategoryImageCombo.SelectedItem is ImageChoice choice)
        {
            _services.Theme.SetCategoryImage(category.Id,
                string.IsNullOrWhiteSpace(choice.Id) ? null : choice.Id);
            _services.Log.Write(
                $"类别={category.Name}｜图片={choice.Name}", "设置窗口背景");
        }
    }

    private void CategoryMode_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncing && SelectedCategory is { } category
                      && CategoryModeCombo.SelectedItem is ModeChoice choice)
        {
            _services.Theme.SetCategoryMode(category.Id, choice.Mode);
            _services.Log.Write(
                $"类别={category.Name}｜方式={choice.Name}", "设置窗口背景填充方式");
        }
    }

    private void CategoryCrop_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedCategory is not { } category)
        {
            return;
        }
        var imageId = _services.Theme.GetCategoryImageId(category.Id)
                      ?? _services.Theme.GlobalImageId;
        var image = _images.FirstOrDefault(item => item.Id == imageId);
        if (image is null)
        {
            MessageBox.Show(this, UiText.SelectCategoryImageFirst, UiText.Crop,
                MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        _services.Log.Write($"窗口=裁切图片｜类别={category.Name}", "打开窗口");
        var dialog = new CropImageWindow(
            image.Path,
            _services.Theme.GetCategoryCrop(category.Id))
        {
            Owner = this
        };
        if (dialog.ShowDialog() != true)
        {
            _services.Log.Write($"类别={category.Name}", "取消窗口背景裁切");
            return;
        }
        _services.Theme.SetCategoryCrop(category.Id, dialog.SelectedCrop);
        _services.Theme.SetCategoryMode(category.Id, BackdropMode.Crop);
        _services.Log.Write(
            $"类别={category.Name}｜裁切区域={dialog.SelectedCrop.X:0.###},{dialog.SelectedCrop.Y:0.###},"
            + $"{dialog.SelectedCrop.Width:0.###},{dialog.SelectedCrop.Height:0.###}",
            "裁切窗口背景");
        RefreshImages((ImageList.SelectedItem as ThemeImage)?.Id);
    }

    private void ClearCategory_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedCategory is { } category)
        {
            _services.Theme.ClearCategory(category.Id);
            _services.Log.Write($"类别={category.Name}", "清除窗口背景设置");
            RefreshCategoryControls();
        }
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
