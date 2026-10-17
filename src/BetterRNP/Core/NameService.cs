using System.Text;

namespace BetterRNP.Core;

public sealed class NameService
{
    private readonly DataStore _data;
    private readonly LogService _log;

    public NameService(DataStore data, LogService log)
    {
        _data = data;
        _log = log;
    }

    public List<string> LoadNames(string schemeName)
    {
        try
        {
            return _data.LoadNames(schemeName);
        }
        catch (DataCorruptedException)
        {
            throw;
        }
        catch (Exception ex)
        {
            _log.Write($"加载方案[{schemeName}]名单失败: {ex.Message}", "LOAD_ERROR");
            return new List<string>();
        }
    }

    public void SaveNames(string schemeName, IEnumerable<string> names)
    {
        var list = names.Where(n => !string.IsNullOrWhiteSpace(n)).Select(n => n.Trim()).ToList();
        _data.SaveNames(schemeName, list);
        _log.Write($"方案[{schemeName}]名单已保存，共{list.Count}个", "SAVE_SUCCESS");
    }

    public void ImportNames(string schemeName, string path)
    {
        var imported = File.ReadAllLines(path, Encoding.UTF8)
            .Select(line => line.Trim())
            .Where(line => line.Length > 0)
            .ToList();
        var names = LoadNames(schemeName);
        names.AddRange(imported);
        SaveNames(schemeName, names);
        _log.Write($"{schemeName}-{Path.GetFileName(path)}，新增{imported.Count}条", "导入名单");
    }

    public void ExportNames(string schemeName, string path)
    {
        var names = LoadNames(schemeName);
        File.WriteAllLines(path, names, new UTF8Encoding(false));
        _log.Write($"{schemeName}-{path}", "导出名单");
    }

    public void DeleteCorruptedCopies(string schemeName)
    {
        _data.DeleteAllCopies(schemeName, DataKind.Names);
        _log.Write($"方案[{schemeName}]损坏名单副本已删除", "CORRUPTED_FILES_DELETED");
    }
}
