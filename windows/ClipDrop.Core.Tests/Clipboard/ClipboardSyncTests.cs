using ClipDrop.Core.Clipboard;
using ClipDrop.Core.Protocol;
using Microsoft.Extensions.Time.Testing;

namespace ClipDrop.Core.Tests.Clipboard;

public class ClipboardSyncTests
{
    private readonly ClipboardSync _sync = new(new EchoGuard(), new FakeTimeProvider(DateTimeOffset.FromUnixTimeMilliseconds(1_000)));

    [Fact]
    public void Plain_text_becomes_a_CLIP_TEXT()
    {
        OutgoingClip outgoing = _sync.Prepare(new LocalClip.TextClip("hello", Excluded: false));
        Assert.NotNull(outgoing.Message);
        Assert.Equal("hello", outgoing.Message.Text);
        Assert.Equal(1_000, outgoing.Message.Timestamp);
        Assert.True(Validation.IsUuid(outgoing.Message.ClipId));
    }

    [Theory]
    [InlineData("", SkipReason.Empty)]
    [InlineData(" \r\n\t ", SkipReason.Empty)]
    public void Blank_text_is_not_sent(string text, SkipReason reason)
    {
        Assert.Equal(reason, _sync.Prepare(new LocalClip.TextClip(text, Excluded: false)).Skipped);
    }

    [Fact]
    public void Excluded_non_text_and_empty_clips_are_not_sent()
    {
        Assert.Equal(SkipReason.Excluded, _sync.Prepare(new LocalClip.TextClip("hunter2", Excluded: true)).Skipped);
        Assert.Equal(SkipReason.NotText, _sync.Prepare(new LocalClip.NotText()).Skipped);
        Assert.Equal(SkipReason.Empty, _sync.Prepare(new LocalClip.Empty()).Skipped);
    }

    [Fact]
    public void A_clip_from_the_phone_is_never_sent_back_even_with_CRLF_line_endings()
    {
        _sync.OnReceived(new ClipText("3f2b8c1e-7a4d-4e6b-9c0a-1d2e3f405162", "line one\nline two", 1));
        Assert.Equal(SkipReason.Echo, _sync.Prepare(new LocalClip.TextClip("line one\r\nline two", Excluded: false)).Skipped);
    }

    [Fact]
    public void A_clip_just_sent_isnt_sent_again_which_stops_loops_with_other_clipboard_tools()
    {
        _sync.OnSent(_sync.Prepare(new LocalClip.TextClip("X", Excluded: false)).Message!);
        Assert.Equal(SkipReason.Echo, _sync.Prepare(new LocalClip.TextClip("X", Excluded: false)).Skipped);
    }

    [Fact]
    public void Copying_X_then_Y_then_X_again_sends_the_second_X()
    {
        _sync.OnSent(_sync.Prepare(new LocalClip.TextClip("X", Excluded: false)).Message!);
        _sync.OnSent(_sync.Prepare(new LocalClip.TextClip("Y", Excluded: false)).Message!);
        Assert.NotNull(_sync.Prepare(new LocalClip.TextClip("X", Excluded: false)).Message);
    }

    [Fact]
    public void A_send_that_reached_nobody_doesnt_count_as_exchanged()
    {
        _sync.Prepare(new LocalClip.TextClip("X", Excluded: false)); // no phone connected: OnSent never called
        Assert.NotNull(_sync.Prepare(new LocalClip.TextClip("X", Excluded: false)).Message);
    }

    [Fact]
    public void Text_too_large_for_one_frame_is_refused()
    {
        string huge = new('x', ProtocolConstants.MaxFrameLength);
        Assert.Equal(SkipReason.TooLarge, _sync.Prepare(new LocalClip.TextClip(huge, Excluded: false)).Skipped);
    }

    [Fact]
    public void The_echo_guard_forgets_old_clips_beyond_its_capacity()
    {
        var guard = new EchoGuard(capacity: 3);
        foreach (string text in new[] { "a", "b", "c", "d" })
        {
            guard.Remember(text);
        }

        Assert.False(guard.IsEcho("a"));
        Assert.True(guard.IsEcho("b") && guard.IsEcho("c") && guard.IsEcho("d"));
    }
}

public class ClipboardExclusionTests
{
    [Theory]
    [InlineData(ClipboardExclusion.ExcludeFromMonitorProcessing)]
    [InlineData(ClipboardExclusion.ClipboardViewerIgnore)]
    public void Marker_formats_exclude_the_clip(string format)
    {
        Assert.True(ClipboardExclusion.IsExcluded(f => f == format, _ => null));
    }

    [Theory]
    [InlineData(ClipboardExclusion.CanIncludeInClipboardHistory, 0, true)]
    [InlineData(ClipboardExclusion.CanUploadToCloudClipboard, 0, true)]
    [InlineData(ClipboardExclusion.CanIncludeInClipboardHistory, 1, false)]
    [InlineData(ClipboardExclusion.CanUploadToCloudClipboard, 1, false)]
    public void History_and_cloud_flags_exclude_only_when_zero(string format, int value, bool excluded)
    {
        Assert.Equal(excluded, ClipboardExclusion.IsExcluded(_ => false, f => f == format ? value : null));
    }

    [Fact]
    public void An_ordinary_clip_is_not_excluded()
    {
        Assert.False(ClipboardExclusion.IsExcluded(_ => false, _ => null));
    }
}
