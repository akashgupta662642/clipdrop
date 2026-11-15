using System.Buffers.Text;
using System.Security.Cryptography;
using ClipDrop.Core.Protocol;

namespace ClipDrop.Core.Pairing;

/// <summary>Outcome of one PAIR_REQUEST (PROTOCOL.md §10.1).</summary>
public enum PairingAttempt
{
    Success,
    PairingClosed,
    ExpiredToken,
    InvalidToken,
    TooManyAttempts,
}

public static class PairingAttempts
{
    /// <summary>The PAIR_RESULT reason for a failed attempt.</summary>
    public static string ToWireReason(this PairingAttempt attempt) => attempt switch
    {
        PairingAttempt.PairingClosed => "pairing_closed",
        PairingAttempt.ExpiredToken => "expired_token",
        PairingAttempt.InvalidToken => "invalid_token",
        PairingAttempt.TooManyAttempts => "too_many_attempts",
        _ => throw new ArgumentOutOfRangeException(nameof(attempt), "Success has no failure reason."),
    };
}

/// <summary>A pairing token on offer: what the QR code shows, and when it stops working.</summary>
public sealed record PairingInvitation(string Token, DateTimeOffset ExpiresAt)
{
    public override string ToString() => $"PairingInvitation(Token = <redacted>, ExpiresAt = {ExpiresAt:O})";
}

/// <summary>
/// The host's pairing mode (PROTOCOL.md §10.1): one cryptographically random, single-use token that
/// expires after <see cref="ProtocolConstants.PairingTokenTtl"/>. Pairing mode closes after a success,
/// on expiry, after <see cref="ProtocolConstants.PairingMaxFailures"/> wrong tokens, or on request.
/// Thread-safe.
/// </summary>
public sealed class PairingCoordinator(TimeProvider timeProvider)
{
    /// <summary>
    /// How long after expiry TLS still admits unknown clients, so a late phone gets a precise
    /// <c>expired_token</c> instead of an opaque TLS failure. No pairing can succeed in this window.
    /// </summary>
    public static readonly TimeSpan ExpiredGrace = ProtocolConstants.PairingSessionTimeout;

    private readonly Lock _lock = new();
    private byte[]? _token;
    private DateTimeOffset _expiresAt;
    private int _failures;
    private int _unpairedSessions;

    /// <summary>Raised (outside the lock) whenever pairing mode opens or closes.</summary>
    public event EventHandler? StateChanged;

    public bool IsOpen
    {
        get
        {
            lock (_lock)
            {
                return _token is not null;
            }
        }
    }

    /// <summary>Opens pairing mode with a fresh token, replacing any previous one.</summary>
    public PairingInvitation Open()
    {
        byte[] token = RandomNumberGenerator.GetBytes(ProtocolConstants.PairingTokenBytes);
        PairingInvitation invitation;
        lock (_lock)
        {
            ClearToken();
            _token = token;
            _expiresAt = timeProvider.GetUtcNow() + ProtocolConstants.PairingTokenTtl;
            _failures = 0;
            invitation = new PairingInvitation(Base64Url.EncodeToString(token), _expiresAt);
        }

        StateChanged?.Invoke(this, EventArgs.Empty);
        return invitation;
    }

    /// <summary>Closes pairing mode. The current token stops working.</summary>
    public void Close()
    {
        bool changed;
        lock (_lock)
        {
            changed = _token is not null;
            ClearToken();
        }

        if (changed)
        {
            StateChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    /// <summary>Whether TLS may accept a client certificate that isn't paired (§4.3).</summary>
    public bool AcceptsUnknownClients
    {
        get
        {
            lock (_lock)
            {
                return _token is not null && timeProvider.GetUtcNow() < _expiresAt + ExpiredGrace;
            }
        }
    }

    /// <summary>Admits one more unpaired connection, up to <see cref="ProtocolConstants.MaxPairingConnections"/> (§9.3).</summary>
    public bool TryBeginUnpairedSession()
    {
        lock (_lock)
        {
            if (_unpairedSessions >= ProtocolConstants.MaxPairingConnections)
            {
                return false;
            }

            _unpairedSessions++;
            return true;
        }
    }

    public void EndUnpairedSession()
    {
        lock (_lock)
        {
            _unpairedSessions = Math.Max(0, _unpairedSessions - 1);
        }
    }

    /// <summary>Checks a PAIR_REQUEST token in the order §10.1 prescribes.</summary>
    public PairingAttempt TryPair(string token)
    {
        ArgumentNullException.ThrowIfNull(token);
        PairingAttempt result;
        bool closed = false;
        lock (_lock)
        {
            if (_token is null)
            {
                return PairingAttempt.PairingClosed;
            }

            if (timeProvider.GetUtcNow() >= _expiresAt)
            {
                result = PairingAttempt.ExpiredToken;
                closed = true;
            }
            else if (Matches(token))
            {
                result = PairingAttempt.Success;
                closed = true; // single use
            }
            else
            {
                _failures++;
                closed = _failures >= ProtocolConstants.PairingMaxFailures;
                result = closed ? PairingAttempt.TooManyAttempts : PairingAttempt.InvalidToken;
            }

            if (closed)
            {
                ClearToken();
            }
        }

        if (closed)
        {
            StateChanged?.Invoke(this, EventArgs.Empty);
        }

        return result;
    }

    private bool Matches(string token)
    {
        if (!Validation.IsPairingToken(token))
        {
            return false;
        }

        byte[] candidate = Base64Url.DecodeFromChars(token);
        return candidate.Length == _token!.Length && CryptographicOperations.FixedTimeEquals(candidate, _token);
    }

    private void ClearToken()
    {
        if (_token is not null)
        {
            CryptographicOperations.ZeroMemory(_token);
        }

        _token = null;
    }
}
