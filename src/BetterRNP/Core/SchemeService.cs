using System.Text;
using BetterRNP.Models;

namespace BetterRNP.Core;

public sealed class SchemeService
{
    private readonly AppPaths _paths;
    private readonly DataStore _data;
    private readonly LogService _log;

    public SchemeService(AppPaths paths, DataStore data, LogService log)
    {
        _paths = paths;
        _data = data;
        _log = log;
    }

    public List<Scheme> GetAllSchemes()
    {
        var schemes = new List<Scheme>();
        if (!File.Exists(_paths.SchemesIndexFile))
        {
            return schemes;
        }

        try
        {
            foreach (var raw in File.ReadAllLines(_paths.SchemesIndexFile, Encoding.UTF8))
            {
                var line = raw.Trim();
                if (line.Length == 0)
                {
                    continue;
                }
                var split = line.IndexOf(',');
                if (split <= 0)
                {
                    continue;
                }
                var name = line[..split].Trim();
                var type = line[(split + 1)..].Trim();
                if (name.Length > 0)
                {
                    schemes.Add(new Scheme(name, type));
                }
            }
        }
        catch (Exception ex)
        {
            _log.Write(LogLevel.Error, $"读取方案索引失败: {ex.Message}", "SCHEME_INDEX_ERROR");
        }
        return schemes;
    }

    public bool AddScheme(string name, string type)
    {
        var schemes = GetAllSchemes();
        if (schemes.Any(s => string.Equals(s.Name, name, StringComparison.Ordinal)))
        {
            return false;
        }
        schemes.Add(new Scheme(name, type));
        SaveIndex(schemes);
        _log.Write($"方案={name}｜类型={type}", "创建方案");
        return true;
    }

    public bool RemoveScheme(string name)
    {
        var schemes = GetAllSchemes();
        var removed = schemes.RemoveAll(s => string.Equals(s.Name, name, StringComparison.Ordinal));
        if (removed == 0)
        {
            return false;
        }

        SaveIndex(schemes);
        _data.DeleteAllCopies(name, DataKind.Names);
        _data.DeleteAllCopies(name, DataKind.Number);
        _data.DeleteAllCopies(name, DataKind.Seat);
        _log.Write($"方案={name}", "删除方案");
        return true;
    }

    public NumberRange? GetNumberRange(string name)
    {
        try
        {
            var content = _data.LoadNumberRange(name);
            if (string.IsNullOrWhiteSpace(content))
            {
                return null;
            }
            var parts = content.Split(',');
            return parts.Length == 2
                && int.TryParse(parts[0].Trim(), out var min)
                && int.TryParse(parts[1].Trim(), out var max)
                    ? new NumberRange(min, max)
                    : null;
        }
        catch (Exception ex)
        {
            _log.Write(LogLevel.Error, $"加载方案[{name}]数字范围失败: {ex.Message}", "LOAD_NUMBER_ERROR");
            return null;
        }
    }

    public void SaveNumberRange(string name, NumberRange range)
    {
        _data.SaveNumberRange(name, $"{range.Min},{range.Max}");
        _log.Write($"方案={name}｜范围=[{range.Min},{range.Max}]", "SAVE_NUMBER_SUCCESS");
    }

    public SeatConfig? GetSeatConfig(string name)
    {
        try
        {
            var content = _data.LoadSeatConfig(name);
            if (string.IsNullOrWhiteSpace(content))
            {
                return null;
            }

            var lines = content.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n');
            var dims = lines[0].Trim().Split(',');
            if (dims.Length != 2
                || !int.TryParse(dims[0].Trim(), out var rows)
                || !int.TryParse(dims[1].Trim(), out var cols))
            {
                return null;
            }

            var seats = new List<System.Windows.Point>();
            var dropped = 0;
            foreach (var line in lines.Skip(1))
            {
                var coords = line.Trim().Split(',');
                if (coords.Length != 2
                    || !int.TryParse(coords[0].Trim(), out var row)
                    || !int.TryParse(coords[1].Trim(), out var col))
                {
                    continue;
                }
                if (row < 1 || row > rows || col < 1 || col > cols)
                {
                    dropped++;
                    continue;
                }
                seats.Add(new System.Windows.Point(row, col));
            }
            if (dropped > 0)
            {
                _log.Write(LogLevel.Warn, $"方案[{name}]座位配置含{dropped}个越界座位已忽略（{rows}×{cols}）", "SEAT_OUT_OF_RANGE");
            }
            return new SeatConfig(rows, cols, seats);
        }
        catch (Exception ex)
        {
            _log.Write(LogLevel.Error, $"加载方案[{name}]座位配置失败: {ex.Message}", "LOAD_SEAT_ERROR");
            return null;
        }
    }

    public void SaveSeatConfig(string name, SeatConfig config)
    {
        var content = new StringBuilder();
        content.Append(config.Rows).Append(',').Append(config.Cols).Append('\n');
        foreach (var seat in config.SelectedSeats)
        {
            content.Append((int)seat.X).Append(',').Append((int)seat.Y).Append('\n');
        }
        _data.SaveSeatConfig(name, content.ToString());
        _log.Write(
            $"方案={name}｜行数={config.Rows}｜列数={config.Cols}｜已选座位数={config.SelectedSeats.Count}",
            "SAVE_SEAT_SUCCESS");
    }

    private void SaveIndex(IEnumerable<Scheme> schemes)
    {
        Directory.CreateDirectory(_paths.SchemesDir);
        File.WriteAllLines(
            _paths.SchemesIndexFile,
            schemes.Select(s => $"{s.Name},{s.Type}"),
            new UTF8Encoding(false));
    }
}
