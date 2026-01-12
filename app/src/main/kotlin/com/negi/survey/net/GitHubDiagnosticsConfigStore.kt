/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubDiagnosticsConfigStore.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate")

package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores GitHub diagnostics upload configuration.
 *
 * This implementation intentionally avoids Jetpack Security-Crypto because recent versions
 * deprecated the legacy APIs (EncryptedSharedPreferences/MasterKey). We use Android Keystore
 * (AES/GCM) directly.
 *
 * Storage policy:
 * - owner/repo/branch/pathPrefix are stored as plaintext SharedPreferences.
 * - token is stored encrypted with AES/GCM in Android Keystore (API 23+).
 *
 * If the keystore key is lost/rotated (OS update, secure lock changes, etc.), token decryption may fail.
 * In that case, we clear ONLY the encrypted token and keep other fields intact.
 */
object GitHubDiagnosticsConfigStore {

    private const val TAG = "GitHubDiagCfgStore"

    // Current prefs name.
    private const val PREF_NAME = "github_diagnostics_config"

    // Current keys.
    private const val KEY_OWNER = "owner"
    private const val KEY_REPO = "repo"
    private const val KEY_BRANCH = "branch"
    private const val KEY_PATH_PREFIX = "pathPrefix"
    private const val KEY_TOKEN_ENC = "tokenEnc"

    // Optional legacy keys/prefs (add more if you know the old names).
    private const val LEGACY_PREF_NAME_1 = "github_diagnostics_config_secure" // example
    private const val LEGACY_KEY_TOKEN_1 = "token" // example legacy plaintext key
    private const val LEGACY_KEY_TOKEN_ENC_1 = "token" // example legacy encrypted blob key

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "ishizuki.github.diag.token.aesgcm"

    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    /**
     * Read config from storage.
     *
     * @return null if missing or invalid.
     */
    fun read(context: Context): GitHubUploader.GitHubConfig? {
        val app = context.applicationContext
        val p = prefs(app)

        // Attempt migration first (best-effort). Does nothing if not needed.
        runCatching { migrateLegacyIfNeeded(app) }
            .onFailure { Log.w(TAG, "Legacy migration failed: ${it.message}", it) }

        val owner = p.getString(KEY_OWNER, null)?.trim().orEmpty()
        val repo = p.getString(KEY_REPO, null)?.trim().orEmpty()
        val branch = p.getString(KEY_BRANCH, null)?.trim().orEmpty()
        val pathPrefix = p.getString(KEY_PATH_PREFIX, null)?.trim().orEmpty()

        val token = readTokenBestEffort(app)

        if (owner.isBlank() || repo.isBlank() || token.isBlank()) return null

        return GitHubUploader.GitHubConfig(
            owner = owner,
            repo = repo,
            branch = branch.ifBlank { "main" },
            pathPrefix = pathPrefix,
            token = token
        )
    }

    /**
     * Persist config to storage.
     *
     * Token is stored encrypted (AES/GCM) on API 23+.
     * On API < 23, token is NOT persisted (safety-first).
     */
    fun write(context: Context, cfg: GitHubUploader.GitHubConfig) {
        val app = context.applicationContext
        val p = prefs(app)
        val e = p.edit()

        e.putString(KEY_OWNER, cfg.owner.trim())
        e.putString(KEY_REPO, cfg.repo.trim())
        e.putString(KEY_BRANCH, cfg.branch.trim())
        e.putString(KEY_PATH_PREFIX, cfg.pathPrefix.trim())

        val token = cfg.token.trim()

        if (token.isBlank()) {
            e.remove(KEY_TOKEN_ENC)
            e.apply()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // We refuse to persist tokens insecurely.
            Log.w(TAG, "API < 23: token persistence disabled; storing metadata only.")
            e.remove(KEY_TOKEN_ENC)
            e.apply()
            return
        }

        val stored = runCatching { encryptString(token) }
            .onFailure { Log.w(TAG, "Token encrypt failed: ${it.message}", it) }
            .getOrNull()

        if (stored.isNullOrBlank()) {
            // Don't keep a broken value.
            e.remove(KEY_TOKEN_ENC)
        } else {
            e.putString(KEY_TOKEN_ENC, stored)
        }

        e.apply()
    }

    /**
     * Clear stored config.
     */
    fun clear(context: Context) {
        prefs(context.applicationContext).edit().clear().apply()
    }

    /* -----------------------------------------------------------------------
     * Compatibility helpers (older call sites).
     * --------------------------------------------------------------------- */

    /** Backward compatible alias. */
    fun load(context: Context): GitHubUploader.GitHubConfig? = read(context)

    /** Backward compatible alias. */
    fun save(context: Context, cfg: GitHubUploader.GitHubConfig) = write(context, cfg)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Read token with robust fallback behavior.
     *
     * - First tries KEY_TOKEN_ENC in current prefs.
     * - If decrypt fails due to key mismatch/bad tag/etc., remove tokenEnc only.
     * - Optionally tries legacy locations if current token is missing.
     */
    private fun readTokenBestEffort(context: Context): String {
        val p = prefs(context)

        // 1) Current key.
        val tokenEnc = p.getString(KEY_TOKEN_ENC, null)
        if (!tokenEnc.isNullOrBlank()) {
            val token = runCatching { decryptString(tokenEnc) }
                .onFailure { e ->
                    Log.w(TAG, "Token decrypt failed; clearing token only. reason=${e.message}", e)
                    // Keep non-sensitive fields; remove token only.
                    p.edit().remove(KEY_TOKEN_ENC).apply()
                }
                .getOrNull()
                .orEmpty()

            if (token.isNotBlank()) return token
        }

        // 2) Legacy fallbacks (best-effort). If found, migrate into current storage.
        val legacyToken = runCatching { readLegacyToken(context) }.getOrNull().orEmpty()
        if (legacyToken.isNotBlank()) {
            // Persist into current location (encrypted if possible).
            runCatching {
                write(
                    context,
                    GitHubUploader.GitHubConfig(
                        owner = p.getString(KEY_OWNER, "") ?: "",
                        repo = p.getString(KEY_REPO, "") ?: "",
                        branch = p.getString(KEY_BRANCH, "") ?: "main",
                        pathPrefix = p.getString(KEY_PATH_PREFIX, "") ?: "",
                        token = legacyToken
                    )
                )
            }.onFailure { Log.w(TAG, "Legacy token re-save failed: ${it.message}", it) }

            return legacyToken
        }

        return ""
    }

    /**
     * Attempt to migrate legacy prefs/keys into current prefs.
     *
     * This is intentionally conservative:
     * - If current prefs already has owner/repo, we don't overwrite.
     * - If legacy token exists, we try to carry it forward safely.
     */
    private fun migrateLegacyIfNeeded(context: Context) {
        val current = prefs(context)
        val hasAnyCurrent =
            !current.getString(KEY_OWNER, null).isNullOrBlank() ||
                    !current.getString(KEY_REPO, null).isNullOrBlank() ||
                    !current.getString(KEY_TOKEN_ENC, null).isNullOrBlank()

        // If current already populated, nothing to do.
        if (hasAnyCurrent) return

        // Try legacy pref name (optional).
        val legacy = context.getSharedPreferences(LEGACY_PREF_NAME_1, Context.MODE_PRIVATE)

        val owner = legacy.getString(KEY_OWNER, null)?.trim().orEmpty()
        val repo = legacy.getString(KEY_REPO, null)?.trim().orEmpty()
        val branch = legacy.getString(KEY_BRANCH, null)?.trim().orEmpty()
        val pathPrefix = legacy.getString(KEY_PATH_PREFIX, null)?.trim().orEmpty()

        val token = readLegacyToken(context).trim()

        if (owner.isBlank() || repo.isBlank()) return

        // Write migrated values (token stored securely if possible).
        write(
            context,
            GitHubUploader.GitHubConfig(
                owner = owner,
                repo = repo,
                branch = branch.ifBlank { "main" },
                pathPrefix = pathPrefix,
                token = token
            )
        )
    }

    /**
     * Read token from known legacy places (best-effort).
     *
     * NOTE:
     * - If your old implementation used EncryptedSharedPreferences, you may not be able to decrypt it
     *   without those APIs. In that case, migration may only be possible if you were also storing a
     *   plaintext token (not recommended) or if you keep Security-Crypto temporarily for migration.
     *
     * Here we try:
     * - legacy pref name, key "token" (plaintext) as a last-resort.
     * - legacy pref name, key "token" assumed same format as our base64(iv+ciphertext) (if you used this).
     */
    private fun readLegacyToken(context: Context): String {
        // Try same prefs as current first (some older builds used different key names).
        val pCurrent = prefs(context)

        // Candidate 1: legacy plaintext key in current prefs.
        val t1 = pCurrent.getString(LEGACY_KEY_TOKEN_1, null)
        if (!t1.isNullOrBlank()) return t1.trim()

        // Candidate 2: legacy prefs name.
        val pLegacy = context.getSharedPreferences(LEGACY_PREF_NAME_1, Context.MODE_PRIVATE)

        // Candidate 3: legacy plaintext key.
        val t2 = pLegacy.getString(LEGACY_KEY_TOKEN_1, null)
        if (!t2.isNullOrBlank()) return t2.trim()

        // Candidate 4: legacy encrypted blob key that matches our format.
        val enc = pLegacy.getString(LEGACY_KEY_TOKEN_ENC_1, null)
        if (!enc.isNullOrBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return runCatching { decryptString(enc) }.getOrNull().orEmpty()
        }

        return ""
    }

    /**
     * Encrypt a string using AES/GCM with a key stored in Android Keystore.
     *
     * Stored format: base64( iv(12) + ciphertext+tag )
     */
    private fun encryptString(plain: String): String {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { "AES/GCM requires API 23+" }

        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        if (iv.size != GCM_IV_BYTES) {
            // Do not crash; fail safe.
            throw IllegalStateException("Unexpected GCM IV length: ${iv.size}")
        }

        val cipherText = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))

        val buf = ByteBuffer.allocate(iv.size + cipherText.size)
        buf.put(iv)
        buf.put(cipherText)

        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    /**
     * Decrypt a string using AES/GCM with a key stored in Android Keystore.
     *
     * Expected format: base64( iv(12) + ciphertext+tag )
     */
    private fun decryptString(b64: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

        val raw = runCatching { Base64.decode(b64, Base64.NO_WRAP) }
            .getOrElse { return null }

        if (raw.size <= GCM_IV_BYTES) return null

        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val cipherText = raw.copyOfRange(GCM_IV_BYTES, raw.size)

        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance(AES_MODE)
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)

        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, StandardCharsets.UTF_8)
        } catch (e: AEADBadTagException) {
            // Key mismatch / data tampering / restore mismatch.
            throw e
        }
    }

    /**
     * Get or create an AES key in AndroidKeyStore.
     *
     * Key properties:
     * - AES/GCM/NoPadding
     * - Not user-authenticated (background uploads must work)
     */
    private fun getOrCreateAesKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        val existing = runCatching { ks.getKey(KEY_ALIAS, null) }.getOrNull()
        if (existing is SecretKey) return existing

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Background uploads must work; do not require user auth.
            .setUserAuthenticationRequired(false)
            .build()

        kg.init(spec)
        return kg.generateKey()
    }
}
