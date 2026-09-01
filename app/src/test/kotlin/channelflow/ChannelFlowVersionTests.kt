package org.jellyfin.mobile.channelflow

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChannelFlowVersionTests : FunSpec({
	test("treats a higher GitHub tag as newer") {
		ChannelFlowVersion.isNewer("v0.0.2", "0.0.1") shouldBe true
		ChannelFlowVersion.isNewer("0.0.1", "v0.0.1") shouldBe false
		ChannelFlowVersion.isNewer("0.0.1", "0.0.2") shouldBe false
	}

	test("treats a release as newer than a matching pre-release") {
		ChannelFlowVersion.isNewer("0.0.1", "0.0.1-dev.1") shouldBe true
		ChannelFlowVersion.isNewer("0.0.1-dev.1", "0.0.1") shouldBe false
	}

	test("prompts on launch until that version is dismissed") {
		ChannelFlowVersion.shouldPromptLaunch("0.0.3", null) shouldBe true
		ChannelFlowVersion.shouldPromptLaunch("0.0.3", "0.0.2") shouldBe true
		ChannelFlowVersion.shouldPromptLaunch("0.0.3", "0.0.3") shouldBe false
		ChannelFlowVersion.shouldPromptLaunch("", null) shouldBe false
	}

	test("formats display versions with a v. prefix") {
		ChannelFlowVersion.display("v0.0.1") shouldBe "v.0.0.1"
		ChannelFlowVersion.display("0.0.2") shouldBe "v.0.0.2"
	}

	test("picks a release APK and prefers debug when requested") {
		val assets = listOf(
			GithubAsset("app-release.apk", "https://example/release.apk", "application/vnd.android.package-archive", 10),
			GithubAsset("app-debug.apk", "https://example/debug.apk", "application/vnd.android.package-archive", 11),
			GithubAsset("notes.txt", "https://example/notes.txt", "text/plain", 2),
		)
		pickApkAsset(assets, preferDebug = false)?.name shouldBe "app-release.apk"
		pickApkAsset(assets, preferDebug = true)?.name shouldBe "app-debug.apk"
		pickApkAsset(emptyList(), preferDebug = false) shouldBe null
	}
})
