using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using BetterRNP.Core;

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
        LocalizationService.SetTitle(this, UiText.SeatConflictTitle);
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
        buttons.Children.Add(CreateButton(UiText.RemoveOutOfRangeSeats, SeatConflictChoice.Remove));
        buttons.Children.Add(CreateButton(UiText.CancelAndRestoreDimensions, SeatConflictChoice.Cancel));
        buttons.Children.Add(CreateButton(UiText.ExpandDimensions, SeatConflictChoice.Expand));
        Grid.SetRow(buttons, 1);
        grid.Children.Add(buttons);
        return grid;
    }

    private Button CreateButton(string resourceKey, SeatConflictChoice choice)
    {
        var button = new Button
        {
            Style = (Style)FindResource("SecondaryButton"),
            Margin = new Thickness(5)
        };
        LocalizationService.SetContent(button, resourceKey);
        button.Click += (_, _) =>
        {
            Choice = choice;
            Close();
        };
        return button;
    }
}
