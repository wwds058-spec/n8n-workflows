package com.personalai.assistant.data

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom

/** Whether the local database is encrypted, and why not if it isn't. */
data class EncryptionStatus(val encrypted: Boolean, val detail: String)

/**
 * Opens the app database encrypted with SQLCipher. The passphrase is random, generated on
 * first use and kept in EncryptedSharedPreferences, which is protected by the Android
 * Keystore. An existing unencrypted database is converted once; if that fails, the app
 * keeps using the unencrypted copy rather than losing data.
 */
object DatabaseEncryption {

    private const val DB_NAME = "assistant.db"
    private const val KEY_FILE = "secure_database"
    private const val KEY_PASSPHRASE = "passphrase"
    private const val STATUS_FILE = "database_status"
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun open(context: Context): Pair<AppDatabase, EncryptionStatus> {
        val status = try {
            System.loadLibrary("sqlcipher")
            val passphrase = passphrase(context)
            prepareFile(context, passphrase)
            EncryptionStatus(true, "Encrypted with SQLCipher; the key is protected by the Android Keystore.")
        } catch (e: Throwable) {
            // Unencrypted Room can't open an encrypted file; keep it aside rather than crash.
            val db = context.getDatabasePath(DB_NAME)
            if (db.exists() && !isPlaintext(db)) {
                db.renameTo(File(db.parentFile, "$DB_NAME.unreadable-${System.currentTimeMillis()}"))
                sidecars(db).forEach { it.delete() }
            }
            EncryptionStatus(false, "Not encrypted: ${e.message ?: e.javaClass.simpleName}")
        }
        context.getSharedPreferences(STATUS_FILE, Context.MODE_PRIVATE).edit {
            putBoolean("encrypted", status.encrypted)
            putString("detail", status.detail)
        }
        val factory = if (status.encrypted) SupportOpenHelperFactory(passphrase(context)) else null
        return AppDatabase.create(context, factory) to status
    }

    /** Makes sure the file on disk is either absent or encrypted with [passphrase]. */
    private fun prepareFile(context: Context, passphrase: ByteArray) {
        val db = context.getDatabasePath(DB_NAME)
        if (!db.exists()) return
        if (isPlaintext(db)) {
            encryptExisting(db, passphrase)
            return
        }
        if (!canOpen(db, passphrase)) {
            // The key was lost (for example the Keystore was reset). The old data can't be
            // read by anyone, so set it aside and start fresh instead of crashing.
            val aside = File(db.parentFile, "$DB_NAME.unreadable-${System.currentTimeMillis()}")
            db.renameTo(aside)
            sidecars(db).forEach { it.delete() }
        }
    }

    private fun encryptExisting(db: File, passphrase: ByteArray) {
        val tmp = File(db.parentFile, "$DB_NAME.encrypting")
        tmp.delete()
        val key = String(passphrase, Charsets.US_ASCII)
        try {
            val plain = SQLiteDatabase.openDatabase(db.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, null)
            try {
                val version = plain.version
                plain.execSQL("ATTACH DATABASE '${tmp.path.replace("'", "''")}' AS encrypted KEY '$key'")
                plain.rawQuery("SELECT sqlcipher_export('encrypted')").use { it.moveToFirst() }
                plain.execSQL("PRAGMA encrypted.user_version = $version")
                plain.execSQL("DETACH DATABASE encrypted")
            } finally {
                plain.close()
            }
            check(canOpen(tmp, passphrase)) { "the encrypted copy couldn't be verified" }
            sidecars(db).forEach { it.delete() }
            check(db.delete() && tmp.renameTo(db)) { "the encrypted copy couldn't replace the original" }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun canOpen(file: File, passphrase: ByteArray): Boolean = try {
        val db = SQLiteDatabase.openDatabase(file.path, passphrase, null, SQLiteDatabase.OPEN_READONLY, null)
        try {
            db.rawQuery("SELECT count(*) FROM sqlite_master").use { it.moveToFirst() }
        } finally {
            db.close()
        }
    } catch (e: Exception) {
        false
    }

    private fun isPlaintext(file: File): Boolean {
        val header = ByteArray(SQLITE_HEADER.size)
        val read = file.inputStream().use { it.read(header) }
        return read == header.size && header.contentEquals(SQLITE_HEADER)
    }

    private fun sidecars(db: File) = listOf("-wal", "-shm", "-journal").map { File(db.path + it) }

    private fun passphrase(context: Context): ByteArray {
        fun openPrefs() = EncryptedSharedPreferences.create(
            context,
            KEY_FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        val prefs = try {
            openPrefs()
        } catch (e: Exception) {
            // The Keystore entry is gone, so the stored passphrase can't be read any more.
            context.deleteSharedPreferences(KEY_FILE)
            openPrefs()
        }
        val existing = prefs.getString(KEY_PASSPHRASE, null)
        if (existing != null) return existing.toByteArray(Charsets.US_ASCII)
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit(commit = true) { putString(KEY_PASSPHRASE, hex) }
        return hex.toByteArray(Charsets.US_ASCII)
    }
}
