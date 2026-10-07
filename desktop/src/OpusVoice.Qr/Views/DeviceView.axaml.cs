using System.Collections.Generic;
using System.IO;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Threading;
using OpusVoice.Qr.Services.Usb;

namespace OpusVoice.Qr.Views;

/// <summary>
/// The USB device tab: adb control for a phone plugged into this machine — list devices,
/// install the OpusVoice APK, launch or force-stop the app, and stream the app's logcat
/// (audio, RTP transport and pinhole/iroh lines) as the live evidence stream while the phone
/// streams. Degrades to clear guidance when adb or a device is absent.
/// </summary>
public partial class DeviceView : UserControl
{
    private const int MaxLogLines = 2000;
    private static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(3);

    private readonly AdbService _adb = new();
    private readonly DispatcherTimer _poll;
    private readonly List<string> _log = new();

    private string? _selectedSerial;

    public DeviceView()
    {
        InitializeComponent();
        UpdateAdbStatus();
        _poll = new DispatcherTimer(DispatcherPriority.Background) { Interval = PollInterval };
        _poll.Tick += (_, _) => RefreshDevices(keepSelection: true);
        _poll.Start();

        _adb.LogLine += line => Dispatcher.UIThread.Post(() => AppendLog(line), DispatcherPriority.Background);
        _adb.LogStopped += reason => Dispatcher.UIThread.Post(() => { LogText.Text += $"\n[{reason}]"; LogStopButton.IsEnabled = false; LogStartButton.IsEnabled = _selectedSerial is not null; });
        Unloaded += (_, _) => { _poll.Stop(); _adb.Dispose(); };
    }

    private void UpdateAdbStatus()
    {
        if (_adb.IsAvailable)
        {
            AdbStatus.Text = "adb found — plug a phone in over USB (authorize debugging on it if asked).";
            RefreshDevices(keepSelection: false);
        }
        else
        {
            AdbStatus.Text = "adb was not found. Install Android platform-tools and set ANDROID_HOME " +
                             "(or put adb on PATH); the rest of the app keeps working without it.";
        }
    }

    private void RefreshDevices(bool keepSelection)
    {
        if (!_adb.IsAvailable)
        {
            return;
        }

        IReadOnlyList<AdbDevice> devices = _adb.ListDevices();
        string? previous = _selectedSerial;
        DeviceList.Items.Clear();
        foreach (AdbDevice device in devices)
        {
            string label = string.IsNullOrEmpty(device.Model)
                ? device.Serial
                : $"{device.Model} ({device.Serial})";
            if (device.State != "device")
            {
                label += $" — {device.State}";
            }
            DeviceList.Items.Add(new ListBoxItem { Content = label, Tag = device.Serial });
        }

        string? select = keepSelection ? previous : null;
        if (select is null && DeviceList.Items.Count > 0)
        {
            select = (DeviceList.Items[0] as ListBoxItem)?.Tag as string;
        }
        _selectedSerial = select;
        DeviceList.SelectedItem = DeviceList.Items.OfType<ListBoxItem>().FirstOrDefault(i => i.Tag as string == select);
        bool ready = _selectedSerial is not null;
        InstallButton.IsEnabled = ready;
        LaunchButton.IsEnabled = ready;
        ForceStopButton.IsEnabled = ready;
        LogStartButton.IsEnabled = ready && !IsLogRunning;
        LogClearButton.IsEnabled = ready;
        if (DeviceList.Items.Count == 0)
        {
            DeviceList.Items.Add(new ListBoxItem { Content = "no devices — connect a phone over USB", IsEnabled = false });
        }
    }

    private bool IsLogRunning => LogStopButton.IsEnabled;

    private void OnRefreshClicked(object? sender, RoutedEventArgs e) => RefreshDevices(keepSelection: true);

    private void OnDeviceSelected(object? sender, SelectionChangedEventArgs e)
    {
        _selectedSerial = (DeviceList.SelectedItem as ListBoxItem)?.Tag as string;
        bool ready = _selectedSerial is not null;
        InstallButton.IsEnabled = ready;
        LaunchButton.IsEnabled = ready;
        ForceStopButton.IsEnabled = ready;
        LogStartButton.IsEnabled = ready && !IsLogRunning;
        LogClearButton.IsEnabled = ready;
    }

    private async void OnInstallClicked(object? sender, RoutedEventArgs e)
    {
        if (_selectedSerial is null) return;
        var files = await TopLevel.GetTopLevel(this)!.StorageProvider.OpenFilePickerAsync(new()
        {
            Title = "Choose an OpusVoice APK",
            AllowMultiple = false,
        });
        if (files.Count == 0) return;
        string path = Uri.UnescapeDataString(files[0].Path.AbsolutePath);
        ActionResult.Text = $"installing {Path.GetFileName(path)} on {_selectedSerial}…";
        string result = await Task.Run(() => _adb.InstallApk(_selectedSerial, path));
        ActionResult.Text = $"install: {result}";
    }

    private async void OnLaunchClicked(object? sender, RoutedEventArgs e)
    {
        if (_selectedSerial is null) return;
        string result = await Task.Run(() => _adb.LaunchApp(_selectedSerial));
        ActionResult.Text = result.Length == 0 ? "launch requested." : $"launch: {result}";
    }

    private async void OnForceStopClicked(object? sender, RoutedEventArgs e)
    {
        if (_selectedSerial is null) return;
        ActionResult.Text = await Task.Run(() => _adb.ForceStopApp(_selectedSerial));
    }

    private void OnLogStartClicked(object? sender, RoutedEventArgs e)
    {
        if (_selectedSerial is null) return;
        _adb.ClearLogcat(_selectedSerial);
        _log.Clear();
        LogText.Text = string.Empty;
        _adb.StartLogcat(_selectedSerial);
        LogStopButton.IsEnabled = true;
        LogStartButton.IsEnabled = false;
    }

    private void OnLogStopClicked(object? sender, RoutedEventArgs e)
    {
        _adb.StopLogcat();
        LogStopButton.IsEnabled = false;
        LogStartButton.IsEnabled = _selectedSerial is not null;
    }

    private void OnLogClearClicked(object? sender, RoutedEventArgs e)
    {
        if (_selectedSerial is null) return;
        _adb.ClearLogcat(_selectedSerial);
        _log.Clear();
        LogText.Text = string.Empty;
    }

    /// <summary>Stops polling and the logcat stream; called when the window closes.</summary>
    public void Shutdown()
    {
        _poll.Stop();
        _adb.Dispose();
    }

    private void AppendLog(string line)
    {
        _log.Add(line);
        if (_log.Count > MaxLogLines)
        {
            _log.RemoveRange(0, _log.Count - MaxLogLines);
        }
        LogText.Text = string.Join('\n', _log);
        if (LogAutoScroll.IsChecked == true)
        {
            LogScroll.ScrollToEnd();
        }
    }
}
