using OpusVoice.Qr.Core.Rtp;
using OpusVoice.Qr.Pinhole;
using Pinhole;

namespace OpusVoice.Qr.Core.Tests;

/// <summary>
/// The Voice tab's Pinhole transport, exercised against a real in-process listener on this
/// machine: dial, RTP round-trip, typed failures, and the path/snapshot surface the UI polls.
/// These tests use the network (STUN/relay probes at bind; the connection itself is a local
/// direct punch) — that is the point: they fail if the transport stops working for real.
/// </summary>
public sealed class PinholeTransportTests
{
    private static readonly TimeSpan TestTimeout = TimeSpan.FromSeconds(30);

    [Fact]
    public async Task Dialing_The_Listener_Round_Trips_Rtp_Packets()
    {
        await using var listener = await PinholeVoiceListener.StartAsync();
        var acceptTask = listener.AcceptAsync();

        PinholeDialOutcome dial = await PinholeVoiceTransport.TryConnectAsync(listener.ConnectionString, TestToken(TestTimeout));
        Assert.True(dial.Transport is not null, $"dial failed: {dial.Failure} — {dial.ErrorMessage}");
        await using PinholePeerSession peer = await acceptTask.WaitAsync(TestTimeout);

        var packetizer = new RtpPacketizer(0x53535333);
        var sent = new List<byte[]>();
        for (int i = 0; i < 10; i++)
        {
            var payload = new byte[120 + i];
            Random.Shared.NextBytes(payload);
            byte[] packet = packetizer.NextPacket(payload);
            sent.Add(packet);
            dial.Transport.Send(packet);
        }

        int received = 0;
        using var done = new CancellationTokenSource(TestTimeout);
        try
        {
            await foreach (ReadOnlyMemory<byte> datagram in peer.ReadAllAsync(done.Token))
            {
                ParsedRtpPacket parsed = RtpPacket.Parse(datagram.Span);
                Assert.Equal(2, parsed.Version);
                Assert.Equal(RtpPacket.OpusPayloadType, parsed.PayloadType);
                Assert.Equal(sent[received], datagram.ToArray());
                received++;
                if (received == sent.Count) break;
            }
        }
        catch (OperationCanceledException)
        {
        }
        Assert.Equal(sent.Count, received);

        dial.Transport.Dispose();
    }

    [Fact]
    public async Task Dialing_Garbage_Returns_A_Typed_Failure_Not_A_Throw()
    {
        PinholeDialOutcome dial = await PinholeVoiceTransport.TryConnectAsync("definitely not a ticket", TestToken(TestTimeout));
        Assert.Null(dial.Transport);
        Assert.Equal(PinholeConnectFailure.InvalidConnectionString, dial.Failure);
        Assert.NotNull(dial.ErrorMessage);
    }

    [Fact]
    public async Task Connected_Transport_Reports_Its_Path_And_Peer()
    {
        await using var listener = await PinholeVoiceListener.StartAsync();
        var acceptTask = listener.AcceptAsync();

        PinholeDialOutcome dial = await PinholeVoiceTransport.TryConnectAsync(listener.ConnectionString, TestToken(TestTimeout));
        Assert.NotNull(dial.Transport);
        await using PinholePeerSession peer = await acceptTask.WaitAsync(TestTimeout);

        // Same machine: the punch lands on a direct loopback path every time.
        Assert.StartsWith("direct", dial.Transport.Snapshot().Path, StringComparison.OrdinalIgnoreCase);
        Assert.StartsWith("0x", dial.Transport.PeerId);
        Assert.StartsWith("direct", peer.Path, StringComparison.OrdinalIgnoreCase);

        dial.Transport.Dispose();
    }

    private static CancellationToken TestToken(TimeSpan timeout)
    {
        var cts = new CancellationTokenSource(timeout);
        return cts.Token;
    }
}
