package dev.macodes.crypto

import java.security.SecureRandom

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
