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

    private sealed record ModeChoice(BackdropMode Mode, string Name)
    {
        public override string ToString() => Name;
    }

    private sealed record CategoryChoice(string Id, string Name)
    {
        public override string ToString() => Name;
    }

    private static readonly ModeChoice[] ModeChoices =
    {
        new(BackdropMode.Fill, "填充（裁切填满）"),
        new(BackdropMode.Fit, "适应（完整显示）"),
        new(BackdropMode.Stretch, "拉伸"),
        new(BackdropMode.Center, "居中")
    };

    private readonly AppServices _services;
    private readonly ObservableCollection<ThemeImage> _images = new();
    private bool _syncing;

    public ThemeWindow(AppServices services)
    {
        _services = services;
        InitializeComponent();
        ImageList.ItemsSource = _images;
        GlobalModeCombo.ItemsSource = ModeChoices;
        CategoryModeCombo.ItemsSource = ModeChoices;
        CategoryList.ItemsSource = ThemeService.CategoryIds
            .Select(id => new CategoryChoice(id, ThemeService.CategoryLabels[id]))
            .ToList();
        EnabledCheck.IsChecked = _services.Theme.IsEnabled;
        RefreshImages();
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
                new(string.Empty, "（无）")
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
            new(string.Empty, "（继承全局）")
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
            _services.Theme.IsEnabled = EnabledCheck.IsChecked == true;
        }
    }

    private void Import_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "导入背景图片",
            Filter = "图片文件|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.tif;*.tiff|所有文件|*.*"
        };
        if (dialog.ShowDialog(this) != true)
        {
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
            MessageBox.Show(this, $"导入失败：{ex.Message}", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (ImageList.SelectedItem is not ThemeImage image)
        {
            return;
        }
        var result = MessageBox.Show(this,
            $"确定删除图片“{image.DisplayName}”吗？引用它的窗口将回退到全局或无色背景。",
            "确认删除", MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (result != MessageBoxResult.Yes)
        {
            return;
        }
        _services.Theme.DeleteImage(image.Id);
        RefreshImages();
    }

    private void Reset_Click(object sender, RoutedEventArgs e)
    {
        var result = MessageBox.Show(this,
            "恢复默认会关闭照片背景并清除图片指定，但不会删除图库中的图片。继续吗？",
            "恢复默认", MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (result != MessageBoxResult.Yes)
        {
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
            RefreshImages(image.Id);
        }
    }

    private void GlobalImage_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncing && GlobalImageCombo.SelectedItem is ImageChoice choice)
        {
            _services.Theme.SetGlobalImage(choice.Id);
        }
    }

    private void GlobalMode_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncing && GlobalModeCombo.SelectedItem is ModeChoice choice)
        {
            _services.Theme.SetGlobalMode(choice.Mode);
        }
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
        }
    }

    private void CategoryMode_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!_syncing && SelectedCategory is { } category
                      && CategoryModeCombo.SelectedItem is ModeChoice choice)
        {
            _services.Theme.SetCategoryMode(category.Id, choice.Mode);
        }
    }

    private void ClearCategory_Click(object sender, RoutedEventArgs e)
    {
        if (SelectedCategory is { } category)
        {
            _services.Theme.ClearCategory(category.Id);
            RefreshCategoryControls();
        }
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
