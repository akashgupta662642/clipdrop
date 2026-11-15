package io.github.akashgupta662642.clipdrop.ui

import androidx.annotation.StringRes
import io.github.akashgupta662642.clipdrop.R
import io.github.akashgupta662642.clipdrop.engine.ConnectFailure

/** The user-facing explanation for a connection or pairing failure (PROTOCOL.md §10.3). */
@StringRes
fun ConnectFailure.message(wrongNetwork: Boolean = false): Int = when (this) {
    ConnectFailure.UNREACHABLE -> if (wrongNetwork) R.string.failure_wrong_network else R.string.failure_unreachable
    ConnectFailure.REFUSED -> R.string.failure_refused
    ConnectFailure.PIN_MISMATCH -> R.string.failure_pin_mismatch
    ConnectFailure.TLS_FAILED -> R.string.failure_tls
    ConnectFailure.PROTOCOL_ERROR -> R.string.failure_protocol
    ConnectFailure.UNSUPPORTED_VERSION -> R.string.failure_version
    ConnectFailure.TOKEN_EXPIRED -> R.string.failure_expired
    ConnectFailure.TOKEN_INVALID, ConnectFailure.TOO_MANY_ATTEMPTS -> R.string.failure_invalid_token
    ConnectFailure.PAIRING_CLOSED -> R.string.failure_pairing_closed
    ConnectFailure.FORGOTTEN_BY_HOST -> R.string.failure_forgotten
}
