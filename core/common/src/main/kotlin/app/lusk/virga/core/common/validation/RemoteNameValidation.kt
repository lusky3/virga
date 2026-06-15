package app.lusk.virga.core.common.validation

/**
 * Maximum allowed length for an rclone remote name.
 *
 * rclone parses remote references as "name:path"; there is no hard limit in
 * the rclone source, but 64 characters is a practical cap that keeps config
 * files readable and avoids shell-quoting edge cases.
 */
const val MAX_REMOTE_NAME_LENGTH = 64

/**
 * Maximum allowed length for a filesystem folder name.
 *
 * POSIX defines NAME_MAX as 255 bytes; most modern filesystems (ext4, APFS,
 * NTFS) share this limit for a single path component.
 */
const val MAX_FOLDER_NAME_LENGTH = 255

/**
 * Returns true when [name] is structurally valid as an rclone remote name.
 *
 * Rules (mirrors the inline check in AddRemoteDialog):
 * - Length must be within [MAX_REMOTE_NAME_LENGTH].
 * - No C0 control characters (U+0000–U+001F).
 * - The trimmed name must contain neither `':'` nor `'/'`, which would corrupt
 *   rclone's "remote:path" addressing syntax.
 *
 * Blank names are NOT considered invalid by this function — the caller is
 * responsible for treating a blank name as "not yet usable" rather than an
 * error (matching AddRemoteDialog's `nameError`/`nameUsable` split).
 */
fun isValidRemoteName(name: String): Boolean {
    if (name.length > MAX_REMOTE_NAME_LENGTH) return false
    if (name.any { it in '\u0000'..'\u001F' }) return false
    return name.trim().none { it == ':' || it == '/' }
}

/**
 * Returns true when [trimmed] is structurally valid as a folder (directory) name.
 *
 * The caller is expected to pass an already-trimmed string — this matches the
 * call semantics in FileBrowserViewModel, which trims before delegating to the
 * structural check.
 *
 * Rules (mirrors isStructurallyValidFolderName in FileBrowserViewModel):
 * - Must be non-empty and must not be the reserved names `"."` or `".."`.
 * - Length must be within [MAX_FOLDER_NAME_LENGTH].
 * - Must contain no `'/'`, `'\\'`, or ISO control characters.
 *
 * Unlike remote-name validation, folder names must not contain backslash
 * (cross-platform compatibility) and must not be the POSIX dot-entries.
 */
fun isValidFolderName(trimmed: String): Boolean {
    if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return false
    if (trimmed.length > MAX_FOLDER_NAME_LENGTH) return false
    return trimmed.none { it == '/' || it == '\\' || it.isISOControl() }
}
