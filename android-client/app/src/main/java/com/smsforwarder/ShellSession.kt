package com.smsforwarder

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Shell intégré : pas de processus externe, uniquement des commandes internes
 * bornées à des racines autorisées.
 */
class ShellSession(private val context: Context) {

    sealed class Outcome {
        class Text(val text: String, val exit: Int = 0) : Outcome()
        class Binary(val file: File, val name: String) : Outcome()
        class Failure(val message: String) : Outcome()
    }

    private val cwds = ConcurrentHashMap<String, File>()

    private val roots: List<File> by lazy {
        listOfNotNull(
            Environment.getExternalStorageDirectory(),
                      File("/sdcard"),
                      context.getExternalFilesDir(null),
                      context.filesDir
        ).map { it.canonicalFile }
    }

    private fun defaultRoot(): File = roots.firstOrNull() ?: context.filesDir

        fun cwd(session: String): File = cwds[session] ?: defaultRoot()

        fun execute(session: String, name: String, args: List<String>): Outcome {
            return try {
                when (name) {
                    "pwd" -> Outcome.Text(cwd(session).absolutePath + "\n")
                    "cd" -> cd(session, args.firstOrNull())
                    "ls" -> list(session, args)
                    "stat" -> stat(session, args.firstOrNull())
                    "cat", "head", "tail" -> read(session, name, args)
                    "download" -> download(session, args.firstOrNull())
                    "applist" -> download(session, appListPath())
                    "mkdir" -> mkdir(session, args.firstOrNull())
                    "rm" -> remove(session, args)
                    "mv" -> move(session, args, copy = false)
                    "cp" -> move(session, args, copy = true)
                    "du" -> size(session, args.firstOrNull())
                    else -> Outcome.Failure("commande inconnue: $name")
                }
            } catch (t: Throwable) {
                Outcome.Failure(t.message ?: t.javaClass.simpleName)
            }
        }

        private fun appListPath(): String =
            File(context.getExternalFilesDir(null) ?: context.filesDir, Config.APP_LIST_FILE).absolutePath

            private fun resolve(session: String, raw: String?): File {
                val value = raw?.trim().orEmpty()
                val candidate = when {
                    value.isEmpty() -> cwd(session)
                    value.startsWith("/") -> File(value)
                    else -> File(cwd(session), value)
                }
                val canonical = candidate.canonicalFile
                if (roots.none { canonical.path == it.path || canonical.path.startsWith(it.path + File.separator) }) {
                    throw IOException("hors périmètre: ${canonical.path}")
                }
                return canonical
            }

            private fun cd(session: String, target: String?): Outcome {
                val dir = resolve(session, target ?: "/")
                if (!dir.isDirectory) return Outcome.Failure("répertoire inexistant: ${dir.path}")
                    cwds[session] = dir
                    return Outcome.Text("")
            }

            private fun list(session: String, args: List<String>): Outcome {
                val target = resolve(session, args.lastOrNull { !it.startsWith("-") })
                if (!target.isDirectory) return Outcome.Failure("pas un répertoire: ${target.path}")
                    val entries = target.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
                    val out = StringBuilder()
                    entries.take(2000).forEach { entry ->
                        out.append(if (entry.isDirectory) "d" else "-")
                        out.append(' ').append(entry.length().toString().padStart(10))
                        out.append(' ').append(entry.lastModified())
                        out.append("  ").append(entry.name)
                        if (entry.isDirectory) out.append('/')
                            out.append('\n')
                    }
                    return Outcome.Text(out.toString())
            }

            private fun stat(session: String, target: String?): Outcome {
                val file = resolve(session, target)
                if (!file.exists()) return Outcome.Failure("introuvable: ${file.path}")
                    return Outcome.Text(
                        "path=${file.absolutePath}\ntype=${if (file.isDirectory) "dir" else "file"}\n" +
                        "size=${file.length()}\nmtime=${file.lastModified()}\nreadable=${file.canRead()}\n"
                    )
            }

            private fun read(session: String, mode: String, args: List<String>): Outcome {
                val file = resolve(session, args.firstOrNull { !it.startsWith("-") })
                if (!file.isFile) return Outcome.Failure("fichier introuvable: ${file.path}")
                    val max = Config.SHELL_MAX_TEXT_BYTES.toLong()
                    val bytes = file.inputStream().use { stream ->
                        if (mode == "cat") stream.readNBytes(max.toInt())
                            else stream.readNBytes(max.toInt())
                    }
                    val text = String(bytes, Charsets.UTF_8)
                    return when (mode) {
                        "head" -> Outcome.Text(text.lineSequence().take(40).joinToString("\n"))
                        "tail" -> Outcome.Text(text.lineSequence().toList().takeLast(40).joinToString("\n"))
                        else -> Outcome.Text(text)
                    }
            }

            private fun download(session: String, target: String?): Outcome {
                val file = resolve(session, target)
                if (!file.isFile) return Outcome.Failure("fichier introuvable: ${file.path}")
                    return Outcome.Binary(file, file.name)
            }

            private fun mkdir(session: String, target: String?): Outcome {
                val dir = resolve(session, target)
                return if (dir.mkdirs() || dir.isDirectory) Outcome.Text("")
                else Outcome.Failure("création impossible: ${dir.path}")
            }

            private fun remove(session: String, args: List<String>): Outcome {
                val recursive = args.any { it == "-r" || it == "-rf" }
                val file = resolve(session, args.firstOrNull { !it.startsWith("-") })
                if (file.isDirectory && recursive) file.deleteRecursively() else file.delete()
                    return Outcome.Text("")
            }

            private fun move(session: String, args: List<String>, copy: Boolean): Outcome {
                val source = resolve(session, args.getOrNull(0))
                val destination = resolve(session, args.getOrNull(1))
                if (!source.exists()) return Outcome.Failure("source absente: ${source.path}")
                    val target = if (destination.isDirectory) File(destination, source.name) else destination
                    if (copy) source.copyTo(target, overwrite = true) else source.copyTo(target, overwrite = true)
                        if (!copy) source.delete()
                            return Outcome.Text("")
            }

            private fun size(session: String, target: String?): Outcome {
                val file = resolve(session, target)
                val total = if (file.isDirectory) {
                    file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } else file.length()
                    return Outcome.Text("$total\t${file.path}\n")
            }
}
