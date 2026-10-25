using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using BetterRNP.Core;

namespace BetterRNP.Views;

public sealed class PasswordWindow : Window
{
    private readonly AppServices _services;
    private readonly PasswordBox _passwordBox = new();

    public PasswordWindow(AppServices services, string titleKey, string promptKey)
    {
        _services = services;
        LocalizationService.SetTitle(this, titleKey);
        Width = 420;
        Height = 210;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Content = BuildContent(promptKey);
        _services.Theme.Attach(this, ThemeService.CategoryOther);
        Loaded += (_, _) => _passwordBox.Focus();
    }

    public bool Verified { get; private set; }

    private UIElement BuildContent(string promptKey)
    {
        var grid = new Grid { Margin = new Thickness(24) };
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        var label = new TextBlock
        {
            Foreground = (System.Windows.Media.Brush)FindResource("MutedBrush"),
            Margin = new Thickness(0, 0, 0, 6)
        };
        LocalizationService.SetText(label, promptKey);
        Grid.SetRow(label, 0);
        grid.Children.Add(label);

        Grid.SetRow(_passwordBox, 1);
        grid.Children.Add(_passwordBox);

        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right
        };
        var cancel = new Button
        {
            Style = (Style)FindResource("SecondaryButton"),
            Margin = new Thickness(6)
        };
        LocalizationService.SetContent(cancel, UiText.Cancel);
        cancel.Click += (_, _) => Close();
        var ok = new Button
        {
            Style = (Style)FindResource("PrimaryButton"),
            MinWidth = 92,
            Margin = new Thickness(6)
        };
        LocalizationService.SetContent(ok, UiText.Ok);
        ok.Click += (_, _) => Verify();
        buttons.Children.Add(cancel);
        buttons.Children.Add(ok);
        Grid.SetRow(buttons, 3);
        grid.Children.Add(buttons);

        _passwordBox.KeyDown += OnPasswordKeyDown;
        return grid;
    }

    private void OnPasswordKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            Verify();
            e.Handled = true;
        }
        else if (e.Key == Key.L && Keyboard.Modifiers.HasFlag(ModifierKeys.Control)
                 && _services.Passwords.RegisterCtrlL())
        {
            Verified = true;
            MessageBox.Show(this, UiText.BackupUnlocked, UiText.Notice,
                MessageBoxButton.OK, MessageBoxImage.Information);
            Close();
        }
    }

    private void Verify()
    {
        if (string.IsNullOrEmpty(_passwordBox.Password))
        {
            MessageBox.Show(this, UiText.PasswordCannotBeEmpty, UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }
        if (!_services.Passwords.VerifyAndUnlock(_passwordBox.Password))
        {
            MessageBox.Show(this, UiText.IncorrectPassword, UiText.Error,
                MessageBoxButton.OK, MessageBoxImage.Error);
            _passwordBox.Clear();
            _passwordBox.Focus();
            return;
        }
        Verified = true;
        Close();
    }
}
