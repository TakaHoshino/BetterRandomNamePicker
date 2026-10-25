using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using BetterRNP.Core;

namespace BetterRNP.Views;

public partial class CropImageWindow : Window
{
    private readonly BitmapImage _source;
    private Rect _displayedImageBounds;
    private Rect _normalizedCrop;
    private Point _dragStart;
    private bool _dragging;

    public CropImageWindow(string imagePath, Rect initialCrop)
    {
        InitializeComponent();
        _source = LoadImage(imagePath);
        PreviewImage.Source = _source;
        _normalizedCrop = NormalizeCrop(initialCrop);
        SelectedCrop = _normalizedCrop;
        Loaded += (_, _) => UpdateDisplayedImageBounds();
    }

    public Rect SelectedCrop { get; private set; }

    private static BitmapImage LoadImage(string path)
    {
        var bitmap = new BitmapImage();
        bitmap.BeginInit();
        bitmap.CacheOption = BitmapCacheOption.OnLoad;
        bitmap.UriSource = new Uri(path, UriKind.Absolute);
        bitmap.EndInit();
        bitmap.Freeze();
        return bitmap;
    }

    private void OverlayCanvas_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        UpdateDisplayedImageBounds();
    }

    private void UpdateDisplayedImageBounds()
    {
        if (OverlayCanvas.ActualWidth <= 0
            || OverlayCanvas.ActualHeight <= 0
            || _source.PixelWidth <= 0
            || _source.PixelHeight <= 0)
        {
            return;
        }

        var scale = Math.Min(
            OverlayCanvas.ActualWidth / _source.PixelWidth,
            OverlayCanvas.ActualHeight / _source.PixelHeight);
        var width = _source.PixelWidth * scale;
        var height = _source.PixelHeight * scale;
        _displayedImageBounds = new Rect(
            (OverlayCanvas.ActualWidth - width) / 2,
            (OverlayCanvas.ActualHeight - height) / 2,
            width,
            height);
        DrawSelection();
    }

    private void OverlayCanvas_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (_displayedImageBounds.IsEmpty)
        {
            return;
        }
        _dragStart = ClampToImage(e.GetPosition(OverlayCanvas));
        _dragging = true;
        OverlayCanvas.CaptureMouse();
        SetSelectionPixels(new Rect(_dragStart, _dragStart));
        e.Handled = true;
    }

    private void OverlayCanvas_MouseMove(object sender, MouseEventArgs e)
    {
        if (!_dragging)
        {
            return;
        }
        var current = ClampToImage(e.GetPosition(OverlayCanvas));
        SetSelectionPixels(new Rect(_dragStart, current));
    }

    private void OverlayCanvas_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (!_dragging)
        {
            return;
        }
        _dragging = false;
        OverlayCanvas.ReleaseMouseCapture();
        _normalizedCrop = ToNormalized(new Rect(
            Canvas.GetLeft(SelectionBox),
            Canvas.GetTop(SelectionBox),
            SelectionBox.Width,
            SelectionBox.Height));
        SelectedCrop = _normalizedCrop;
        DrawSelection();
    }

    private void SetSelectionPixels(Rect rect)
    {
        var left = Math.Min(rect.Left, rect.Right);
        var top = Math.Min(rect.Top, rect.Bottom);
        var width = Math.Abs(rect.Width);
        var height = Math.Abs(rect.Height);
        SelectionBox.Width = Math.Max(1, width);
        SelectionBox.Height = Math.Max(1, height);
        Canvas.SetLeft(SelectionBox, left);
        Canvas.SetTop(SelectionBox, top);
        SelectionBox.Visibility = Visibility.Visible;
        _normalizedCrop = ToNormalized(new Rect(left, top, width, height));
        UpdateSelectionText();
    }

    private void DrawSelection()
    {
        if (_displayedImageBounds.IsEmpty)
        {
            return;
        }
        var rect = new Rect(
            _displayedImageBounds.X + _normalizedCrop.X * _displayedImageBounds.Width,
            _displayedImageBounds.Y + _normalizedCrop.Y * _displayedImageBounds.Height,
            _normalizedCrop.Width * _displayedImageBounds.Width,
            _normalizedCrop.Height * _displayedImageBounds.Height);
        SelectionBox.Width = rect.Width;
        SelectionBox.Height = rect.Height;
        Canvas.SetLeft(SelectionBox, rect.X);
        Canvas.SetTop(SelectionBox, rect.Y);
        SelectionBox.Visibility = Visibility.Visible;
        UpdateSelectionText();
    }

    private Rect ToNormalized(Rect pixels)
    {
        if (_displayedImageBounds.Width <= 0 || _displayedImageBounds.Height <= 0)
        {
            return new Rect(0, 0, 1, 1);
        }
        return NormalizeCrop(new Rect(
            (pixels.X - _displayedImageBounds.X) / _displayedImageBounds.Width,
            (pixels.Y - _displayedImageBounds.Y) / _displayedImageBounds.Height,
            pixels.Width / _displayedImageBounds.Width,
            pixels.Height / _displayedImageBounds.Height));
    }

    private Point ClampToImage(Point point)
        => new(
            Math.Clamp(point.X, _displayedImageBounds.Left, _displayedImageBounds.Right),
            Math.Clamp(point.Y, _displayedImageBounds.Top, _displayedImageBounds.Bottom));

    private void UpdateSelectionText()
    {
        SelectionText.Text = LocalizationService.Format(
            UiText.SelectionFormat,
            _normalizedCrop.X,
            _normalizedCrop.Y,
            _normalizedCrop.Width,
            _normalizedCrop.Height);
    }

    private static Rect NormalizeCrop(Rect crop)
    {
        var x = Math.Clamp(crop.X, 0, 0.999);
        var y = Math.Clamp(crop.Y, 0, 0.999);
        var width = Math.Clamp(crop.Width, 0.001, 1 - x);
        var height = Math.Clamp(crop.Height, 0.001, 1 - y);
        return new Rect(x, y, width, height);
    }

    private void Reset_Click(object sender, RoutedEventArgs e)
    {
        _normalizedCrop = new Rect(0, 0, 1, 1);
        SelectedCrop = _normalizedCrop;
        DrawSelection();
    }

    private void Ok_Click(object sender, RoutedEventArgs e)
    {
        if (SelectionBox.Width < 4 || SelectionBox.Height < 4)
        {
            MessageBox.Show(this, UiText.InvalidCropSelection, UiText.Crop,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        SelectedCrop = _normalizedCrop;
        DialogResult = true;
    }
}
