package dev.macodes.data

import kotlinx.serialization.Serializable

/**
 * Tag Group: Global predefined tags (selection from the tag group when creating a snippet; cannot be entered freely—
 * Free-form characters tend to become fragmented: multiple ways to express the same meaning, making it impossible to manage them consistently.
 * After a tag is renamed, all snippets that reference it are automatically updated; when a tag is deleted, references to it are cascadingly removed from snippets.
 */
@Serializable
data class TagGroup(
    val id: String,
    val name: String,
)

/**
 * Command snippets: A library of commonly used commands that can be reused across hosts.
 * Terminal keyboard toolbar: Press “{}” to insert the current input line with a single keystroke (without pressing Enter; manually press Enter after adding parameters);
 * Tags are passed via [tagIds] in [TagGroup].
 */
@Serializable
data class Snippet(
    val id: String,
    val name: String,
    val content: String,
    val tagIds: List<String> = emptyList(),
    /** Creation/update timestamps (in milliseconds): The list is sorted in reverse chronological order, with the most recent edit at the top. */
    val updatedAt: Long = 0,
)
