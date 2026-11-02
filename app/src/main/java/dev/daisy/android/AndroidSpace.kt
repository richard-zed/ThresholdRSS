package dev.daisy.android

import dev.daisy.core.FileSpace
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * The [FileSpace] adapter for Android, rooted at a directory from
 * `context.filesDir`.
 *
 * This is the only Android-specific code in the persistence path, which is why
 * it is a class and not an interface layer. It diverges from the [FileSpace]
 * default in one respect:
 *
 *  - Writes go through a temp file and an atomic rename. The interface's
 *    default is a plain write, and on a phone that default is wrong: Android
 *    kills backgrounded processes without warning and a truncated meta.json
 *    loses the whole feed list. `renameTo` within one directory is atomic on
 *    every Android filesystem in practice (ext4, f2fs), so a kill mid-write
 *    leaves either the old file or the new one.
 *
 *  - `filesDir` is app-private and backed up, so no permission is needed and no
 *    other app can read it. That also means there is nothing to migrate from
 *    external storage, which Daisy never used.
 *
 * Paths are relative and joined under the root. Nothing here resolves `..` by
 * hand because nothing upstream builds a path from user data -- the store's
 * paths are literals plus numeric feed ids (see Store.feedPath).
 */
class AndroidSpace(root: File) : FileSpace {

    private val root: File = root.also { it.mkdirs() }

    override fun read(path: String): String? {
        val f = File(this.root, path)
        return if (f.isFile) {
            f.readText(StandardCharsets.UTF_8)
        } else {
            null
        }
    }

    override fun write(path: String, content: String) {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { out ->
            out.write(content.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
    }

    override fun writeAtomically(path: String, content: String) {
        val f = File(root, path)
        val dir = f.parentFile ?: root
        dir.mkdirs()

        // Same directory, so the rename cannot cross a filesystem boundary.
        val tmp = File(dir, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(StandardCharsets.UTF_8))
            out.flush()
            // fsync before rename. Without it the rename can land while the
            // bytes are still in the page cache, which is exactly the
            // half-written file this method exists to prevent.
            out.fd.sync()
        }

        if (!tmp.renameTo(f)) {
            // renameTo returns false rather than throwing, and the reasons are
            // not distinguishable. Falling back to an in-place write is worse
            // than the atomic path but better than losing the write entirely.
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
    }

    override fun delete(path: String) {
        File(root, path).delete()
    }

    override fun list(dir: String): List<String> {
        val names = File(root, dir).list()
        return names?.toList().orEmpty()
    }
}
