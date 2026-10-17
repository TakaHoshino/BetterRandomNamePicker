using System.Windows;

namespace BetterRNP.Models;

public sealed class SeatConfig
{
    public SeatConfig(int rows, int cols, IEnumerable<Point> selectedSeats)
    {
        Rows = rows;
        Cols = cols;
        SelectedSeats = selectedSeats.ToList();
    }

    public int Rows { get; }
    public int Cols { get; }
    public IReadOnlyList<Point> SelectedSeats { get; }
}
