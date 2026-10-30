package com.zipmobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.github.junrar.Junrar
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var files: List<Uri> = emptyList()
    private var folder: Uri? = null
    private var suggestedName = "archive.zip"
    private var archives: List<Uri> = emptyList()
    private var password = ""

    private val PICK_FILES = 1
    private val SAVE_ZIP = 2
    private val PICK_ARCHIVES = 3
    private val PICK_DIR = 4
    private val PICK_FOLDER = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 120, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "ZipMobile"; textSize = 32f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Compresser en ZIP  •  Extraire ZIP, 7z, RAR, TAR, GZ"; gravity = Gravity.CENTER
            setPadding(0, 16, 0, 64)
        })
        root.addView(Button(this).apply {
            text = "Compresser (fichiers ou dossier)"; textSize = 18f
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Compresser")
                    .setItems(arrayOf("Des fichiers", "Un dossier entier")) { _, which ->
                        if (which == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }, PICK_FILES)
                        else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER)
                    }.show()
            }
        })
        root.addView(Button(this).apply {
            text = "Extraire (une ou plusieurs archives)"; textSize = 18f
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, PICK_ARCHIVES)
            }
        })
        status = TextView(this).apply { textSize = 16f; setPadding(0, 64, 0, 0); gravity = Gravity.CENTER }
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun urisOf(d: Intent): List<Uri> {
        val list = mutableListOf<Uri>()
        val cd = d.clipData
        if (cd != null) for (i in 0 until cd.itemCount) list.add(cd.getItemAt(i).uri)
        else d.data?.let { list.add(it) }
        return list
    }

    private fun askThenSave() {
        askPassword("Mot de passe du ZIP (laisse vide = sans)") { pw ->
            password = pw
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, suggestedName)
            }, SAVE_ZIP)
        }
    }

    @Deprecated("simple")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        when (req) {
            PICK_FILES -> {
                files = urisOf(data); folder = null; suggestedName = "archive.zip"
                askThenSave()
            }
            PICK_FOLDER -> {
                folder = data.data; files = emptyList()
                val n = DocumentFile.fromTreeUri(this, folder!!)?.name ?: "dossier"
                suggestedName = "$n.zip"
                askThenSave()
            }
            SAVE_ZIP -> data.data?.let { compress(it) }
            PICK_ARCHIVES -> {
                archives = urisOf(data)
                askPassword("Mot de passe des archives (laisse vide = sans)") { pw ->
                    password = pw
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_DIR)
                }
            }
            PICK_DIR -> data.data?.let { extractAll(it) }
        }
    }

    private fun askPassword(title: String, next: (String) -> Unit) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Mot de passe"
        }
        AlertDialog.Builder(this).setTitle(title).setView(et).setCancelable(false)
            .setPositiveButton("OK") { _, _ -> next(et.text.toString()) }.show()
    }

    private fun nameOf(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return "fichier"
    }

    private fun work(msg: String, block: () -> String) {
        status.text = msg
        io.execute {
            val r = try { block() } catch (e: Exception) { "Erreur : ${e.message}" }
            ui.post { status.text = r }
        }
    }

    // ---------- COMPRESSION ----------
    private fun walk(d: DocumentFile, prefix: String, out: MutableList<Pair<String, Uri>>) {
        d.listFiles().forEach { f ->
            val n = f.name ?: return@forEach
            if (f.isDirectory) walk(f, "$prefix$n/", out) else out.add(Pair("$prefix$n", f.uri))
        }
    }

    private fun compress(out: Uri) = work("Compression en cours...") {
        val pw = password
        val entries = mutableListOf<Pair<String, Uri>>()
        files.forEach { entries.add(Pair(nameOf(it), it)) }
        folder?.let {
            val r = DocumentFile.fromTreeUri(this, it)!!
            walk(r, (r.name ?: "dossier") + "/", entries)
        }
        contentResolver.openOutputStream(out)!!.use { os ->
            val zos = if (pw.isEmpty()) ZipOutputStream(os) else ZipOutputStream(os, pw.toCharArray())
            zos.use {
                for ((path, uri) in entries) {
                    val p = ZipParameters().apply {
                        fileNameInZip = path
                        compressionMethod = CompressionMethod.DEFLATE
                        if (pw.isNotEmpty()) { isEncryptFiles = true; encryptionMethod = EncryptionMethod.AES }
                    }
                    zos.putNextEntry(p)
                    contentResolver.openInputStream(uri)!!.use { i -> i.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        "Terminé : ${entries.size} fichier(s) compressé(s)"
    }

    // ---------- EXTRACTION ----------
    private fun extractAll(dir: Uri) = work("Extraction en cours...") {
        val root = DocumentFile.fromTreeUri(this, dir)!!
        var ok = 0
        var total = 0
        val errs = mutableListOf<String>()
        archives.forEachIndexed { idx, a ->
            val name = nameOf(a)
            ui.post { status.text = "Extraction ${idx + 1}/${archives.size} : $name" }
            try { total += extractOne(a, name, root); ok++ } catch (e: Exception) { errs.add("$name : ${e.message}") }
        }
        var msg = "Terminé : $ok/${archives.size} archive(s), $total fichier(s)"
        if (errs.isNotEmpty()) msg += "\n\nErreurs :\n" + errs.joinToString("\n")
        msg
    }

    private fun baseName(n: String): String {
        val l = n.lowercase()
        for (e in listOf(".tar.gz", ".tar.bz2", ".tar.xz")) if (l.endsWith(e)) return n.dropLast(e.length)
        return n.substringBeforeLast('.', n)
    }

    private fun safeFile(out: File, name: String): File {
        val f = File(out, name)
        if (!f.canonicalPath.startsWith(out.canonicalPath)) throw SecurityException("Chemin invalide")
        return f
    }

    private fun extractOne(a: Uri, name: String, root: DocumentFile): Int {
        val pw = password
        val tmp = File(cacheDir, "work"); tmp.deleteRecursively(); tmp.mkdirs()
        try {
            val src = File(tmp, "in.bin")
            contentResolver.openInputStream(a)!!.use { i -> src.outputStream().use { o -> i.copyTo(o) } }
            val out = File(tmp, "out"); out.mkdirs()
            val l = name.lowercase()
            val isTar = listOf(".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz").any { l.endsWith(it) }

            when {
                l.endsWith(".7z") -> {
                    val sz = if (pw.isEmpty()) SevenZFile(src) else SevenZFile(src, pw.toCharArray())
                    sz.use { s ->
                        var e = s.nextEntry
                        while (e != null) {
                            val f = safeFile(out, e.name)
                            if (e.isDirectory) f.mkdirs() else {
                                f.parentFile?.mkdirs()
                                f.outputStream().use { o ->
                                    val buf = ByteArray(8192)
                                    while (true) { val n = s.read(buf); if (n < 0) break; o.write(buf, 0, n) }
                                }
                            }
                            e = s.nextEntry
                        }
                    }
                }
                l.endsWith(".rar") -> {
                    if (pw.isEmpty()) Junrar.extract(src, out) else Junrar.extract(src, out, pw)
                }
                isTar -> {
                    val raw = src.inputStream().buffered()
                    val stream: InputStream = when {
                        l.endsWith(".gz") || l.endsWith(".tgz") -> GzipCompressorInputStream(raw)
                        l.endsWith(".bz2") || l.endsWith(".tbz2") -> BZip2CompressorInputStream(raw)
                        l.endsWith(".xz") || l.endsWith(".txz") -> XZCompressorInputStream(raw)
                        else -> raw
                    }
                    TarArchiveInputStream(stream).use { t ->
                        var e = t.nextEntry
                        while (e != null) {
                            val f = safeFile(out, e.name)
                            if (e.isDirectory) f.mkdirs() else {
                                f.parentFile?.mkdirs()
                                f.outputStream().use { o -> t.copyTo(o) }
                            }
                            e = t.nextEntry
                        }
                    }
                }
                l.endsWith(".gz") -> {
                    GzipCompressorInputStream(src.inputStream().buffered()).use { g ->
                        File(out, baseName(name)).outputStream().use { o -> g.copyTo(o) }
                    }
                }
                else -> {
                    val z = if (pw.isEmpty()) ZipFile(src) else ZipFile(src, pw.toCharArray())
                    z.extractAll(out.path)
                }
            }

            val base = baseName(name)
            val target = root.findFile(base) ?: root.createDirectory(base)!!
            return copyTo(out, target)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun copyTo(src: File, dst: DocumentFile): Int {
        var n = 0
        src.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                n += copyTo(f, dst.findFile(f.name) ?: dst.createDirectory(f.name)!!)
            } else {
                val d = dst.createFile("application/octet-stream", f.name)!!
                contentResolver.openOutputStream(d.uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
                n++
            }
        }
        return n
    }
}
