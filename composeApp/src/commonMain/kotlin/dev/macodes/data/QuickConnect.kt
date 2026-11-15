package dev.macodes.data

import kotlinx.serialization.Serializable

/** Step sequence inside the single Smart Quick Connect wizard dialog. */
enum class QuickConnectStep {
    PROTOCOL,
    USERNAME,
    PASSWORD,
}

@Serializable
data class QuickConnectHistoryEntry(
    val protocol: ConnectionMode,
    val host: String,
    val port: Int = 22,
    val moshUdpPort: Int = 0,
    val moshServerCommand: String = "",
    val username: String,
    val lastConnectedAt: Long,
    val system: String = "",
    val openSftp: Boolean = false,
)

data class QuickConnectTarget(
    val rawInput: String,
    val protocol: ConnectionMode?,
    val host: String,
    val sshPort: Int = 22,
    val sshPortExplicit: Boolean = false,
    val moshUdpPort: Int = 0,
    val moshServerCommand: String = "",
    val username: String? = null,
    val openSftp: Boolean = false,
)

sealed interface QuickConnectParseError {
    data object EmptyInput : QuickConnectParseError

    data object ShellMetacharacters : QuickConnectParseError

    data class MissingFlagValue(
        val flag: String,
    ) : QuickConnectParseError

    data class UnsupportedFlag(
        val flag: String,
    ) : QuickConnectParseError

    data class InvalidPort(
        val value: String,
    ) : QuickConnectParseError

    data object InvalidHost : QuickConnectParseError

    data object UnexpectedArguments : QuickConnectParseError
}

sealed interface QuickConnectParseResult {
    data class Success(
        val target: QuickConnectTarget,
    ) : QuickConnectParseResult

    data class Error(
        val error: QuickConnectParseError,
    ) : QuickConnectParseResult
}

internal fun quickConnectHostId(
    protocol: ConnectionMode,
    host: String,
    port: Int,
): String = "quickconnect:${protocol.name.lowercase()}:$host:$port"

/**
 * Pure parser for Quick Connect commands, URIs, `user@host`, and bare IP/host inputs.
 * Never executes the input string as a shell command.
 */
object QuickConnectParser {
    private val shellMetaRegex = Regex("[;&|`<>\\n\\r]|\\$\\(")

    fun parse(rawInput: String): QuickConnectParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return QuickConnectParseResult.Error(QuickConnectParseError.EmptyInput)
        if (shellMetaRegex.containsMatchIn(trimmed)) {
            return QuickConnectParseResult.Error(QuickConnectParseError.ShellMetacharacters)
        }

        val tokens = tokenize(trimmed) ?: return QuickConnectParseResult.Error(QuickConnectParseError.ShellMetacharacters)
        if (tokens.isEmpty()) return QuickConnectParseResult.Error(QuickConnectParseError.EmptyInput)

        var protocol: ConnectionMode? = null
        var openSftp = false
        var idx = 0
        when (tokens[0].lowercase()) {
            "ssh" -> {
                protocol = ConnectionMode.SSH
                idx = 1
            }
            "mosh" -> {
                protocol = ConnectionMode.MOSH
                idx = 1
            }
            "sftp" -> {
                protocol = ConnectionMode.SSH
                openSftp = true
                idx = 1
            }
        }

        if (idx >= tokens.size) return QuickConnectParseResult.Error(QuickConnectParseError.InvalidHost)

        var sshPort = 22
        var sshPortExplicit = false
        var moshUdpPort = 0
        var moshServerCommand = ""
        var flagUsername: String? = null
        val positional = mutableListOf<String>()

        while (idx < tokens.size) {
            val tok = tokens[idx]
            when {
                tok == "-p" || (openSftp && tok == "-P") || (protocol == ConnectionMode.MOSH && tok == "--port") -> {
                    val value =
                        tokens.getOrNull(idx + 1)
                            ?: return QuickConnectParseResult.Error(QuickConnectParseError.MissingFlagValue(tok))
                    val parsedPort =
                        parsePort(value)
                            ?: return QuickConnectParseResult.Error(QuickConnectParseError.InvalidPort(value))
                    if (protocol == ConnectionMode.MOSH) {
                        moshUdpPort = parsedPort
                    } else {
                        sshPort = parsedPort
                        sshPortExplicit = true
                    }
                    idx += 2
                }
                protocol == ConnectionMode.MOSH && tok.startsWith("--port=") -> {
                    val value = tok.removePrefix("--port=").trim()
                    if (value.isEmpty()) {
                        return QuickConnectParseResult.Error(QuickConnectParseError.MissingFlagValue("--port"))
                    }
                    val parsedPort =
                        parsePort(value)
                            ?: return QuickConnectParseResult.Error(QuickConnectParseError.InvalidPort(value))
                    moshUdpPort = parsedPort
                    idx += 1
                }
                (tok.startsWith("-p") || (openSftp && tok.startsWith("-P"))) && tok.length > 2 && tok[2].isDigit() -> {
                    val value = tok.substring(2)
                    val parsedPort =
                        parsePort(value)
                            ?: return QuickConnectParseResult.Error(QuickConnectParseError.InvalidPort(value))
                    if (protocol == ConnectionMode.MOSH) {
                        moshUdpPort = parsedPort
                    } else {
                        sshPort = parsedPort
                        sshPortExplicit = true
                    }
                    idx += 1
                }
                tok == "-l" -> {
                    val value =
                        tokens.getOrNull(idx + 1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("-") }
                            ?: return QuickConnectParseResult.Error(QuickConnectParseError.MissingFlagValue("-l"))
                    flagUsername = value
                    idx += 2
                }
                tok.startsWith("-l") && tok.length > 2 -> {
                    flagUsername = tok.substring(2)
                    idx += 1
                }
                protocol == ConnectionMode.MOSH && tok == "--server" -> {
                    val value =
                        tokens.getOrNull(idx + 1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("-") }
                            ?: return QuickConnectParseResult.Error(QuickConnectParseError.MissingFlagValue("--server"))
                    moshServerCommand = value
                    idx += 2
                }
                protocol == ConnectionMode.MOSH && tok.startsWith("--server=") -> {
                    val value = tok.removePrefix("--server=").trim()
                    if (value.isEmpty()) {
                        return QuickConnectParseResult.Error(QuickConnectParseError.MissingFlagValue("--server"))
                    }
                    moshServerCommand = value
                    idx += 1
                }
                protocol == ConnectionMode.MOSH && tok.startsWith("--ssh=") -> {
                    val sshArg = tok.removePrefix("--ssh=")
                    val portFromSsh =
                        extractPortFromMoshSshArg(sshArg)
                            ?: return QuickConnectParseResult.Error(QuickConnectParseError.UnsupportedFlag(tok))
                    sshPort = portFromSsh
                    sshPortExplicit = true
                    idx += 1
                }
                tok.startsWith("-") -> {
                    return QuickConnectParseResult.Error(QuickConnectParseError.UnsupportedFlag(tok))
                }
                else -> {
                    positional.add(tok)
                    idx += 1
                }
            }
        }

        if (positional.isEmpty()) return QuickConnectParseResult.Error(QuickConnectParseError.InvalidHost)
        if (positional.size > 1) return QuickConnectParseResult.Error(QuickConnectParseError.UnexpectedArguments)

        val endpointResult = parseEndpoint(positional[0], initialProtocol = protocol, initialOpenSftp = openSftp)
        if (endpointResult is QuickConnectParseResult.Error) return endpointResult
        val endpoint = (endpointResult as QuickConnectParseResult.Success).target

        val finalProtocol = protocol ?: endpoint.protocol
        val finalOpenSftp = openSftp || endpoint.openSftp
        val finalUsername = endpoint.username ?: flagUsername
        val finalSshPort = if (sshPortExplicit) sshPort else endpoint.sshPort
        val finalPortExplicit = sshPortExplicit || endpoint.sshPortExplicit

        return QuickConnectParseResult.Success(
            QuickConnectTarget(
                rawInput = rawInput,
                protocol = finalProtocol,
                host = endpoint.host,
                sshPort = finalSshPort,
                sshPortExplicit = finalPortExplicit,
                moshUdpPort = moshUdpPort,
                moshServerCommand = moshServerCommand,
                username = finalUsername,
                openSftp = finalOpenSftp,
            ),
        )
    }

    fun stepsFor(target: QuickConnectTarget): List<QuickConnectStep> =
        buildList {
            if (target.protocol == null) add(QuickConnectStep.PROTOCOL)
            if (target.username == null) add(QuickConnectStep.USERNAME)
            add(QuickConnectStep.PASSWORD)
        }

    fun fromHistory(entry: QuickConnectHistoryEntry): QuickConnectTarget =
        QuickConnectTarget(
            rawInput = "${entry.username}@${entry.host}",
            protocol = entry.protocol,
            host = entry.host,
            sshPort = entry.port,
            sshPortExplicit = true,
            moshUdpPort = entry.moshUdpPort,
            moshServerCommand = entry.moshServerCommand,
            username = entry.username,
            openSftp = entry.openSftp,
        )

    private fun parseEndpoint(
        token: String,
        initialProtocol: ConnectionMode?,
        initialOpenSftp: Boolean = false,
    ): QuickConnectParseResult {
        var remaining = token.trim()
        var uriProtocol: ConnectionMode? = null
        var uriOpenSftp = false
        val schemeSplit = remaining.indexOf("://")
        if (schemeSplit >= 0) {
            val scheme = remaining.substring(0, schemeSplit).lowercase()
            when (scheme) {
                "ssh" -> uriProtocol = ConnectionMode.SSH
                "mosh" -> uriProtocol = ConnectionMode.MOSH
                "sftp" -> {
                    uriProtocol = ConnectionMode.SSH
                    uriOpenSftp = true
                }
                else -> return QuickConnectParseResult.Error(QuickConnectParseError.UnsupportedFlag("$scheme://"))
            }
            if ((initialProtocol != null && initialProtocol != uriProtocol) ||
                (initialOpenSftp && uriProtocol != ConnectionMode.SSH)
            ) {
                return QuickConnectParseResult.Error(QuickConnectParseError.UnexpectedArguments)
            }
            remaining = remaining.substring(schemeSplit + 3).substringBefore('/')
        }

        var username: String? = null
        val atIdx = remaining.lastIndexOf('@')
        if (atIdx >= 0) {
            val userPart = remaining.substring(0, atIdx).trim()
            if (userPart.isEmpty()) return QuickConnectParseResult.Error(QuickConnectParseError.InvalidHost)
            username = userPart
            remaining = remaining.substring(atIdx + 1).trim()
        }

        if (remaining.isEmpty()) return QuickConnectParseResult.Error(QuickConnectParseError.InvalidHost)

        var hostPart = remaining
        var port = 22
        var portExplicit = false

        if (remaining.startsWith("[")) {
            val closeBracket = remaining.indexOf(']')
            if (closeBracket <= 1) return QuickConnectParseResult.Error(QuickConnectParseError.InvalidHost)
            hostPart = remaining.substring(1, closeBracket)
            val after = remaining.substring(closeBracket + 1)
            if (after.isNotEmpty()) {
                if (!after.startsWith(":")) return QuickConnectParseResult.Error(QuickConnectParseError.InvalidHost)
                val portStr = after.substring(1)
                port = parsePort(portStr) ?: return QuickConnectParseResult.Error(QuickConnectParseError.InvalidPort(portStr))
                portExplicit = true
            }
        } else {
            val colonCount = remaining.count { it == ':' }
            if (colonCount == 1) {
                val colonIdx = remaining.indexOf(':')
                hostPart = remaining.substring(0, colonIdx)
                val portStr = remaining.substring(colonIdx + 1)
                port = parsePort(portStr) ?: return QuickConnectParseResult.Error(QuickConnectParseError.InvalidPort(portStr))
                portExplicit = true
            } else if (colonCount > 1 && !isValidIpv6(remaining)) {
                return QuickConnectParseResult.Error(QuickConnectParseError.InvalidHost)
            }
        }

        if (!isValidHost(hostPart)) {
            return QuickConnectParseResult.Error(QuickConnectParseError.InvalidHost)
        }

        return QuickConnectParseResult.Success(
            QuickConnectTarget(
                rawInput = token,
                protocol = uriProtocol,
                host = hostPart,
                sshPort = port,
                sshPortExplicit = portExplicit,
                username = username,
                openSftp = uriOpenSftp,
            ),
        )
    }

    private fun extractPortFromMoshSshArg(arg: String): Int? {
        val parts = tokenize(arg) ?: return null
        if (parts.isEmpty() || parts[0].lowercase() != "ssh") return null
        var i = 1
        while (i < parts.size) {
            when {
                parts[i] == "-p" -> {
                    val v = parts.getOrNull(i + 1) ?: return null
                    return parsePort(v)
                }
                parts[i].startsWith("-p") && parts[i].length > 2 -> {
                    return parsePort(parts[i].substring(2))
                }
                else -> return null
            }
        }
        return null
    }

    private fun tokenize(input: String): List<String>? {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in input) {
            when {
                quote != null -> {
                    if (ch == quote) {
                        quote = null
                    } else {
                        cur.append(ch)
                    }
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (cur.isNotEmpty()) {
                        out.add(cur.toString())
                        cur.setLength(0)
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (quote != null) return null
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun parsePort(raw: String): Int? = raw.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.equals("localhost", ignoreCase = true)) return true
        if (isValidIpv4(host) || isValidIpv6(host)) return true
        if ('.' !in host) return false
        val labels = host.split('.')
        if (labels.any { !isValidDomainLabel(it) }) return false
        // Reject malformed numeric hosts such as "999.999.999.999" or "1.2.3"
        return labels.last().any { it in 'a'..'z' || it in 'A'..'Z' }
    }

    private fun isValidIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.length in 1..3 &&
                part.all(Char::isDigit) &&
                (part.toIntOrNull()?.let { it in 0..255 } == true)
        }
    }

    private fun isValidIpv6(host: String): Boolean {
        if (':' !in host || host.startsWith('.') || host.endsWith('.')) return false
        val halves = host.split("::")
        if (halves.size > 2) return false
        val groups =
            if (halves.size == 1) {
                host.split(':').also { if (it.size != 8) return false }
            } else {
                val left = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
                val right = if (halves[1].isEmpty()) emptyList() else halves[1].split(':')
                if (left.size + right.size >= 8) return false
                left + right
            }
        return groups.all { group ->
            group.length in 1..4 &&
                group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
    }

    private fun isValidDomainLabel(label: String): Boolean =
        label.length in 1..63 &&
            label.first().isAsciiAlnum() &&
            label.last().isAsciiAlnum() &&
            label.all { it.isAsciiAlnum() || it == '-' }

    private fun Char.isAsciiAlnum(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
