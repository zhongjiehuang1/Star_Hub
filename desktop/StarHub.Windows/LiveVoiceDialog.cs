using System.Net.WebSockets;
using System.Text;
using System.Threading.Channels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using NAudio.Wave;
using StarHub.Core;

namespace StarHub.Windows;

public sealed partial class MainWindow
{
    private async Task ShowLiveVoiceAsync()
    {
        var key = MediaKey();
        var body = new StackPanel { Spacing = 10 };
        var models = new ComboBox { Header = T("Realtime model"), ItemsSource = ModelCatalog.RealtimeModels.Select(model => model.Id).ToArray() };
        models.SelectedIndex = 0;
        var devices = new ComboBox { Header = T("Microphone"), HorizontalAlignment = HorizontalAlignment.Stretch };
        devices.ItemsSource = new[] { "Windows default" }.Concat(Enumerable.Range(0, WaveIn.DeviceCount)
            .Select(index => WaveIn.GetCapabilities(index).ProductName)).ToArray();
        devices.SelectedIndex = 0;
        var start = new Button { Content = T("Connect and turn on microphone") };
        var end = new Button { Content = T("Disconnect"), IsEnabled = false };
        var mute = new ToggleButton { Content = T("Mute microphone"), IsEnabled = false };
        var cancelReply = new Button { Content = T("Interrupt reply"), IsEnabled = false };
        var status = Label(T("While connected, microphone audio goes to OpenAI. The session can hear you while it replies."));
        var transcript = Field(T("Session transcript"), "", 0, multiline: true);
        transcript.IsReadOnly = true;
        transcript.MaxHeight = 220;
        var save = new Button { Content = T("Save transcript…"), IsEnabled = false };
        var controls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 7 };
        controls.Children.Add(mute);
        controls.Children.Add(cancelReply);
        controls.Children.Add(end);
        foreach (var element in new UIElement[] { models, devices, start, controls, status, transcript, save })
            body.Children.Add(element);

        CancellationTokenSource? active = null;
        Task? running = null;
        bool closed = false;
        start.Click += (_, _) =>
        {
            if (active is not null || models.SelectedItem is not string model) return;
            var sessionToken = new CancellationTokenSource(TimeSpan.FromMinutes(25));
            active = sessionToken;
            running = RunAsync(sessionToken, model, devices.SelectedIndex - 1);
        };
        end.Click += (_, _) => active?.Cancel();
        save.Click += async (_, _) => await TryAsync(() => SaveTextAsync(transcript.Text, "StarHub voice transcript"));

        async Task RunAsync(CancellationTokenSource sessionToken, string model, int device)
        {
            var token = sessionToken.Token;
            start.IsEnabled = models.IsEnabled = devices.IsEnabled = save.IsEnabled = false;
            end.IsEnabled = true;
            status.Text = "Connecting to OpenAI…";
            transcript.Text = "";
            try
            {
                StopReading();
                await using var voice = await RealtimeVoice.ConnectAsync(key, model, voiceDetection: true, token);
                using var audio = new DuplexAudio(device);
                var input = Channel.CreateBounded<(int Epoch, byte[] Bytes)>(new BoundedChannelOptions(50)
                { SingleReader = true, SingleWriter = true, FullMode = BoundedChannelFullMode.DropOldest });
                var microphone = new CancellationTokenSource();
                using var audioScope = CancellationTokenSource.CreateLinkedTokenSource(token, microphone.Token);
                using var microphoneScope = microphone;
                var interrupted = new HashSet<string>();
                var interruptedResponses = new HashSet<string>();
                int micEpoch = 0;
                string? response = null, transcriptItem = null;
                bool isMuted = false;
                Exception? deviceError = null;

                void ToggleMute(object sender, RoutedEventArgs args)
                {
                    Interlocked.Increment(ref micEpoch);
                    Volatile.Write(ref isMuted, mute.IsChecked == true);
                    mute.Content = isMuted ? "Unmute microphone" : "Mute microphone";
                    status.Text = isMuted ? "Microphone muted" : "Listening";
                }

                async Task InterruptAsync(bool cancel)
                {
                    var output = audio.Interrupt();
                    if (response is { } currentlyPlaying) interruptedResponses.Add(currentlyPlaying);
                    if (output is { } cut) interrupted.Add(cut.Item);
                    if (cancel && response is { } ongoing)
                        await voice.CancelResponseAsync(ongoing, audioScope.Token);
                    if (output is { } played)
                        await voice.TruncateAsync(played.Item, played.ContentIndex, played.Milliseconds, audioScope.Token);
                }

                async void InterruptClick(object sender, RoutedEventArgs args)
                {
                    try { await InterruptAsync(cancel: true); }
                    catch (OperationCanceledException) { }
                    catch (Exception error) { if (!closed) status.Text = DialogError(error); }
                }

                void DeviceStopped(Exception error)
                {
                    deviceError = error;
                    audioScope.Cancel();
                }

                audio.RecordingFailed += DeviceStopped;
                audio.Frame += frame =>
                {
                    int epoch = Volatile.Read(ref micEpoch);
                    if (!Volatile.Read(ref isMuted)) input.Writer.TryWrite((epoch, frame));
                };
                mute.IsChecked = false;
                mute.Content = T("Mute microphone");
                mute.Click += ToggleMute;
                cancelReply.Click += InterruptClick;
                mute.IsEnabled = cancelReply.IsEnabled = true;
                status.Text = "Listening";
                var sendAudio = PumpAudioAsync(voice, input.Reader, audioScope,
                    epoch => !Volatile.Read(ref isMuted) && Volatile.Read(ref micEpoch) == epoch);
                try
                {
                    audio.Start();
                    var text = new StringBuilder();
                    await foreach (var update in voice.ReadAsync(audioScope.Token))
                    {
                        switch (update.Kind)
                        {
                            case VoiceEventKind.Audio when update.Pcm is { } pcm && update.Item is { } item
                                && !interrupted.Contains(item) && (update.Response is null || !interruptedResponses.Contains(update.Response)):
                                audio.Play(item, update.ContentIndex, pcm);
                                break;
                            case VoiceEventKind.ResponseStarted:
                                response = update.Response;
                                if (!isMuted) status.Text = "Replying · microphone on";
                                break;
                            case VoiceEventKind.ResponseDone:
                                if (response == update.Response) response = null;
                                if (!isMuted) status.Text = "Listening";
                                break;
                            case VoiceEventKind.SpeechStarted:
                                await InterruptAsync(cancel: false);
                                if (!isMuted) status.Text = "Listening";
                                break;
                            case VoiceEventKind.SpeechStopped:
                                if (!isMuted) status.Text = "Thinking…";
                                break;
                            case VoiceEventKind.UserTranscript when !string.IsNullOrWhiteSpace(update.Text):
                                text.Append("\n\nYou\n").Append(update.Text);
                                transcriptItem = null;
                                break;
                            case VoiceEventKind.AssistantText when update.Item is { } reply && !interrupted.Contains(reply)
                                && (update.Response is null || !interruptedResponses.Contains(update.Response)):
                                if (transcriptItem != reply) text.Append("\n\nStar Hub\n");
                                transcriptItem = reply;
                                text.Append(update.Text);
                                break;
                            case VoiceEventKind.Warning:
                                status.Text = update.Text ?? "OpenAI reported a voice error.";
                                break;
                        }
                        if (update.Kind is VoiceEventKind.UserTranscript or VoiceEventKind.AssistantText)
                        {
                            if (text.Length > 100_000) text.Remove(0, text.Length - 95_000);
                            transcript.Text = text.ToString().TrimStart();
                            transcript.SelectionStart = transcript.Text.Length;
                        }
                    }
                }
                catch (OperationCanceledException) when (audioScope.IsCancellationRequested) { }
                finally
                {
                    audioScope.Cancel();
                    input.Writer.TryComplete();
                    audio.RecordingFailed -= DeviceStopped;
                    mute.Click -= ToggleMute;
                    cancelReply.Click -= InterruptClick;
                    try { await sendAudio; }
                    catch (OperationCanceledException) when (audioScope.IsCancellationRequested) { }
                    if (deviceError is not null) throw deviceError;
                }
                if (!closed) status.Text = token.IsCancellationRequested ? "Voice session ended." : "The voice server disconnected.";
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            { if (!closed) status.Text = "Voice session ended."; }
            catch (WebSocketException)
            { if (!closed) status.Text = "Could not reach the OpenAI Realtime endpoint. Check your connection and model access."; }
            catch (Exception error)
            { if (!closed) status.Text = DialogError(error); }
            finally
            {
                active = null;
                sessionToken.Dispose();
                start.IsEnabled = models.IsEnabled = devices.IsEnabled = true;
                end.IsEnabled = mute.IsEnabled = cancelReply.IsEnabled = false;
                save.IsEnabled = !string.IsNullOrWhiteSpace(transcript.Text);
            }
        }

        await Dialog(T("Live voice"), Scroller(body)).ShowAsync();
        closed = true;
        active?.Cancel();
        if (running is not null) await running;
    }

    private static async Task PumpAudioAsync(RealtimeVoice voice, ChannelReader<(int Epoch, byte[] Bytes)> input,
        CancellationTokenSource session, Func<int, bool> isCurrent)
    {
        try
        {
            await foreach (var frame in input.ReadAllAsync(session.Token).ConfigureAwait(false))
                if (isCurrent(frame.Epoch)) await voice.AppendAsync(frame.Bytes, session.Token).ConfigureAwait(false);
        }
        catch
        {
            session.Cancel();
            throw;
        }
    }

    private sealed record CutAudio(string Item, int ContentIndex, int Milliseconds);

    private sealed class DuplexAudio : IDisposable
    {
        private static readonly WaveFormat Format = new(RealtimeVoice.SampleRate, 16, 1);
        private readonly WaveIn input;
        private readonly WaveOut output = new() { BufferMilliseconds = 50, NumberOfBuffers = 3 };
        private readonly BufferedWaveProvider player = new(Format, TimeSpan.FromSeconds(28));
        private string? playItem;
        private int playIndex, received;
        private long startPosition;
        public event Action<byte[]>? Frame;
        public event Action<Exception>? RecordingFailed;

        public DuplexAudio(int device)
        {
            input = new WaveIn { DeviceNumber = device, WaveFormat = Format, BufferMilliseconds = 40, NumberOfBuffers = 3 };
            // WaveIn owns and reuses its capture buffers, so each frame gets a private copy.
            input.DataAvailable += (_, args) => { if (args.BytesRecorded >= 2) Frame?.Invoke(args.Buffer[..args.BytesRecorded]); };
            input.RecordingStopped += (_, args) => { if (args.Exception is { } error) RecordingFailed?.Invoke(error); };
            output.PlaybackStopped += (_, args) => { if (args.Exception is { } error) RecordingFailed?.Invoke(error); };
        }

        public void Start()
        {
            output.Init(player);
            output.Play();
            input.StartRecording();
        }

        public void Play(string item, int index, byte[] pcm)
        {
            if (playItem != item)
            {
                playItem = item;
                playIndex = index;
                startPosition = output.GetPosition() + player.BufferedBytes;
                received = 0;
            }
            player.AddSamples(pcm, 0, pcm.Length);
            received += pcm.Length;
        }

        public CutAudio? Interrupt()
        {
            if (playItem is not { } item) return null;
            long played = Math.Clamp(output.GetPosition() - startPosition, 0, received);
            playItem = null;
            if (played >= received && player.BufferedBytes == 0) return null;
            output.Stop();
            player.ClearBuffer();
            output.Play();
            return new CutAudio(item, playIndex, (int)(played / RealtimeVoice.BytesPerMillisecond));
        }

        public void Dispose()
        {
            input.Dispose();
            output.Dispose();
        }
    }
}
