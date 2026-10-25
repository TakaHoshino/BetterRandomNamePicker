namespace BetterRNP.Core;

public sealed record PluginCacheCleanResult(int RemovedEntries, int FailedEntries)
{
    public bool Success => FailedEntries == 0;
}

public static class PluginCacheService
{
    public static PluginCacheCleanResult Clear(AppPaths paths)
    {
        ArgumentNullException.ThrowIfNull(paths);
        List<string> entries;
        try
        {
            Directory.CreateDirectory(paths.PluginCacheDir);
            entries = Directory.EnumerateFileSystemEntries(paths.PluginCacheDir).ToList();
        }
        catch
        {
            return new PluginCacheCleanResult(0, 1);
        }

        var removed = 0;
        var failed = 0;
        foreach (var entry in entries)
        {
            try
            {
                if (Directory.Exists(entry))
                {
                    Directory.Delete(entry, true);
                }
                else
                {
                    File.Delete(entry);
                }
                removed++;
            }
            catch
            {
                failed++;
            }
        }
        return new PluginCacheCleanResult(removed, failed);
    }
}
