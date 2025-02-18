package com.looker.droidify.utility

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.looker.droidify.BuildConfig
import com.looker.droidify.PREFS_LANGUAGE_DEFAULT
import com.looker.droidify.R
import com.looker.droidify.content.Preferences
import com.looker.droidify.database.entity.Installed
import com.looker.droidify.database.entity.Repository
import com.looker.droidify.entity.Product
import com.looker.droidify.service.Connection
import com.looker.droidify.service.DownloadService
import com.looker.droidify.utility.extension.android.Android
import com.looker.droidify.utility.extension.android.singleSignature
import com.looker.droidify.utility.extension.android.versionCodeCompat
import com.looker.droidify.utility.extension.json.Json
import com.looker.droidify.utility.extension.json.parseDictionary
import com.looker.droidify.utility.extension.json.writeDictionary
import com.looker.droidify.utility.extension.resources.getDrawableCompat
import com.looker.droidify.utility.extension.text.hex
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.util.*

object Utils {
    fun PackageInfo.toInstalledItem(): Installed {
        val signatureString = singleSignature?.let(Utils::calculateHash).orEmpty()
        return Installed(packageName, versionName.orEmpty(), versionCodeCompat, signatureString)
    }

    fun getDefaultApplicationIcon(context: Context): Drawable =
        context.getDrawableCompat(R.drawable.ic_placeholder)

    fun getToolbarIcon(context: Context, resId: Int): Drawable {
        return context.getDrawableCompat(resId).mutate()
    }

    fun calculateHash(signature: Signature): String {
        return MessageDigest.getInstance("MD5").digest(signature.toCharsString().toByteArray())
            .hex()
    }

    fun calculateFingerprint(certificate: Certificate): String {
        val encoded = try {
            certificate.encoded
        } catch (e: CertificateEncodingException) {
            null
        }
        return encoded?.let(::calculateFingerprint).orEmpty()
    }

    fun calculateFingerprint(key: ByteArray): String {
        return if (key.size >= 256) {
            try {
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(key)
                val builder = StringBuilder()
                for (byte in fingerprint) {
                    builder.append("%02X".format(Locale.US, byte.toInt() and 0xff))
                }
                builder.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        } else {
            ""
        }
    }

    val rootInstallerEnabled: Boolean
        get() = Preferences[Preferences.Key.RootPermission] &&
                (Shell.getCachedShell()?.isRoot ?: Shell.getShell().isRoot)

    suspend fun startUpdate(
        packageName: String,
        installed: Installed?,
        products: List<Pair<Product, Repository>>,
        downloadConnection: Connection<DownloadService.Binder, DownloadService>,
    ) {
        val productRepository = Product.findSuggested(products, installed) { it.first }
        val compatibleReleases = productRepository?.first?.selectedReleases.orEmpty()
            .filter { installed == null || installed.signature == it.signature }
        val releaseFlow = MutableStateFlow(compatibleReleases.firstOrNull())
        if (compatibleReleases.size > 1) {
            releaseFlow.emit(
                compatibleReleases
                    .filter { it.platforms.contains(Android.primaryPlatform) }
                    .minByOrNull { it.platforms.size }
                    ?: compatibleReleases.minByOrNull { it.platforms.size }
                    ?: compatibleReleases.firstOrNull()
            )
        }
        val binder = downloadConnection.binder
        releaseFlow.collect {
            if (productRepository != null && it != null && binder != null) {
                binder.enqueue(
                    packageName,
                    productRepository.first.name,
                    productRepository.second,
                    it
                )
            }
        }
    }

    fun Context.setLanguage(): Configuration {
        var setLocalCode = Preferences[Preferences.Key.Language]
        if (setLocalCode == PREFS_LANGUAGE_DEFAULT) {
            setLocalCode = Locale.getDefault().language
        }
        val config = resources.configuration
        val sysLocale = if (Android.sdk(24)) config.locales[0] else config.locale
        if (setLocalCode != sysLocale.language || setLocalCode != "${sysLocale.language}-r${sysLocale.country}") {
            val newLocale = getLocaleOfCode(setLocalCode)
            Locale.setDefault(newLocale)
            config.setLocale(newLocale)
        }
        return config
    }

    val languagesList: List<String>
        get() {
            val entryVals = arrayOfNulls<String>(1)
            entryVals[0] = PREFS_LANGUAGE_DEFAULT
            return entryVals.plus(BuildConfig.DETECTED_LOCALES.sorted()).filterNotNull()
        }

    fun translateLocale(locale: Locale): String {
        val country = locale.getDisplayCountry(locale)
        val language = locale.getDisplayLanguage(locale)
        return (language.replaceFirstChar { it.uppercase(Locale.getDefault()) }
                + (if (country.isNotEmpty() && country.compareTo(language, true) != 0)
            "($country)" else ""))
    }

    fun Context.getLocaleOfCode(localeCode: String): Locale = when {
        localeCode.isEmpty() -> if (Android.sdk(24)) {
            resources.configuration.locales[0]
        } else {
            resources.configuration.locale
        }
        localeCode.contains("-r") -> Locale(
            localeCode.substring(0, 2),
            localeCode.substring(4)
        )
        else -> Locale(localeCode)
    }

    /**
     * Checks if app is currently considered to be in the foreground by Android.
     */
    fun inForeground(): Boolean {
        val appProcessInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        val importance = appProcessInfo.importance
        return ((importance == IMPORTANCE_FOREGROUND) or (importance == IMPORTANCE_VISIBLE))
    }

}

fun <T> ByteArray.jsonParse(callback: (JsonParser) -> T): T {
    return Json.factory.createParser(this).use { it.parseDictionary(callback) }
}

fun jsonGenerate(callback: (JsonGenerator) -> Unit): ByteArray {
    val outputStream = ByteArrayOutputStream()
    Json.factory.createGenerator(outputStream).use { it.writeDictionary(callback) }
    return outputStream.toByteArray()
}

val isDarkTheme: Boolean
    get() = when (Preferences[Preferences.Key.Theme]) {
        is Preferences.Theme.Light -> false
        is Preferences.Theme.Dark -> true
        is Preferences.Theme.Amoled -> true
        else -> false
    }

val isBlackTheme: Boolean
    get() = when (Preferences[Preferences.Key.Theme]) {
        is Preferences.Theme.Amoled -> true
        is Preferences.Theme.AmoledSystem -> true
        else -> false
    }