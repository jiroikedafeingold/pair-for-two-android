package com.jirofeingold.pairfortwo.core

import kotlinx.serialization.json.Json

/**
 * The wire protocol version this build speaks, announced in [GameMessage.Hello].
 *
 * A peer that announces nothing is an old iOS build speaking the pre-v1 derived-`Codable`
 * format. Android never speaks that format — it has no legacy peers — so it only ever needs
 * to *announce* v1, not read the old shape. See PROTOCOL.md.
 */
const val WIRE_PROTOCOL_VERSION = 1

/**
 * The single JSON configuration used for everything that crosses the wire.
 *
 * Every setting here is load-bearing against PROTOCOL.md:
 * - `classDiscriminator = "t"` produces the internally-tagged `{"t":"hello",…}` envelope,
 *   rather than kotlinx's default `"type"` key.
 * - `explicitNulls = false` omits absent optionals instead of emitting `null`.
 * - `encodeDefaults = true` so fields that happen to equal their default — `scoreLog`, and
 *   `protocol` in `hello` — are still written, matching what iOS sends.
 * - `ignoreUnknownKeys = true` so a newer peer adding a field doesn't break this build.
 */
val PairWireJson: Json = Json {
    classDiscriminator = "t"
    explicitNulls = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}
