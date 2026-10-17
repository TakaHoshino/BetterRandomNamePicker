using System.Globalization;
using System.Text;

namespace BetterRNP.Core;

/// <summary>
/// Small Java-properties compatible reader/writer. It intentionally preserves unknown keys,
/// escaped characters, and UTF-8 Chinese text.
/// </summary>
public sealed class JavaProperties
{
    private readonly Dictionary<string, string> _values = new(StringComparer.Ordinal);

    public IReadOnlyDictionary<string, string> Values => _values;

    public string? Get(string key, string? fallback = null)
        => _values.TryGetValue(key, out var value) ? value : fallback;

    public void Set(string key, string? value)
    {
        if (value is null)
        {
            _values.Remove(key);
        }
        else
        {
            _values[key] = value;
        }
    }

    public bool Contains(string key) => _values.ContainsKey(key);

    public void Remove(string key) => _values.Remove(key);

    public static JavaProperties Load(string path)
    {
        var result = new JavaProperties();
        if (!File.Exists(path))
        {
            return result;
        }

        var text = File.ReadAllText(path, Encoding.UTF8);
        if (text.Length > 0 && text[0] == '\uFEFF')
        {
            text = text[1..];
        }

        var logical = new StringBuilder();
        foreach (var rawLine in text.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n'))
        {
            var line = rawLine;
            if (logical.Length == 0)
            {
                line = line.TrimStart();
                if (line.Length == 0 || line[0] is '#' or '!')
                {
                    continue;
                }
            }

            logical.Append(line);
            if (EndsWithContinuation(logical))
            {
                logical.Length--;
                continue;
            }

            ParseLine(logical.ToString(), result);
            logical.Clear();
        }

        if (logical.Length > 0)
        {
            ParseLine(logical.ToString(), result);
        }
        return result;
    }

    public void WriteWithSections(
        string path,
        IEnumerable<string> headerLines,
        IEnumerable<(string Title, IEnumerable<string> Keys)> sections)
    {
        var written = new HashSet<string>(StringComparer.Ordinal);
        var text = new StringBuilder();
        foreach (var line in headerLines)
        {
            text.Append("# ").AppendLine(line);
        }
        text.AppendLine();

        foreach (var section in sections)
        {
            var keys = section.Keys.Where(_values.ContainsKey).ToArray();
            if (keys.Length == 0)
            {
                continue;
            }

            text.Append("# ---- ").Append(section.Title).Append(" ----").AppendLine();
            foreach (var key in keys)
            {
                text.Append(EscapeKey(key)).Append('=').Append(EscapeValue(_values[key])).AppendLine();
                written.Add(key);
            }
            text.AppendLine();
        }

        var others = _values.Keys.Where(k => !written.Contains(k)).OrderBy(k => k, StringComparer.Ordinal).ToArray();
        if (others.Length > 0)
        {
            text.AppendLine("# ---- 其它（未识别的键，原样保留） ----");
            foreach (var key in others)
            {
                text.Append(EscapeKey(key)).Append('=').Append(EscapeValue(_values[key])).AppendLine();
            }
            text.AppendLine();
        }

        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + ".tmp";
        File.WriteAllText(temp, text.ToString(), new UTF8Encoding(false));
        File.Move(temp, path, true);
    }

    private static bool EndsWithContinuation(StringBuilder builder)
    {
        var slashCount = 0;
        for (var i = builder.Length - 1; i >= 0 && builder[i] == '\\'; i--)
        {
            slashCount++;
        }
        return slashCount % 2 == 1;
    }

    private static void ParseLine(string line, JavaProperties target)
    {
        var separator = -1;
        var escaped = false;
        for (var i = 0; i < line.Length; i++)
        {
            var c = line[i];
            if (escaped)
            {
                escaped = false;
                continue;
            }
            if (c == '\\')
            {
                escaped = true;
                continue;
            }
            if (c is '=' or ':' || char.IsWhiteSpace(c))
            {
                separator = i;
                break;
            }
        }

        string key;
        string value;
        if (separator < 0)
        {
            key = Unescape(line.Trim());
            value = string.Empty;
        }
        else
        {
            key = Unescape(line[..separator].Trim());
            var pos = separator;
            while (pos < line.Length && char.IsWhiteSpace(line[pos]))
            {
                pos++;
            }
            if (pos < line.Length && line[pos] is '=' or ':')
            {
                pos++;
            }
            while (pos < line.Length && char.IsWhiteSpace(line[pos]))
            {
                pos++;
            }
            value = Unescape(line[pos..]);
        }

        if (key.Length > 0)
        {
            target._values[key] = value;
        }
    }

    private static string Unescape(string input)
    {
        var output = new StringBuilder(input.Length);
        for (var i = 0; i < input.Length; i++)
        {
            var c = input[i];
            if (c != '\\' || i + 1 >= input.Length)
            {
                output.Append(c);
                continue;
            }

            var next = input[++i];
            switch (next)
            {
                case 't': output.Append('\t'); break;
                case 'n': output.Append('\n'); break;
                case 'r': output.Append('\r'); break;
                case 'f': output.Append('\f'); break;
                case 'u' when i + 4 < input.Length:
                    if (int.TryParse(input.Substring(i + 1, 4), NumberStyles.HexNumber,
                            CultureInfo.InvariantCulture, out var code))
                    {
                        output.Append((char)code);
                        i += 4;
                    }
                    else
                    {
                        output.Append('u');
                    }
                    break;
                default:
                    output.Append(next);
                    break;
            }
        }
        return output.ToString();
    }

    private static string EscapeKey(string key)
    {
        var output = new StringBuilder(key.Length);
        foreach (var c in key)
        {
            switch (c)
            {
                case ' ':
                case '=':
                case ':':
                case '#':
                case '!':
                case '\\':
                    output.Append('\\').Append(c);
                    break;
                case '\n': output.Append("\\n"); break;
                case '\r': output.Append("\\r"); break;
                case '\t': output.Append("\\t"); break;
                default: output.Append(c); break;
            }
        }
        return output.ToString();
    }

    private static string EscapeValue(string value)
    {
        var output = new StringBuilder(value.Length);
        foreach (var c in value)
        {
            switch (c)
            {
                case '\\': output.Append("\\\\"); break;
                case '\n': output.Append("\\n"); break;
                case '\r': output.Append("\\r"); break;
                case '\t': output.Append("\\t"); break;
                default: output.Append(c); break;
            }
        }
        return output.ToString();
    }
}
