using System.Buffers.Text;
using ClipDrop.Core.Pairing;
using ClipDrop.Core.Protocol;
using Microsoft.Extensions.Time.Testing;

namespace ClipDrop.Core.Tests.Pairing;

public class PairingCoordinatorTests
{
    private readonly FakeTimeProvider _time = new(DateTimeOffset.Parse("2026-11-15T12:00:00Z", System.Globalization.CultureInfo.InvariantCulture));
    private readonly PairingCoordinator _pairing;

    public PairingCoordinatorTests()
    {
        _pairing = new PairingCoordinator(_time);
    }

    [Fact]
    public void Tokens_are_16_random_bytes_valid_for_two_minutes()
    {
        PairingInvitation first = _pairing.Open();
        PairingInvitation second = _pairing.Open();

        Assert.True(Validation.IsPairingToken(first.Token));
        Assert.Equal(16, Base64Url.DecodeFromChars(first.Token).Length);
        Assert.NotEqual(first.Token, second.Token);
        Assert.Equal(_time.GetUtcNow() + TimeSpan.FromMinutes(2), second.ExpiresAt);
    }

    [Fact]
    public void A_correct_token_pairs_once_and_closes_pairing_mode()
    {
        string token = _pairing.Open().Token;

        Assert.Equal(PairingAttempt.Success, _pairing.TryPair(token));
        Assert.False(_pairing.IsOpen);
        Assert.Equal(PairingAttempt.PairingClosed, _pairing.TryPair(token));
    }

    [Fact]
    public void Opening_again_replaces_the_previous_token()
    {
        string old = _pairing.Open().Token;
        string current = _pairing.Open().Token;

        Assert.Equal(PairingAttempt.InvalidToken, _pairing.TryPair(old));
        Assert.Equal(PairingAttempt.Success, _pairing.TryPair(current));
    }

    [Fact]
    public void An_expired_token_is_refused_and_closes_pairing_mode()
    {
        string token = _pairing.Open().Token;
        _time.Advance(TimeSpan.FromMinutes(2));

        Assert.Equal(PairingAttempt.ExpiredToken, _pairing.TryPair(token));
        Assert.False(_pairing.IsOpen);
    }

    [Fact]
    public void Five_wrong_tokens_close_pairing_mode()
    {
        string token = _pairing.Open().Token;
        for (int i = 0; i < 4; i++)
        {
            Assert.Equal(PairingAttempt.InvalidToken, _pairing.TryPair("AAAAAAAAAAAAAAAAAAAAAA"));
        }

        Assert.Equal(PairingAttempt.TooManyAttempts, _pairing.TryPair("not even base64url!"));
        Assert.False(_pairing.IsOpen);
        Assert.Equal(PairingAttempt.PairingClosed, _pairing.TryPair(token));
    }

    [Fact]
    public void Unknown_clients_are_admitted_only_while_open_plus_a_short_grace()
    {
        Assert.False(_pairing.AcceptsUnknownClients);

        _pairing.Open();
        Assert.True(_pairing.AcceptsUnknownClients);

        _time.Advance(TimeSpan.FromMinutes(2) + TimeSpan.FromSeconds(29));
        Assert.True(_pairing.AcceptsUnknownClients, "a late phone still gets a precise expired_token");

        _time.Advance(TimeSpan.FromSeconds(1));
        Assert.False(_pairing.AcceptsUnknownClients);
    }

    [Fact]
    public void At_most_four_unpaired_connections_at_once()
    {
        for (int i = 0; i < 4; i++)
        {
            Assert.True(_pairing.TryBeginUnpairedSession());
        }

        Assert.False(_pairing.TryBeginUnpairedSession());
        _pairing.EndUnpairedSession();
        Assert.True(_pairing.TryBeginUnpairedSession());
    }

    [Fact]
    public void State_changes_are_announced()
    {
        int changes = 0;
        _pairing.StateChanged += (_, _) => changes++;
        string token = _pairing.Open().Token;
        _pairing.TryPair(token);
        _pairing.Close(); // already closed: no event
        Assert.Equal(2, changes);
    }

    [Fact]
    public void The_invitation_hides_its_token_in_ToString()
    {
        PairingInvitation invitation = _pairing.Open();
        Assert.DoesNotContain(invitation.Token, invitation.ToString(), StringComparison.Ordinal);
    }
}
