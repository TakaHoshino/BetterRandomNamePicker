using BetterRNP.Core;

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

    public string DisplayName
    {
        get
        {
            var typeName = Type switch
            {
                NumberType => LocalizationService.Get(UiText.Numbers),
                SeatType => LocalizationService.Get(UiText.Seats),
                _ => LocalizationService.Get(UiText.NameList)
            };
            if (IsBuiltIn)
            {
                typeName = LocalizationService.Format(UiText.BuiltInSchemeTypeFormat, typeName);
            }
            var displayName = IsBuiltIn
                ? LocalizationService.Get(UiText.DefaultSchemeName)
                : Name;
            return LocalizationService.Format(UiText.SchemeDisplayFormat, displayName, typeName);
        }
    }

    public override string ToString() => Name;
}
