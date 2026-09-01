package org.jellyfin.mobile.channelflow

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.R
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

sealed class ChannelFlowUpdateStatus {
	data object Idle : ChannelFlowUpdateStatus()
	data object Checking : ChannelFlowUpdateStatus()
	data class UpToDate(val installed: String, val latest: String) : ChannelFlowUpdateStatus()
	data class Available(
		val installed: String,
		val latest: String,
		val pageUrl: String,
		val apkUrl: String?,
		val apkName: String? = null,
		val apkSize: Long = 0L,
	) : ChannelFlowUpdateStatus()
	data class Downloading(val latest: String, val progress: Int, val received: Long, val total: Long) : ChannelFlowUpdateStatus()
	data class Installing(val latest: String) : ChannelFlowUpdateStatus()
	data class Failed(val reason: String? = null) : ChannelFlowUpdateStatus()
}

class ChannelFlowUpdateChecker(
	context: Context,
) {
	private val app = context.applicationContext
	private val json = Json { ignoreUnknownKeys = true }
	private val http = OkHttpClient.Builder()
		.connectTimeout(20, TimeUnit.SECONDS)
		.readTimeout(5, TimeUnit.MINUTES)
		.writeTimeout(30, TimeUnit.SECONDS)
		.followRedirects(true)
		.followSslRedirects(true)
		.build()
	private val downloadLock = Mutex()
	private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
	private var pending: ChannelFlowUpdateStatus.Available? = null

	private val _status = MutableStateFlow<ChannelFlowUpdateStatus>(ChannelFlowUpdateStatus.Idle)
	val status: StateFlow<ChannelFlowUpdateStatus> = _status

	suspend fun prefetch() {
		if (_status.value is ChannelFlowUpdateStatus.Idle) check(force = false)
	}

	suspend fun check(force: Boolean = false): ChannelFlowUpdateStatus {
		if (!force) {
			when (val current = _status.value) {
				is ChannelFlowUpdateStatus.UpToDate,
				is ChannelFlowUpdateStatus.Available,
				is ChannelFlowUpdateStatus.Downloading,
				is ChannelFlowUpdateStatus.Installing,
					-> return current
				else -> Unit
			}
		}

		_status.value = ChannelFlowUpdateStatus.Checking
		val result = runCatching { fetchLatest() }
			.fold(
				onSuccess = { release -> compare(release) },
				onFailure = { error ->
					Timber.w(error, "GitHub version check failed")
					ChannelFlowUpdateStatus.Failed(error.message)
				},
			)
		if (result is ChannelFlowUpdateStatus.Available) pending = result
		_status.value = result
		return result
	}

	fun needsInstallPermission(): Boolean =
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()

	fun installPermissionIntent(newTask: Boolean = true): Intent =
		Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
			.apply { if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

	fun startInstall(context: Context) {
		when (_status.value) {
			is ChannelFlowUpdateStatus.Downloading,
			is ChannelFlowUpdateStatus.Installing,
				-> return
			else -> Unit
		}
		val available = pending ?: _status.value as? ChannelFlowUpdateStatus.Available
		if (available?.apkUrl.isNullOrBlank()) {
			_status.value = ChannelFlowUpdateStatus.Failed("no apk")
			return
		}
		pending = available
		val intent = Intent(context, ChannelFlowUpdateInstallActivity::class.java)
		if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		context.startActivity(intent)
	}

	fun retryInstall(context: Context): Boolean {
		if (pending?.apkUrl.isNullOrBlank()) return false
		startInstall(context)
		return true
	}

	fun shouldPromptLaunch(latest: String): Boolean =
		ChannelFlowVersion.shouldPromptLaunch(latest, prefs.getString(KEY_DISMISSED, null))

	fun dismissLaunchPrompt(latest: String) {
		prefs.edit { putString(KEY_DISMISSED, ChannelFlowVersion.normalize(latest)) }
	}

	fun restorePending() {
		pending?.let { _status.value = it }
	}

	fun onInstallCommitted(latest: String) {
		_status.value = ChannelFlowUpdateStatus.Installing(latest)
	}

	fun onInstallSucceeded() {
		val latest = pending?.latest ?: BuildConfig.VERSION_NAME
		_status.value = ChannelFlowUpdateStatus.UpToDate(latest, latest)
	}

	fun onInstallFailed(message: String?) {
		_status.value = ChannelFlowUpdateStatus.Failed(message)
	}

	private fun compare(release: GithubRelease): ChannelFlowUpdateStatus {
		val installed = BuildConfig.VERSION_NAME
		val latest = ChannelFlowVersion.normalize(release.tagName.ifBlank { release.name.orEmpty() })
		if (latest.isBlank()) return ChannelFlowUpdateStatus.Failed("empty tag")
		val pageUrl = release.htmlUrl.ifBlank { RELEASES_PAGE }
		val apk = pickApkAsset(release.assets, preferDebug = false)
		return if (ChannelFlowVersion.isNewer(latest, installed)) {
			ChannelFlowUpdateStatus.Available(
				installed = installed,
				latest = latest,
				pageUrl = pageUrl,
				apkUrl = apk?.browserDownloadUrl?.ifBlank { null },
				apkName = apk?.name,
				apkSize = apk?.size ?: 0L,
			)
		} else {
			ChannelFlowUpdateStatus.UpToDate(installed, latest)
		}
	}

	suspend fun downloadLatest(): File = downloadLock.withLock {
		val available = pending ?: _status.value as? ChannelFlowUpdateStatus.Available
			?: error("No update available")
		val apkUrl = available.apkUrl ?: error("No APK on GitHub release")
		val file = apkFile()
		file.parentFile?.mkdirs()
		if (file.exists() && available.apkSize > 1000L && file.length() == available.apkSize) {
			_status.value = ChannelFlowUpdateStatus.Installing(available.latest)
			return file
		}
		if (file.exists()) file.delete()

		_status.value = ChannelFlowUpdateStatus.Downloading(available.latest, 0, 0L, available.apkSize)
		withContext(Dispatchers.IO) {
			val response = http.newCall(
				Request.Builder()
					.url(apkUrl)
					.header("Accept", "application/octet-stream")
					.header("User-Agent", USER_AGENT)
					.build()
			).execute()
			response.use { result ->
				if (!result.isSuccessful) error("GitHub download HTTP ${result.code}")
				val body = result.body ?: error("Empty APK download")
				val total = if (body.contentLength() > 0) body.contentLength() else available.apkSize
				var received = 0L
				body.byteStream().use { input ->
					file.outputStream().use { output ->
						val buffer = ByteArray(DEFAULT_BUFFER)
						while (true) {
							val read = input.read(buffer)
							if (read <= 0) break
							output.write(buffer, 0, read)
							received += read
							val percent = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 99) else 0
							_status.value = ChannelFlowUpdateStatus.Downloading(available.latest, percent, received, total)
						}
					}
				}
			}
		}
		if (!file.exists() || file.length() < 1000L) error("Downloaded APK is empty")
		_status.value = ChannelFlowUpdateStatus.Installing(available.latest)
		file
	}

	fun openInstaller(activity: Activity, file: File): Boolean {
		when (updateCompatibility(file)) {
			ChannelFlowUpdateCompat.SignatureMismatch -> {
				onInstallFailed(activity.getString(R.string.msg_update_signature_mismatch))
				return false
			}
			ChannelFlowUpdateCompat.PackageMismatch -> {
				onInstallFailed(activity.getString(R.string.msg_update_package_mismatch))
				return false
			}
			ChannelFlowUpdateCompat.Compatible -> Unit
		}
		if (openSystemInstaller(activity, file)) return true
		return runCatching { commitInstall(activity, file) }
			.onFailure { Timber.e(it, "PackageInstaller session failed") }
			.isSuccess
	}

	internal fun updateCompatibility(file: File): ChannelFlowUpdateCompat =
		apkUpdateCompatibility(app, file)

	private fun openSystemInstaller(activity: Activity, file: File): Boolean {
		val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update", file)
		val intents = listOf(
			Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME),
			Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, APK_MIME),
		)
		for (intent in intents) {
			intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			grantInstallerAccess(activity, intent, uri)
			val started = runCatching { activity.startActivity(intent) }.isSuccess
			if (started) {
				Timber.i("Opened system installer via %s", intent.action)
				return true
			}
		}
		return false
	}

	private fun grantInstallerAccess(context: Context, intent: Intent, uri: Uri) {
		val matches = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
		for (resolve in matches) {
			context.grantUriPermission(
				resolve.activityInfo.packageName,
				uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION,
			)
		}
	}

	private fun commitInstall(activity: Activity, file: File) {
		val latest = pending?.latest ?: BuildConfig.VERSION_NAME
		val installer = activity.packageManager.packageInstaller
		val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
			setAppPackageName(activity.packageName)
			setSize(file.length())
		}
		val sessionId = installer.createSession(params)
		val session = installer.openSession(sessionId)
		try {
			session.openWrite("base.apk", 0, file.length()).use { out ->
				file.inputStream().use { input -> input.copyTo(out) }
				session.fsync(out)
			}
			val flags = PendingIntent.FLAG_UPDATE_CURRENT or (
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
			)
			val statusIntent = Intent(activity, ChannelFlowUpdateInstallActivity::class.java).apply {
				action = ACTION_INSTALL_STATUS
				putExtra(EXTRA_VERSION, latest)
			}
			val pendingIntent = PendingIntent.getActivity(activity, sessionId, statusIntent, flags)
			session.commit(pendingIntent.intentSender)
		} catch (error: Throwable) {
			runCatching { session.abandon() }
			throw error
		} finally {
			runCatching { session.close() }
		}
	}

	private fun apkFile(): File = File(File(app.cacheDir, "updates"), "channelflow-mobile-update.apk")

	private suspend fun fetchLatest(): GithubRelease = withContext(Dispatchers.IO) {
		val latest = fetchRelease(LATEST_URL)?.takeUnless { it.prerelease || it.draft }
		val listed = fetchReleaseList().filterNot { it.prerelease || it.draft }
		latest ?: listed.firstOrNull() ?: error("No GitHub releases")
	}

	private fun fetchRelease(url: String): GithubRelease? {
		val body = request(url) ?: return null
		return json.decodeFromString<GithubRelease>(body).takeUnless { it.draft }
	}

	private fun fetchReleaseList(): List<GithubRelease> {
		val body = request(LIST_URL) ?: return emptyList()
		return json.decodeFromString<List<GithubRelease>>(body).filterNot { it.draft }
	}

	private fun request(url: String): String? {
		val response = http.newCall(
			Request.Builder()
				.url(url)
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", USER_AGENT)
				.header("X-GitHub-Api-Version", "2022-11-28")
				.build()
		).execute()
		response.use { result ->
			if (result.code == 404) return null
			if (!result.isSuccessful) error("GitHub HTTP ${result.code}")
			return result.body?.string()
		}
	}

	companion object {
		const val GITHUB_REPO = "binarygeek119/ChannelFlow-Mobile-Client"
		const val RELEASES_PAGE = "https://github.com/$GITHUB_REPO/releases"
		const val ACTION_INSTALL_STATUS = "org.jellyfin.mobile.channelflow.INSTALL_STATUS"
		const val EXTRA_VERSION = "version"
		private const val PREFS = "channelflow_updates"
		private const val KEY_DISMISSED = "dismissed_version"
		private const val APK_MIME = "application/vnd.android.package-archive"
		private const val LATEST_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
		private const val LIST_URL = "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=5"
		private const val DEFAULT_BUFFER = 64 * 1024
		private val USER_AGENT = "ChannelFlow-Mobile/${BuildConfig.VERSION_NAME} (+https://github.com/$GITHUB_REPO)"
	}
}

internal enum class ChannelFlowUpdateCompat {
	Compatible,
	SignatureMismatch,
	PackageMismatch,
}

internal fun apkUpdateCompatibility(context: Context, file: File): ChannelFlowUpdateCompat {
	val archive = packageArchiveInfo(context.packageManager, file) ?: return ChannelFlowUpdateCompat.Compatible
	if (archive.packageName != context.packageName) return ChannelFlowUpdateCompat.PackageMismatch
	val installed = runCatching {
		packageInfo(context.packageManager, context.packageName)
	}.getOrNull() ?: return ChannelFlowUpdateCompat.Compatible
	val installedCerts = signingCerts(installed)
	val updateCerts = signingCerts(archive)
	if (installedCerts.isEmpty() || updateCerts.isEmpty()) return ChannelFlowUpdateCompat.Compatible
	val matches = installedCerts.any { installedCert ->
		updateCerts.any { it.contentEquals(installedCert) }
	}
	return if (matches) ChannelFlowUpdateCompat.Compatible else ChannelFlowUpdateCompat.SignatureMismatch
}

private fun packageArchiveInfo(packageManager: PackageManager, file: File): android.content.pm.PackageInfo? {
	val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
		packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
	} else {
		@Suppress("DEPRECATION")
		packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags())
	} ?: return null
	info.applicationInfo?.apply {
		sourceDir = file.absolutePath
		publicSourceDir = file.absolutePath
	}
	return info
}

private fun packageInfo(packageManager: PackageManager, packageName: String): android.content.pm.PackageInfo =
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
		packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(signingFlags().toLong()))
	} else {
		@Suppress("DEPRECATION")
		packageManager.getPackageInfo(packageName, signingFlags())
	}

private fun signingFlags(): Int =
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
		PackageManager.GET_SIGNING_CERTIFICATES
	} else {
		@Suppress("DEPRECATION")
		PackageManager.GET_SIGNATURES
	}

private fun signingCerts(info: android.content.pm.PackageInfo): List<ByteArray> {
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
		val signingInfo = info.signingInfo ?: return emptyList()
		val signers = if (signingInfo.hasMultipleSigners()) {
			signingInfo.apkContentsSigners
		} else {
			signingInfo.signingCertificateHistory
		}
		return signers.orEmpty().map { it.toByteArray() }
	}
	@Suppress("DEPRECATION")
	return info.signatures.orEmpty().map { it.toByteArray() }
}

internal fun pickApkAsset(assets: List<GithubAsset>, preferDebug: Boolean): GithubAsset? {
	val apks = assets.filter { asset ->
		asset.browserDownloadUrl.isNotBlank() && (
			asset.name.endsWith(".apk", ignoreCase = true) ||
				asset.contentType.contains("android.package", ignoreCase = true)
			)
	}
	if (apks.isEmpty()) return null
	val preferred = if (preferDebug) {
		apks.firstOrNull { it.name.contains("debug", ignoreCase = true) }
	} else {
		apks.firstOrNull { !it.name.contains("debug", ignoreCase = true) }
	}
	return preferred ?: apks.first()
}

@Serializable
internal data class GithubRelease(
	@SerialName("tag_name") val tagName: String = "",
	@SerialName("html_url") val htmlUrl: String = "",
	val name: String? = null,
	val draft: Boolean = false,
	val prerelease: Boolean = false,
	val assets: List<GithubAsset> = emptyList(),
)

@Serializable
internal data class GithubAsset(
	val name: String = "",
	@SerialName("browser_download_url") val browserDownloadUrl: String = "",
	@SerialName("content_type") val contentType: String = "",
	val size: Long = 0L,
)
