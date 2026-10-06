using System.Text.Json;

namespace OpusVoice.Qr.Services;

/// <summary>One scanned payload, persisted across sessions.</summary>
public sealed class HistoryEntry
{
    /// <summary>The raw scanned text (already routed through the payload parser when displayed).</summary>
    public string RawText { get; set; } = string.Empty;

    public DateTimeOffset ScannedAtUtc { get; set; }
}

/// <summary>
/// Scan history kept as JSON under <c>~/.local/share/OpusVoice.Qr/history.json</c>
/// (<see cref="Environment.SpecialFolder.ApplicationData"/>), newest first. Corrupt or
/// missing files degrade to an empty history rather than crashing.
/// </summary>
internal sealed class HistoryStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };
    private readonly string _path;
    private readonly object _gate = new();

    public static HistoryStore Instance { get; } = new();

    public HistoryStore()
    {
        string appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        _path = Path.Combine(appData, "OpusVoice.Qr", "history.json");
    }

    public IReadOnlyList<HistoryEntry> Load()
    {
        lock (_gate)
        {
            try
            {
                if (!File.Exists(_path)) return Array.Empty<HistoryEntry>();
                List<HistoryEntry>? entries = JsonSerializer.Deserialize<List<HistoryEntry>>(File.ReadAllText(_path));
                return entries ?? new List<HistoryEntry>();
            }
            catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
            {
                return Array.Empty<HistoryEntry>();
            }
        }
    }

    /// <summary>Records a scan at the head of the history and persists it.</summary>
    public void Append(string rawText)
    {
        lock (_gate)
        {
            List<HistoryEntry> entries;
            try
            {
                entries = LoadUnlocked() ?? new List<HistoryEntry>();
            }
            catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
            {
                entries = new List<HistoryEntry>();
            }

            entries.Insert(0, new HistoryEntry { RawText = rawText, ScannedAtUtc = DateTimeOffset.UtcNow });
            SaveUnlocked(entries);
        }
    }

    public void Clear()
    {
        lock (_gate)
        {
            SaveUnlocked(new List<HistoryEntry>());
        }
    }

    private List<HistoryEntry>? LoadUnlocked()
    {
        if (!File.Exists(_path)) return null;
        return JsonSerializer.Deserialize<List<HistoryEntry>>(File.ReadAllText(_path));
    }

    private void SaveUnlocked(List<HistoryEntry> entries)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            File.WriteAllText(_path, JsonSerializer.Serialize(entries, JsonOptions));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // history is a convenience: never let it take the app down
        }
    }
}
