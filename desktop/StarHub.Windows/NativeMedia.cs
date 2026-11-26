using System.Buffers.Binary;
using System.Threading.Channels;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using StarHub.Core;
using NAudio.Wave;
using Rectangle = Microsoft.UI.Xaml.Shapes.Rectangle;
using Windows.Media.Playback;
using Windows.Media.SpeechSynthesis;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private static readonly string[] ImageExtensions = [".png", ".jpg", ".jpeg", ".webp"];
    private MediaPlayer? speechPlayer;
    private IRandomAccessStream? speechStream;
    private long speechRevision;
    private VoiceRecording? voiceRecording;
    private bool voiceTransition;
    private bool VoiceBusy => voiceTransition || voiceRecording is not null;

    private sealed class VoiceRecording : IDisposable
    {
        private static readonly WaveFormat Format = new(RealtimeVoice.SampleRate, 16, 1);
        private readonly WaveIn microphone = new() { DeviceNumber = -1, WaveFormat = Format, BufferMilliseconds = 45, NumberOfBuffers = 3 };
        private readonly MemoryStream audio = new();
        private readonly WaveFileWriter writer;
        private readonly object gate = new();
        private readonly TaskCompletionSource<Exception?> stopped = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly Channel<byte[]> chunks = Channel.CreateUnbounded<byte[]>();
        private readonly CancellationTokenSource transcriptionLifetime = new(TimeSpan.FromSeconds(70));
        private bool disposed;
        private float level;
        public readonly DispatcherQueueTimer Limit, Meter;
        public readonly string Key;
        public readonly bool CanSubmit;
        public Task<string?>? Transcript { get; private set; }

        public VoiceRecording(DispatcherQueueTimer limit, DispatcherQueueTimer meter, string key, bool canSubmit)
        {
            Limit = limit;
            Meter = meter;
            Key = key;
            CanSubmit = canSubmit;
            writer = new WaveFileWriter(audio, Format);
            microphone.DataAvailable += (_, args) =>
            {
                lock (gate)
                {
                    if (disposed || args.BytesRecorded < 2) return;
                    writer.Write(args.Buffer, 0, args.BytesRecorded);
                    if (!transcriptionLifetime.IsCancellationRequested)
                        chunks.Writer.TryWrite(args.Buffer.AsSpan(0, args.BytesRecorded).ToArray());
                    double squares = 0;
                    int samples = args.BytesRecorded / sizeof(short);
                    for (int sample = 0; sample < samples; sample++)
                    {
                        double value = BinaryPrimitives.ReadInt16LittleEndian(args.Buffer.AsSpan(sample * sizeof(short), sizeof(short))) / 32768.0;
                        squares += value * value;
                    }
                    Volatile.Write(ref level, (float)Math.Sqrt(squares / samples));
                }
            };
            microphone.RecordingStopped += (_, args) => stopped.TrySetResult(args.Exception);
        }

        public float Level => Volatile.Read(ref level);
        public bool Stopped => stopped.Task.IsCompleted;
        public void Start() => microphone.StartRecording();

        public void StartTranscript(Action<string> onText) => Transcript = ListenAsync(onText);

        private async Task<string?> ListenAsync(Action<string> onText)
        {
            try
            {
                var token = transcriptionLifetime.Token;
                return await RealtimeVoice.TranscribeAsync(Key, chunks.Reader.ReadAllAsync(token), onText, token).ConfigureAwait(false);
            }
            catch (Exception error)
            {
                StarHub.Platform.WindowsHost.Log("desktop.log", $"Live transcription: {error.GetType().Name}.");
                return null;
            }
            finally { chunks.Writer.TryComplete(); }
        }

        public async Task<MemoryStream> FinishAsync()
        {
            if (!Stopped) microphone.StopRecording();
            var error = await stopped.Task.WaitAsync(TimeSpan.FromSeconds(3));
            chunks.Writer.TryComplete();
            if (error is not null) throw error;
            return new MemoryStream(CloseAudio(), writable: false);
        }

        private byte[] CloseAudio()
        {
            lock (gate)
            {
                if (disposed) return [];
                disposed = true;
                writer.Dispose();
                return audio.ToArray();
            }
        }

        public void Dispose()
        {
            Limit.Stop();
            Meter.Stop();
            microphone.Dispose();
            chunks.Writer.TryComplete();
            transcriptionLifetime.Cancel();
            transcriptionLifetime.Dispose();
            lock (gate)
            {
                if (disposed) return;
                disposed = true;
                writer.Dispose();
            }
        }
    }

    private string MediaKey() => desktopAccount.Read(ChatEngine.OpenAiCredential) ??
        throw new StarHubException(T(accountProfile is null
            ? "Sign in to use your saved models."
            : "Save an OpenAI key on the website, then refresh this connection."));

    private async Task<StorageFile?> ChooseFileAsync(IEnumerable<string> extensions)
    {
        var picker = new FileOpenPicker();
        WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);
        foreach (var extension in extensions) picker.FileTypeFilter.Add(extension);
        return await picker.PickSingleFileAsync();
    }

    private async Task ShowImagesAsync()
    {
        var key = MediaKey();
        var body = new StackPanel { Spacing = 8 };
        var models = new ComboBox { Header = T("OpenAI image model"), ItemsSource = ModelCatalog.ImageModels.Select(model => model.Id).ToArray() };
        models.SelectedIndex = 0;
        var prompt = Field(T("Image prompt"), "", 10000, multiline: true);
        var image = new Image { MaxHeight = 300, Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform };
        var status = Label(T("Add a source image to edit it, or enter a prompt to generate an image."));
        var attach = new Button { Content = T("Choose source image…") };
        var clear = new Button { Content = T("Remove source"), IsEnabled = false };
        var generate = new Button { Content = T("Generate image") };
        var save = new Button { Content = T("Save a copy…"), IsEnabled = false };
        var next = new Button { Content = T("Use result as source"), IsEnabled = false };
        var sources = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        sources.Children.Add(attach);
        sources.Children.Add(clear);
        var outputs = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        outputs.Children.Add(save);
        outputs.Children.Add(next);
        foreach (var item in new UIElement[] { models, prompt, sources, generate, status, image, outputs }) body.Children.Add(item);

        using var cancellation = new CancellationTokenSource();
        var dialog = Dialog(T("Generate or edit an image"), Scroller(body));
        Attachment? source = null;
        byte[]? result = null;
        string? saved = null;
        bool busy = false;

        void Select(Attachment? value)
        {
            source = value;
            clear.IsEnabled = source is not null;
            generate.Content = source is null ? "Generate image" : "Edit image";
            status.Text = source is null ? "Ready to generate an image." : "Source image · " + source.Name;
        }

        attach.Click += async (_, _) =>
        {
            try
            {
                if (await ChooseFileAsync(ImageExtensions) is not { } file) return;
                using var input = await file.OpenStreamForReadAsync();
                Select(await Attachments.ReadAsync(file.Name, input));
            }
            catch (Exception error) { status.Text = DialogError(error); }
        };
        clear.Click += (_, _) => Select(null);
        next.Click += (_, _) =>
        {
            if (result is not null && saved is not null) Select(new Attachment(Path.GetFileName(saved), "image/png", result));
        };
        generate.Click += async (_, _) =>
        {
            if (busy || models.SelectedItem is not string model) return;
            busy = true;
            attach.IsEnabled = clear.IsEnabled = generate.IsEnabled = next.IsEnabled = save.IsEnabled = false;
            status.Text = "Waiting for the OpenAI image model…";
            try
            {
                var png = await providerClient.MakeImageAsync(key, model, prompt.Text, source, cancellation.Token);
                cancellation.Token.ThrowIfCancellationRequested();
                var folder = Path.Combine(store.DirectoryPath, "Images");
                Directory.CreateDirectory(folder);
                var fileName = "StarHub-" + Guid.NewGuid().ToString("N") + ".png";
                var path = Path.Combine(folder, fileName);
                var temporary = path + ".tmp";
                try
                {
                    await File.WriteAllBytesAsync(temporary, png, cancellation.Token);
                    File.Move(temporary, path);
                }
                finally { File.Delete(temporary); }

                image.Source = await PngSourceAsync(png, 550);
                result = png;
                saved = path;
                status.Text = "Saved to the local Images folder.";
            }
            catch (OperationCanceledException)
            {
                if (!cancellation.IsCancellationRequested) status.Text = "The image request timed out.";
            }
            catch (Exception error) { status.Text = DialogError(error); }
            finally
            {
                busy = false;
                attach.IsEnabled = generate.IsEnabled = true;
                clear.IsEnabled = source is not null;
                next.IsEnabled = save.IsEnabled = result is not null;
            }
        };
        save.Click += async (_, _) =>
        {
            try
            {
                if (result is null) return;
                var picker = new FileSavePicker { SuggestedFileName = "StarHub image" };
                WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);
                picker.FileTypeChoices.Add("PNG image", [".png"]);
                if (await picker.PickSaveFileAsync() is { } file)
                    await FileIO.WriteBytesAsync(file, result);
            }
            catch (Exception error) { status.Text = DialogError(error); }
        };
        await dialog.ShowAsync();
        cancellation.Cancel();
    }

    private static async Task<BitmapImage> PngSourceAsync(byte[] png, int displayWidth)
    {
        using var preview = new InMemoryRandomAccessStream();
        using (var writer = new DataWriter(preview))
        {
            writer.WriteBytes(png);
            await writer.StoreAsync();
            writer.DetachStream();
        }
        preview.Seek(0);
        var bitmap = new BitmapImage { DecodePixelWidth = displayWidth };
        await bitmap.SetSourceAsync(preview);
        return bitmap;
    }

    private async Task ShowTranscribeAudioAsync()
    {
        var key = MediaKey();
        if (await ChooseFileAsync(ProviderClient.AudioExtensions) is not { } file) return;
        using var input = await file.OpenStreamForReadAsync();
        await TranscribeToComposerAsync(key, input, file.Name);
    }

    private async void VoiceInputClicked(object sender, RoutedEventArgs args) => await TryAsync(async () =>
    {
        if (request is not null || voiceTransition) return;
        if (voiceRecording is not null) await FinishVoiceAsync(submit: true);
        else await StartVoiceAsync();
    });

    private Task StartVoiceAsync()
    {
        var key = MediaKey();
        var limit = DispatcherQueue.CreateTimer();
        limit.Interval = TimeSpan.FromSeconds(50);
        limit.IsRepeating = false;
        limit.Tick += async (_, _) => await TryAsync(() => FinishVoiceAsync(submit: false));
        var meter = DispatcherQueue.CreateTimer();
        meter.Interval = TimeSpan.FromMilliseconds(55);
        var recording = new VoiceRecording(limit, meter, key, string.IsNullOrWhiteSpace(Composer.Text) && attachment is null);
        try
        {
            recording.Start();
            voiceRecording = recording;
            recording.StartTranscript(text => DispatcherQueue.TryEnqueue(() =>
            {
                if (voiceRecording != recording || string.IsNullOrWhiteSpace(text)) return;
                VoiceTranscript.Text = text;
                VoiceTranscript.Visibility = Visibility.Visible;
            }));
            var ink = Brush("BrandBrush");
            for (int i = 0; i < 42; i++)
                VoiceWaveform.Children.Add(new Rectangle { Fill = ink, Height = 3, RadiusX = 2, RadiusY = 2 });
            VoiceWaveform.Visibility = Visibility.Visible;
            meter.Tick += async (_, _) =>
            {
                if (recording.Stopped)
                {
                    meter.Stop();
                    await TryAsync(() => FinishVoiceAsync(submit: false));
                }
                else UpdateWaveform(recording.Level);
            };
            meter.Start();
            limit.Start();
            Say(T(recording.CanSubmit ? "Recording… Tap the microphone to send." : "Recording… Tap the microphone to add this to your draft."));
        }
        catch (Exception error)
        {
            if (voiceRecording == recording) DisposeVoiceRecording();
            else recording.Dispose();
            StarHub.Platform.WindowsHost.Log("desktop.log", $"Voice input: {error.GetType().Name} (0x{error.HResult:X8}).");
            throw new StarHubException(T("Could not start the microphone. Check your Windows input device and permissions."));
        }
        finally { SetSending(false); }
        return Task.CompletedTask;
    }

    private void UpdateWaveform(float level)
    {
        int count = VoiceWaveform.Children.Count;
        if (count == 0 || VoiceWaveform.ActualWidth <= 0) return;
        double spacing = VoiceWaveform.ActualWidth / count;
        double height = VoiceWaveform.Height;
        double next = 3 + (height - 3) * Math.Clamp(Math.Log10(1 + 90 * level), 0, 1);
        for (int index = 0; index < count; index++)
        {
            var bar = (Rectangle)VoiceWaveform.Children[index];
            bar.Height = index + 1 == count ? next : ((Rectangle)VoiceWaveform.Children[index + 1]).Height;
            bar.Width = Math.Max(1, spacing - 3);
            bar.Opacity = 0.42 + 0.58 * index / Math.Max(1, count - 1);
            Canvas.SetLeft(bar, index * spacing);
            Canvas.SetTop(bar, (height - bar.Height) / 2);
        }
    }

    private async Task FinishVoiceAsync(bool submit, bool discard = false)
    {
        if (voiceTransition || voiceRecording is not { } recording) return;
        voiceTransition = true;
        recording.Limit.Stop();
        recording.Meter.Stop();
        VoiceWaveform.Visibility = Visibility.Collapsed;
        SetSending(false);
        try
        {
            if (discard) Say("");
            else
            {
                using var input = await recording.FinishAsync();
                string? transcript = null;
                if (recording.Transcript is { } pending)
                {
                    try { transcript = await pending.WaitAsync(TimeSpan.FromSeconds(5)); }
                    catch (TimeoutException) { }
                }
                if (string.IsNullOrWhiteSpace(transcript)) await TranscribeToComposerAsync(recording.Key, input, "recording.wav");
                else AddTranscriptToComposer(transcript);
            }
        }
        finally
        {
            DisposeVoiceRecording();
            voiceTransition = false;
            SetSending(false);
            Composer.Focus(FocusState.Programmatic);
        }
        if (submit && recording.CanSubmit && !discard) await SendAsync();
    }

    private void DisposeVoiceRecording()
    {
        var recording = voiceRecording;
        voiceRecording = null;
        VoiceTranscript.Visibility = Visibility.Collapsed;
        VoiceTranscript.Text = "";
        VoiceWaveform.Visibility = Visibility.Collapsed;
        VoiceWaveform.Children.Clear();
        recording?.Dispose();
    }

    private void UpdateVoiceLabel()
    {
        string label = T(voiceRecording is null ? "Start voice input" : voiceRecording.CanSubmit
            ? "Stop recording and send" : "Stop recording and add to draft");
        AutomationProperties.SetName(VoiceInput, label);
        ToolTipService.SetToolTip(VoiceInput, voiceRecording is null ? T("Start voice input (OpenAI)") : label);
        VoiceInputIcon.Glyph = voiceRecording is null ? "\uE720" : "\uE724";
        AutomationProperties.SetName(StopButton, T(voiceRecording is null ? "Stop" : "Discard voice input"));
    }

    private async Task TranscribeToComposerAsync(string key, Stream input, string name)
    {
        Say(T("Transcribing with OpenAI…"));
        var text = await providerClient.TranscribeAsync(key, input, name, CancellationToken.None);
        AddTranscriptToComposer(text);
    }

    private void AddTranscriptToComposer(string text)
    {
        var updated = string.IsNullOrWhiteSpace(Composer.Text) ? text : Composer.Text.TrimEnd() + "\n" + text;
        if (updated.Length > Composer.MaxLength) throw new StarHubException("The transcript is too long for the current draft.");
        Composer.Text = updated;
        Composer.SelectionStart = Composer.Text.Length;
        Composer.Focus(FocusState.Programmatic);
        Say(T("Transcript added to your draft."));
    }

    private Task SpeakAsync(string text) => ReadSpeechAsync(text, lesson: false, CancellationToken.None);

    private Task ReadLessonAloudAsync(string text, CancellationToken token) => ReadSpeechAsync(text, lesson: true, token);

    private async Task ReadSpeechAsync(string text, bool lesson, CancellationToken token)
    {
        StopReading();
        long revision = speechRevision;
        using var synthesizer = new SpeechSynthesizer();
        bool mandarin = text.Take(1200).Count(c => c is >= '\u4e00' and <= '\u9fff') >= (lesson ? 2 : 12);
        var voice = mandarin ? SpeechSynthesizer.AllVoices.FirstOrDefault(v =>
            v.Language.StartsWith("zh", StringComparison.OrdinalIgnoreCase)) : null;
        IRandomAccessStream stream;
        string mime;
        bool cloud = lesson && mandarin && voice is null;
        if (cloud)
        {
            byte[] audio = await providerClient.SpeakMandarinAsync(MediaKey(), text, token);
            stream = new MemoryStream(audio, writable: false).AsRandomAccessStream();
            mime = "audio/mpeg";
        }
        else
        {
            if (voice is not null) synthesizer.Voice = voice;
            var native = await synthesizer.SynthesizeTextToStreamAsync(text[..Math.Min(text.Length, 8000)]);
            stream = native;
            mime = native.ContentType;
        }
        if (revision != speechRevision || token.IsCancellationRequested) { stream.Dispose(); return; }
        speechStream = stream;
        speechPlayer ??= new MediaPlayer();
        speechPlayer.Source = global::Windows.Media.Core.MediaSource.CreateFromStream(stream, mime);
        speechPlayer.Play();
        Say(cloud ? WorkText("正在使用 OpenAI 合成语音朗读。", "Reading with an AI-generated OpenAI voice.")
            : T("Reading aloud using a Windows voice."));
    }

    private void StopReadingClicked(object sender, RoutedEventArgs args) => Try(() =>
    {
        StopReading();
        Say("");
    });

    private void StopReading()
    {
        speechRevision++;
        if (speechPlayer is not null)
        {
            speechPlayer.Pause();
            speechPlayer.Source = null;
        }
        speechStream?.Dispose();
        speechStream = null;
    }
}
