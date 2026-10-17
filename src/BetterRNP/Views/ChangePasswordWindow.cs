using System.Windows;
using System.Windows.Controls;
using BetterRNP.Core;

namespace BetterRNP.Views;

public sealed class ChangePasswordWindow : Window
{
    private readonly AppServices _services;
    private readonly PasswordBox _first = new();
    private readonly PasswordBox _second = new();

    public ChangePasswordWindow(AppServices services)
    {
        _services = services;
        Title = "设置新密码";
        Width = 440;
        Height = 280;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Content = BuildContent();
        _services.Theme.Attach(this, ThemeService.CategoryOther);
    }

    private UIElement BuildContent()
    {
        var grid = new Grid { Margin = new Thickness(24) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        for (var i = 0; i < 5; i++)
        {
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        }

        AddRow(grid, 0, "新密码：", _first);
        AddRow(grid, 1, "确认新密码：", _second);

        var hint = new TextBlock
        {
            Text = "密码长度至少 4 位",
            Foreground = (System.Windows.Media.Brush)FindResource("MutedBrush"),
            Margin = new Thickness(0, 8, 0, 8)
        };
        Grid.SetRow(hint, 2);
        Grid.SetColumnSpan(hint, 2);
        grid.Children.Add(hint);

        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right
        };
        var cancel = new Button
        {
            Content = "取消",
            Style = (Style)FindResource("SecondaryButton")
        };
        cancel.Click += (_, _) => Close();
        var ok = new Button
        {
            Content = "确定",
            Style = (Style)FindResource("PrimaryButton"),
            MinWidth = 92
        };
        ok.Click += (_, _) => Save();
        buttons.Children.Add(cancel);
        buttons.Children.Add(ok);
        Grid.SetRow(buttons, 4);
        Grid.SetColumnSpan(buttons, 2);
        grid.Children.Add(buttons);
        return grid;
    }

    private static void AddRow(Grid grid, int row, string label, PasswordBox box)
    {
        var text = new TextBlock
        {
            Text = label,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 8, 12, 0)
        };
        Grid.SetRow(text, row);
        Grid.SetColumn(text, 0);
        grid.Children.Add(text);

        Grid.SetRow(box, row);
        Grid.SetColumn(box, 1);
        grid.Children.Add(box);
    }

    private void Save()
    {
        if (_first.Password.Length < 4)
        {
            MessageBox.Show(this, "新密码长度至少 4 位！", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }
        if (_first.Password != _second.Password)
        {
            MessageBox.Show(this, "两次输入的新密码不一致！", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
            _first.Clear();
            _second.Clear();
            return;
        }
        if (!_services.Passwords.ChangePassword(_first.Password))
        {
            MessageBox.Show(this, "密码修改失败！", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }
        MessageBox.Show(this, "密码修改成功！", "提示",
            MessageBoxButton.OK, MessageBoxImage.Information);
        Close();
    }
}
