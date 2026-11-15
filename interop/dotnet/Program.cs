using System.Globalization;
using ClipDrop.Core.Identity;
using ClipDrop.Core.Net;
using ClipDrop.Core.Pairing;
using ClipDrop.Core.Protocol;
using ClipDrop.Core.Storage;

// clipdrop-interop-host: runs ClipDrop.Core's host server for the interop test.
//
//   clipdrop-interop-host --state <dir> [--port <n>] [--pair-uri-file <path>] [--lifetime <seconds>]
//
// With --pair-uri-file it opens pairing mode and writes the pairing URI (pointing at 127.0.0.1) to that
// file once the server is listening. It prints one line per event and runs until killed or until
// --lifetime expires.

string? state = null;
string? pairUriFile = null;
int port = 0;
int lifetimeSeconds = 600;
for (int i = 0; i < args.Length; i++)
{
    string Next() => i + 1 < args.Length ? args[++i] : throw new ArgumentException($"{args[i]} needs a value");
    switch (args[i])
    {
        case "--state": state = Next(); break;
        case "--port": port = int.Parse(Next(), CultureInfo.InvariantCulture); break;
        case "--pair-uri-file": pairUriFile = Next(); break;
        case "--lifetime": lifetimeSeconds = int.Parse(Next(), CultureInfo.InvariantCulture); break;
        default: Console.Error.WriteLine($"Unknown argument {args[i]}"); return 2;
    }
}

if (state is null)
{
    Console.Error.WriteLine("Usage: clipdrop-interop-host --state <dir> [--port <n>] [--pair-uri-file <path>] [--lifetime <seconds>]");
    return 2;
}

Directory.CreateDirectory(state);
ISecretProtector protector = OperatingSystem.IsWindows() ? new DpapiSecretProtector() : new PassThroughProtector();
using HostIdentity identity = new IdentityStore(state, protector, TimeProvider.System).LoadOrCreate("Interop Host");
var devices = new SqlitePairedDeviceStore(Path.Combine(state, "devices.db"));
var pairing = new PairingCoordinator(TimeProvider.System);
await using var server = new HostServer(identity, devices, pairing, () => "Interop Host", "0.1.0", TimeProvider.System);

server.DevicePaired += (_, device) => Console.WriteLine($"PAIRED {device.DeviceId} {device.Name}");
server.SessionStarted += (_, session) => Console.WriteLine($"SESSION_STARTED {session.Device.DeviceId}");
server.SessionEnded += (_, session) =>
    Console.WriteLine($"SESSION_ENDED {session.Device.DeviceId} {session.Session.Completion.Result}");

// Clipboard exchange: answer every clip with "PC echo: " + its text, so the client can check both
// directions, including the exact bytes of awkward text.
server.MessageReceived += async (_, received) =>
{
    if (received.Message is not ClipText clip)
    {
        return;
    }

    Console.WriteLine($"CLIP_RECEIVED {clip.Text.Length} chars");
    var reply = new ClipText(Guid.NewGuid().ToString("D"), "PC echo: " + clip.Text, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
    await received.Session.Session.SendAsync(reply);
};

// Loopback only: the harness never needs other machines, and loopback needs no firewall exception.
server.Start(port == 0 ? [] : [port], System.Net.IPAddress.Loopback);
Console.WriteLine($"HOST {identity.DeviceId}");
Console.WriteLine($"LISTENING {server.Port}");

if (pairUriFile is not null)
{
    PairingInvitation invitation = pairing.Open();
    var uri = new PairingUri(["127.0.0.1"], server.Port, identity.DeviceId, invitation.Token, "Interop Host");
    string temporary = pairUriFile + ".tmp";
    await File.WriteAllTextAsync(temporary, uri.ToUriString());
    File.Move(temporary, pairUriFile, overwrite: true); // atomic: the client never reads half a URI
    Console.WriteLine("PAIRING_OPEN");
}

using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(lifetimeSeconds));
Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    stop.Cancel();
};

try
{
    await Task.Delay(Timeout.Infinite, stop.Token);
}
catch (OperationCanceledException)
{
    Console.WriteLine("STOPPING");
}

return 0;

/// <summary>Stand-in for DPAPI on non-Windows CI runners. The harness identity is throwaway.</summary>
internal sealed class PassThroughProtector : ISecretProtector
{
    public byte[] Protect(byte[] plaintext) => plaintext;

    public byte[] Unprotect(byte[] protectedData) => protectedData;
}
