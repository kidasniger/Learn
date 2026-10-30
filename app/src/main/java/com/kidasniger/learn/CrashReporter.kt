package com.kidasniger.learn

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journalise les plantages non rattrapés dans un fichier local (crash_last.txt)
 * afin que l'utilisateur puisse les partager depuis l'écran Réglages, même sans
 * ordinateur ni adb. Le gestionnaire précédent est toujours invoqué ensuite pour
 * conserver le comportement système normal (boîte de dialogue « L'application
 * s'est arrêtée »).
 */
object CrashReporter {

    private const val FILE_NAME = "crash_last.txt"
    private const val MAX_REPORT_BYTES = 256 * 1024
    private const val MAX_TEXT_SHARE_CHARS = 18_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Ne jamais empêcher le traitement système du plantage.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Dernier rapport de plantage enregistré, ou null s'il n'y en a pas. */
    fun lastReport(context: Context): File? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        return if (file.isFile && file.length() > 0L) file else null
    }

    /** Résumé court pour l'affichage : date du plantage + version concernée. */
    fun reportSummary(context: Context): String? {
        val text = lastReport(context)?.readText()?.lineSequence()?.firstOrNull { it.isNotBlank() }
        return text?.take(120)
    }

    /** Contenu tronqué du rapport, prêt à être partagé en texte. */
    fun shareableText(context: Context): String? {
        val text = lastReport(context)?.readText() ?: return null
        return if (text.length <= MAX_TEXT_SHARE_CHARS) text else text.take(MAX_TEXT_SHARE_CHARS) + "\n… (tronqué)"
    }

    fun clear(context: Context) {
        lastReport(context)?.delete()
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val previous = File(context.applicationContext.filesDir, FILE_NAME)
        if (previous.isFile) {
            val older = File(context.applicationContext.filesDir, "crash_prev.txt")
            if (older.isFile) older.delete()
            previous.renameTo(older)
        }

        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val header = buildString {
            append("Plantage du ").append(timestamp).append('\n')
            append("Application : Learn ").append(BuildConfig.VERSION_NAME)
            append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Appareil : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" — Android ").append(Build.VERSION.RELEASE ?: "?")
            append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Thread : ").append(thread.name).append('\n')
        }
        val report = header + '\n' + trace
        File(context.applicationContext.filesDir, FILE_NAME).writeText(report.take(MAX_REPORT_BYTES))
    }
}
