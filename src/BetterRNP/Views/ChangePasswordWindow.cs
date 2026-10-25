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
        LocalizationService.SetTitle(this, UiText.SetNewPassword);
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

        AddRow(grid, 0, UiText.NewPasswordLabel, _first);
        AddRow(grid, 1, UiText.ConfirmNewPasswordLabel, _second);

        var hint = new TextBlock
        {
            Foreground = (System.Windows.Media.Brush)FindResource("MutedBrush"),
            Margin = new Thickness(0, 8, 0, 8)
        };
        LocalizationService.SetText(hint, UiText.PasswordLengthRequirement);
        Grid.SetRow(hint, 2);
        Grid.SetColumnSpan(hint, 2);
        grid.Children.Add(hint);

        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right
        };
        var cancel = new Button { Style = (Style)FindResource("SecondaryButton") };
        LocalizationService.SetContent(cancel, UiText.Cancel);
        cancel.Click += (_, _) => Close();
        var ok = new Button
        {
            Style = (Style)FindResource("PrimaryButton"),
            MinWidth = 92
        };
        LocalizationService.SetContent(ok, UiText.Ok);
        ok.Click += (_, _) => Save();
        buttons.Children.Add(cancel);
        buttons.Children.Add(ok);
        Grid.SetRow(buttons, 4);
        Grid.SetColumnSpan(buttons, 2);
        grid.Children.Add(buttons);
        return grid;
    }

    private static void AddRow(Grid grid, int row, string resourceKey, PasswordBox box)
    {
        var text = new TextBlock
        {
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 8, 12, 0)
        };
        LocalizationService.SetText(text, resourceKey);
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
            MessageBox.Show(
                this,
                UiText.TheNewPasswordMustContainAtLeast4Characters,
                UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }
        if (_first.Password != _second.Password)
        {
            MessageBox.Show(
                this,
                UiText.TheNewPasswordsDoNotMatch,
                UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
            _first.Clear();
            _second.Clear();
            return;
        }
        if (!_services.Passwords.ChangePassword(_first.Password))
        {
            MessageBox.Show(this, UiText.PasswordChangeFailed, UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }
        MessageBox.Show(this, UiText.PasswordChangeSucceeded, UiText.Notice,
            MessageBoxButton.OK, MessageBoxImage.Information);
        Close();
    }
}
