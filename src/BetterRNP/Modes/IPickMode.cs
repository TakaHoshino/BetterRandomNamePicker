using System.Windows;

namespace BetterRNP.Modes;

public interface IPickMode
{
    string Id { get; }
    string DisplayName { get; }
    string? Button1Text { get; }
    string? Button2Text { get; }
    string? CanPick();
    Func<string> CreateCandidateSupplier();
    void HandleButton1(Window owner);
    void HandleButton2(Window owner);
}
