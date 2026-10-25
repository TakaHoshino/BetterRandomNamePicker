using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using BetterRNP.Core;
using BetterRNP.Models;

namespace BetterRNP.Views;

public partial class SeatSettingsWindow : Window
{
    private const int MaxRows = 30;
    private const int MaxCols = 30;
    private readonly AppServices _services;
    private readonly string _schemeName;
    private readonly HashSet<Point> _selectedSeats = new();
    private HashSet<Point> _committedSeats = new();
    private string _committedRows = string.Empty;
    private string _committedCols = string.Empty;
    private RollingPicker? _picker;
    private bool _isPicking;
    private string? _lastResult;
    private bool _closingApproved;

    public SeatSettingsWindow(AppServices services, string schemeName)
    {
        _services = services;
        _schemeName = schemeName;
        InitializeComponent();
        _services.Theme.Attach(this, ThemeService.CategorySeat);
        LoadCurrentConfig();
        Closing += OnClosing;
    }

    private void LoadCurrentConfig()
    {
        var config = _services.Schemes.GetSeatConfig(_schemeName);
        RowsInput.Text = config?.Rows.ToString() ?? "5";
        ColsInput.Text = config?.Cols.ToString() ?? "5";
        _selectedSeats.Clear();
        if (config is not null)
        {
            foreach (var seat in config.SelectedSeats)
            {
                _selectedSeats.Add(seat);
            }
        }
        MarkCommitted();
        BuildSeatGrid();
    }

    private void MarkCommitted()
    {
        _committedRows = RowsInput.Text.Trim();
        _committedCols = ColsInput.Text.Trim();
        _committedSeats = new HashSet<Point>(_selectedSeats);
    }

    private bool TryParseDimensions(bool notify, out int rows, out int cols)
    {
        rows = cols = 0;
        if (!int.TryParse(RowsInput.Text.Trim(), out rows)
            || !int.TryParse(ColsInput.Text.Trim(), out cols))
        {
            if (notify)
            {
                MessageBox.Show(this, UiText.InvalidNumber, UiText.Error,
                    MessageBoxButton.OK, MessageBoxImage.Error);
            }
            return false;
        }
        if (rows <= 0 || cols <= 0)
        {
            if (notify)
            {
                MessageBox.Show(this, UiText.InvalidSeatDimensions, UiText.Error,
                    MessageBoxButton.OK, MessageBoxImage.Error);
            }
            return false;
        }
        if (rows > MaxRows || cols > MaxCols)
        {
            if (notify)
            {
                MessageBox.Show(
                    this,
                    LocalizationService.Format(UiText.SeatDimensionsTooLargeFormat, MaxRows, MaxCols),
                    UiText.Error,
                    MessageBoxButton.OK, MessageBoxImage.Error);
            }
            return false;
        }
        return true;
    }

    private List<Point> OutOfRangeSeats(int rows, int cols)
        => _selectedSeats.Where(p => p.X < 1 || p.X > rows || p.Y < 1 || p.Y > cols).ToList();

    private bool ResolveOutOfRange(ref int rows, ref int cols)
    {
        var invalid = OutOfRangeSeats(rows, cols);
        if (invalid.Count == 0)
        {
            return true;
        }

        var details = string.Join(" ", invalid.Take(10).Select(p => $"({(int)p.X},{(int)p.Y})"));
        if (invalid.Count > 10)
        {
            details += " …";
        }
        var choice = SeatConflictDialog.Choose(
            this,
            LocalizationService.Format(
                UiText.SeatOutOfRangeFormat,
                rows,
                cols,
                invalid.Count,
                details));
        switch (choice)
        {
            case SeatConflictChoice.Remove:
                foreach (var seat in invalid)
                {
                    _selectedSeats.Remove(seat);
                }
                return true;
            case SeatConflictChoice.Expand:
                rows = Math.Max(rows, invalid.Max(p => (int)p.X));
                cols = Math.Max(cols, invalid.Max(p => (int)p.Y));
                if (rows > MaxRows || cols > MaxCols)
                {
                    MessageBox.Show(
                        this,
                        LocalizationService.Format(
                            UiText.SeatLayoutExpansionTooLargeFormat,
                            MaxRows,
                            MaxCols),
                        UiText.Error,
                        MessageBoxButton.OK,
                        MessageBoxImage.Error);
                    return false;
                }
                RowsInput.Text = rows.ToString();
                ColsInput.Text = cols.ToString();
                return true;
            default:
                return false;
        }
    }

    private void BuildSeatGrid()
    {
        if (!TryParseDimensions(false, out var rows, out var cols))
        {
            return;
        }
        SeatGrid.Children.Clear();
        SeatGrid.Columns = cols;
        for (var row = 1; row <= rows; row++)
        {
            for (var col = 1; col <= cols; col++)
            {
                var point = new Point(row, col);
                var button = new Button
                {
                    Content = $"{row},{col}",
                    Tag = point,
                    Width = 52,
                    Height = 42,
                    Margin = new Thickness(2),
                    FontSize = 10,
                    Padding = new Thickness(0)
                };
                button.Click += Seat_Click;
                UpdateSeatButton(button, point);
                SeatGrid.Children.Add(button);
            }
        }
    }

    private void Seat_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: Point point } button)
        {
            return;
        }
        if (_selectedSeats.Contains(point))
        {
            _selectedSeats.Remove(point);
        }
        else
        {
            _selectedSeats.Add(point);
        }
        UpdateSeatButton(button, point);
    }

    private void UpdateSeatButton(Button button, Point point)
    {
        if (_selectedSeats.Contains(point))
        {
            button.Background = Brushes.Gold;
        }
        else
        {
            button.ClearValue(Control.BackgroundProperty);
        }
    }

    private void UpdateLayout_Click(object sender, RoutedEventArgs e)
    {
        if (!TryParseDimensions(true, out var rows, out var cols))
        {
            return;
        }
        if (!ResolveOutOfRange(ref rows, ref cols))
        {
            RowsInput.Text = _committedRows;
            ColsInput.Text = _committedCols;
            BuildSeatGrid();
            return;
        }
        BuildSeatGrid();
        MessageBox.Show(this, UiText.SeatLayoutUpdated, UiText.Notice,
            MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void Pick_Click(object sender, RoutedEventArgs e)
    {
        if (_isPicking)
        {
            StopPicking();
        }
        else
        {
            StartPicking();
        }
    }

    private void StartPicking()
    {
        if (_selectedSeats.Count == 0)
        {
            _services.Log.Write(LogLevel.Warn, $"方案={_schemeName}", "未选择座位，无法抽取");
            MessageBox.Show(this, UiText.SelectSeatsFirst, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        var seats = _selectedSeats.ToArray();
        _isPicking = true;
        LocalizationService.SetContent(PickButton, UiText.Stop);
        _lastResult = null;
        _picker = new RollingPicker(
            () =>
            {
                var seat = seats[Random.Shared.Next(seats.Length)];
                return $"({(int)seat.X}, {(int)seat.Y})";
            },
            value =>
            {
                _lastResult = value;
                ResultText.Text = LocalizationService.Format(UiText.ResultFormat, value);
            });
        _picker.Start();
        _services.Log.Write(
            $"方案={_schemeName}｜模式=座位模式｜候选座位={seats.Length}", "开始抽取");
    }

    private void StopPicking()
    {
        _isPicking = false;
        LocalizationService.SetContent(PickButton, UiText.Start);
        _picker?.Stop();
        _picker = null;
        if (_lastResult is not null)
        {
            _services.Log.Write(
                $"方案={_schemeName}｜模式=座位模式｜结果={_lastResult}", "抽取结果");
        }
    }

    private void Save_Click(object sender, RoutedEventArgs e) => Save(notify: true);

    private bool Save(bool notify)
    {
        if (!TryParseDimensions(true, out var rows, out var cols)
            || !ResolveOutOfRange(ref rows, ref cols))
        {
            return false;
        }

        _services.Schemes.SaveSeatConfig(
            _schemeName,
            new SeatConfig(rows, cols, _selectedSeats));
        MarkCommitted();
        BuildSeatGrid();
        if (notify)
        {
            MessageBox.Show(this, UiText.SaveSucceeded, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
        return true;
    }

    private void Apply_Click(object sender, RoutedEventArgs e)
    {
        if (!Save(notify: false))
        {
            return;
        }
        _services.Log.Write($"方案={_schemeName}", "应用座位设置到方案");
        MessageBox.Show(
            this,
            LocalizationService.Format(UiText.ApplyToSchemeFormat, _schemeName),
            UiText.Notice,
            MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private bool HasUnsavedChanges()
        => RowsInput.Text.Trim() != _committedRows
           || ColsInput.Text.Trim() != _committedCols
           || !_selectedSeats.SetEquals(_committedSeats);

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        if (_closingApproved || !HasUnsavedChanges())
        {
            StopPicking();
            return;
        }
        var result = MessageBox.Show(this,
            UiText.DiscardSeatChangesConfirmation, UiText.Notice,
            MessageBoxButton.YesNo, MessageBoxImage.Warning);
        if (result == MessageBoxResult.Yes)
        {
            _closingApproved = true;
            _services.Log.Write($"方案={_schemeName}", "放弃未保存的修改");
            StopPicking();
        }
        else
        {
            e.Cancel = true;
            _services.Log.Write($"方案={_schemeName}", "取消关闭座位设置");
        }
    }
}
