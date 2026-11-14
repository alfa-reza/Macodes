package dev.macodes.mosh

/**
 * SSP Local State (corresponding to the protocol user stream): The stream of user input awaiting delivery to the server.
 * diff = queue suffix; subtract = discard the prefix known to the other end.
 */
internal class UserStream {
    sealed class Event {
        class Byte(
            val b: kotlin.Byte,
        ) : Event()

        class Resize(
            val width: Int,
            val height: Int,
        ) : Event()

        override fun equals(other: Any?): Boolean =
            when {
                this is Byte && other is Byte -> b == other.b
                this is Resize && other is Resize -> width == other.width && height == other.height
                else -> false
            }

        override fun hashCode(): Int =
            when (this) {
                is Byte -> b.toInt()
                is Resize -> 31 * width + height
            }
    }

    private val actions = ArrayDeque<Event>()

    val size: Int get() = actions.size

    fun pushByte(b: Byte) = actions.addLast(Event.Byte(b))

    fun pushResize(
        width: Int,
        height: Int,
    ) = actions.addLast(Event.Resize(width, height))

    /** Generate a diff starting from the fact that `existing` is a prefix of `this` (UserMessage protobuf encoding). */
    fun diffFrom(existing: UserStream): ByteArray {
        var idx = 0
        for (e in existing.actions) {
            check(idx < actions.size && actions[idx] == e) { "UserStream diff_from: 前缀不匹配" }
            idx++
        }
        val events = ArrayList<UserEventOut>()
        // Growth-based byte buffering: Avoid O(n²) copying caused by byte-by-byte "pending += b" (in large copy-and-paste scenarios)
        var buf = ByteArray(64)
        var len = 0

        fun flushKeys() {
            if (len == 0) return
            events.add(UserEventOut.Keystrokes(buf.copyOf(len)))
            len = 0
        }
        for (i in idx until actions.size) {
            when (val e = actions[i]) {
                is Event.Byte -> {
                    if (len == buf.size) buf = buf.copyOf(buf.size * 2)
                    buf[len++] = e.b
                }
                is Event.Resize -> {
                    flushKeys()
                    events.add(UserEventOut.Resize(e.width, e.height))
                }
            }
        }
        flushKeys()
        if (events.isEmpty()) return ByteArray(0)
        return encodeUserMessage(events)
    }

    /** Discard prefixes that match the prefix (parts that the other end has confirmed receipt of). */
    fun subtract(prefix: UserStream) {
        repeat(prefix.actions.size) {
            check(actions.isNotEmpty() && actions.first() == prefix.actions[it]) {
                "UserStream subtract: 前缀不匹配"
            }
            actions.removeFirst()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is UserStream || other.actions.size != actions.size) return false
        for (i in actions.indices) if (actions[i] != other.actions[i]) return false
        return true
    }

    override fun hashCode(): Int = actions.fold(1) { acc, e -> 31 * acc + e.hashCode() }

    fun copy(): UserStream {
        val c = UserStream()
        for (e in actions) c.actions.addLast(e)
        return c
    }
}
