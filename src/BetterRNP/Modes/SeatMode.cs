using System.Windows;
using BetterRNP.Core;
using BetterRNP.Models;
using BetterRNP.Views;

namespace BetterRNP.Modes;

public sealed class SeatMode : IPickMode
{
    private readonly AppServices _services;
    private readonly Func<Scheme?> _currentScheme;
    private SeatConfig? _snapshot;

    public SeatMode(AppServices services, Func<Scheme?> currentScheme)
    {
        _services = services;
        _currentScheme = currentScheme;
    }

    public string Id => "seat";
    public string DisplayName => "座位模式";
    public string Button1Text => "设置座位布局";
    public string Button2Text => "查看当前设置";

    public string? CanPick()
    {
        var scheme = _currentScheme();
        var config = scheme is null ? null : _services.Schemes.GetSeatConfig(scheme.Name);
        _snapshot = config;
        return config is null || config.SelectedSeats.Count == 0
            ? "请先设置座位并选择座位！"
            : null;
    }

    public Func<string> CreateCandidateSupplier()
    {
        var seats = _snapshot?.SelectedSeats.ToArray() ?? Array.Empty<Point>();
        return () =>
        {
            if (seats.Length == 0)
            {
                return string.Empty;
            }
            var seat = seats[Random.Shared.Next(seats.Length)];
            return $"({(int)seat.X}, {(int)seat.Y})";
        };
    }

    public void HandleButton1(Window owner)
    {
        var scheme = _currentScheme();
        if (scheme is null)
        {
            MessageBox.Show(owner, "请先选择一个方案！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        new SeatSettingsWindow(_services, scheme.Name) { Owner = owner }.ShowDialog();
    }

    public void HandleButton2(Window owner)
    {
        var scheme = _currentScheme();
        if (scheme is null)
        {
            MessageBox.Show(owner, "请先选择一个方案！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var config = _services.Schemes.GetSeatConfig(scheme.Name);
        if (config is null)
        {
            MessageBox.Show(owner, "当前方案尚未设置座位！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        _services.Log.Write(
            $"{scheme.Name}-行数={config.Rows},列数={config.Cols},已选座位数={config.SelectedSeats.Count}",
            "查看座位设置");
        MessageBox.Show(owner,
            $"当前座位设置：行数={config.Rows}，列数={config.Cols}，已选座位数={config.SelectedSeats.Count}\n"
            + "（修改请在“设置座位布局”中保存）",
            "提示", MessageBoxButton.OK, MessageBoxImage.Information);
    }
}
