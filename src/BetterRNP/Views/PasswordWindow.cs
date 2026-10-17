using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using BetterRNP.Core;

namespace BetterRNP.Views;

public sealed class PasswordWindow : Window
{
    private readonly AppServices _services;
    private readonly PasswordBox _passwordBox = new();

    public PasswordWindow(AppServices services, string title, string prompt)
    {
        _services = services;
        Title = title;
        Width = 420;
        Height = 210;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Content = BuildContent(prompt);
        _services.Theme.Attach(this, ThemeService.CategoryOther);
        Loaded += (_, _) => _passwordBox.Focus();
    }

    public bool Verified { get; private set; }

    private UIElement BuildContent(string prompt)
    {
        var grid = new Grid { Margin = new Thickness(24) };
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        var label = new TextBlock
        {
            Text = prompt,
            Foreground = (System.Windows.Media.Brush)FindResource("MutedBrush"),
            Margin = new Thickness(0, 0, 0, 6)
        };
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
            Content = "取消",
            Style = (Style)FindResource("SecondaryButton"),
            Margin = new Thickness(6)
        };
        cancel.Click += (_, _) => Close();
        var ok = new Button
        {
            Content = "确定",
            Style = (Style)FindResource("PrimaryButton"),
            MinWidth = 92,
            Margin = new Thickness(6)
        };
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
            MessageBox.Show(this, "备用通道已激活！", "提示",
                MessageBoxButton.OK, MessageBoxImage.Information);
            Close();
        }
    }

    private void Verify()
    {
        if (string.IsNullOrEmpty(_passwordBox.Password))
        {
            MessageBox.Show(this, "密码不能为空！", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }
        if (!_services.Passwords.VerifyAndUnlock(_passwordBox.Password))
        {
            MessageBox.Show(this, "密码错误！", "错误",
                MessageBoxButton.OK, MessageBoxImage.Error);
            _passwordBox.Clear();
            _passwordBox.Focus();
            return;
        }
        Verified = true;
        Close();
    }
}
