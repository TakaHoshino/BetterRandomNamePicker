namespace BetterRNP.Models;

public sealed class Scheme
{
    public const string NameListType = "name_list";
    public const string NumberType = "number";
    public const string SeatType = "seat";
    public const string BuiltInDefaultName = "默认方案";

    public Scheme(string name, string type, bool isBuiltIn = false)
    {
        Name = name;
        Type = type;
        IsBuiltIn = isBuiltIn;
    }

    public string Name { get; }
    public string Type { get; }
    public bool IsBuiltIn { get; }

    public string DisplayName => $"{Name}（{(IsBuiltIn ? "内置·" : string.Empty)}{Type}）";

    public override string ToString() => Name;
}
