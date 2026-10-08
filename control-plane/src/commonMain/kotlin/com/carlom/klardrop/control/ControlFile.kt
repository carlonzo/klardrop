package com.carlom.klardrop.control

/**
 * Publishes `control.json` (port, token, [API_VERSION], capabilities) so clients can find and
 * authenticate against this host.
 *
 * Fails closed: implementations must throw when the file cannot be created or protected, so
 * [ControlPlane.start] never leaves a bound listener whose token was never published.
 */
internal expect fun writeControlFile(port: Int, token: String, capabilities: List<String>)

internal expect fun deleteControlFile(expectedToken: String? = null)

expect fun resolveControlFilePath(): String?

/**
 * True when this host may legitimately publish no control file at all.
 *
 * Only Android: its clients reach the loopback port through `adb forward`, which is already an
 * authenticated channel, so there is no local user to hand a token to and
 * [resolveControlFilePath] is null there by design. Everywhere else a null path means the host
 * has no writable private location — a stripped sandbox, no HOME — and starting anyway would
 * publish a loopback listener that accepts *unauthenticated* requests against this device's
 * identity and files. That is the opposite of failing closed, so [ControlPlane.start] refuses
 * instead.
 */
internal expect val controlFileOptional: Boolean
