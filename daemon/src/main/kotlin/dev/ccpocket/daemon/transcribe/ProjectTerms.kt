package dev.ccpocket.daemon.transcribe

import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The project vocabulary the daemon can read off a workdir without running anything: the current git branch, then
 * the top-level entry names (hidden ones skipped) in sorted order. Spoken identifiers are exactly the words speech
 * recognition gets wrong, so two consumers bias toward the same list: whisper's initial prompt
 * ([WhisperTranscriber.buildPrompt]) and the transcript refiner's glossary ([RefineGlossary]).
 *
 * Never throws: an unreadable HEAD (detached, no repo) or directory just contributes nothing.
 */
object ProjectTerms {
    fun of(workdir: Path): List<String> {
        val terms = LinkedHashSet<String>()
        runCatching {
            val head = workdir.resolve(".git/HEAD").readText().trim()
            head.substringAfter("ref: refs/heads/", "").takeIf { it.isNotBlank() }?.let { terms.add(it) }
        }
        runCatching {
            workdir.listDirectoryEntries()
                .map { it.name }
                .filterNot { it.startsWith(".") }
                .sorted()
                .forEach { terms.add(it) }
        }
        return terms.toList()
    }
}
