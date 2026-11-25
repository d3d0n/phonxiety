package com.phonxiety.bridge.data.updates

import androidx.core.content.FileProvider
import com.phonxiety.bridge.data.R

/**
 * Shares a downloaded APK with the system package installer on the user-confirmation path.
 *
 * A separate provider from the app's `.fileprovider`: the manifest merger refuses two
 * `FileProvider` declarations with different authorities under one class name, and this one exposes
 * only `files/updates/`.
 */
class UpdatesFileProvider : FileProvider(R.xml.updates_file_paths)
