using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;

namespace BetterRNP.Views;

public enum SeatConflictChoice
{
    Remove,
    Cancel,
    Expand
}

public sealed class SeatConflictDialog : Window
{
    private SeatConflictDialog(string message)
    {
        Title = "座位越界确认";
        Width = 560;
        Height = 250;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Content = BuildContent(message);
    }

    public SeatConflictChoice Choice { get; private set; } = SeatConflictChoice.Cancel;

    public static SeatConflictChoice Choose(Window owner, string message)
    {
        var dialog = new SeatConflictDialog(message) { Owner = owner };
        dialog.ShowDialog();
        return dialog.Choice;
    }

    private UIElement BuildContent(string message)
    {
        var grid = new Grid { Margin = new Thickness(22) };
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        var text = new TextBlock
        {
            Text = message,
            TextWrapping = TextWrapping.Wrap,
            VerticalAlignment = VerticalAlignment.Center,
            FontSize = 14
        };
        grid.Children.Add(text);

        var buttons = new UniformGrid { Columns = 3 };
        buttons.Children.Add(CreateButton("移除越界座位", SeatConflictChoice.Remove));
        buttons.Children.Add(CreateButton("取消并恢复原行列", SeatConflictChoice.Cancel));
        buttons.Children.Add(CreateButton("自动扩展行列", SeatConflictChoice.Expand));
        Grid.SetRow(buttons, 1);
        grid.Children.Add(buttons);
        return grid;
    }

    private Button CreateButton(string text, SeatConflictChoice choice)
    {
        var button = new Button
        {
            Content = text,
            Style = (Style)FindResource("SecondaryButton"),
            Margin = new Thickness(5)
        };
        button.Click += (_, _) =>
        {
            Choice = choice;
            Close();
        };
        return button;
    }
}
