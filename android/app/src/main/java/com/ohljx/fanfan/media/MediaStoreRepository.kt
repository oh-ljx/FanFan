package com.ohljx.fanfan.media

import android.annotation.SuppressLint
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaStore 的轻量访问层。
 *
 * 启动查询只读取系统已经索引好的列。尤其不能在这里逐张打开图片判断实况格式，
 * 否则相册越大，冷启动读取量会按「图片数 × 数 MB」增长。
 */
class MediaStoreRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    suspend fun loadMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        if (canReadImages()) {
            val motionIds = if (supportsSpecialFormatColumn()) loadMotionPhotoIds(false) else null
            queryImages(items, trashedOnly = false, indexedMotionIds = motionIds)
        }
        if (canReadVideos()) queryVideos(items, trashedOnly = false)
        items.sortWith(compareByDescending<MediaItem> { it.dateTaken }.thenByDescending { it.id })
        items
    }

    /** 查询 Android 系统回收站；Android 10 及以下没有标准 MediaStore 回收站。 */
    suspend fun loadSystemTrashed(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()
        val items = mutableListOf<MediaItem>()
        if (canReadImages()) {
            val motionIds = if (supportsSpecialFormatColumn()) loadMotionPhotoIds(true) else null
            queryImages(items, trashedOnly = true, indexedMotionIds = motionIds)
        }
        if (canReadVideos()) queryVideos(items, trashedOnly = true)
        items.sortWith(
            compareByDescending<MediaItem> { it.trashExpiresAtSeconds ?: Long.MIN_VALUE }
                .thenByDescending { it.dateTaken },
        )
        items
    }

    private enum class MotionMetadataColumn { XMP, NONE }

    private fun queryImages(
        out: MutableList<MediaItem>,
        trashedOnly: Boolean,
        indexedMotionIds: Set<Long>?,
    ) {
        val preferred = when {
            indexedMotionIds != null -> MotionMetadataColumn.NONE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> MotionMetadataColumn.XMP
            else -> MotionMetadataColumn.NONE
        }
        try {
            val queried = mutableListOf<MediaItem>()
            queryImages(queried, trashedOnly, preferred, indexedMotionIds)
            out += queried
        } catch (error: RuntimeException) {
            // 少数 OEM MediaProvider 没实现可选索引列；退回基础列，首屏仍应正常打开。
            if (preferred == MotionMetadataColumn.NONE) {
                Log.w(TAG, "Image query failed", error)
            } else {
                Log.w(TAG, "Optional motion metadata column unavailable; using filename hint", error)
                try {
                    val queried = mutableListOf<MediaItem>()
                    queryImages(queried, trashedOnly, MotionMetadataColumn.NONE, indexedMotionIds)
                    out += queried
                } catch (fallbackError: RuntimeException) {
                    Log.w(TAG, "Image fallback query failed", fallbackError)
                }
            }
        }
    }

    private fun queryImages(
        out: MutableList<MediaItem>,
        trashedOnly: Boolean,
        motionColumn: MotionMetadataColumn,
        indexedMotionIds: Set<Long>?,
    ) {
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.BUCKET_ID)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            if (trashedOnly) add(MediaStore.MediaColumns.DATE_EXPIRES)
            when (motionColumn) {
                MotionMetadataColumn.XMP -> add(MediaStore.MediaColumns.XMP)
                MotionMetadataColumn.NONE -> Unit
            }
        }.toTypedArray()

        query(imagesCollection(), projection, trashedOnly)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val expiresColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
            val xmpColumn = cursor.getColumnIndex(MediaStore.MediaColumns.XMP)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val displayName = cursor.getString(nameColumn)
                val indexedMotion = indexedMotionIds?.let { id in it } ?: false
                val xmpMotion = xmpColumn >= 0 && MotionPhoto.hasMotionMetadata(cursor.blobOrNull(xmpColumn))
                val filenameHint = MotionPhoto.isLikelyMotionPhotoName(displayName)
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(imagesCollection(), id),
                    dateTaken = bestDate(cursor.getLong(dateColumn), cursor.getLong(addedColumn)),
                    isVideo = false,
                    bucketId = cursor.getLong(bucketIdColumn),
                    bucketName = cursor.getString(bucketNameColumn),
                    motionPhoto = indexedMotion || xmpMotion,
                    legacyMotionPhotoHint = filenameHint && !indexedMotion && !xmpMotion,
                    trashExpiresAtSeconds = cursor.longOrNull(expiresColumn),
                )
            }
        }
    }

    /** API 37 / S 扩展 21 起，官方标记位于 Files 集合而不是 Images 集合。 */
    @SuppressLint("NewApi", "InlinedApi")
    private fun loadMotionPhotoIds(trashedOnly: Boolean): Set<Long>? {
        return try {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.SPECIAL_FORMAT,
            )
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
            val selectionArgs = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            val cursor = if (trashedOnly) {
                val args = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                }
                resolver.query(collection, projection, args, null)
            } else {
                resolver.query(collection, projection, selection, selectionArgs, null)
            }
            cursor?.use {
                val ids = mutableSetOf<Long>()
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val formatColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SPECIAL_FORMAT)
                while (it.moveToNext()) {
                    if (!it.isNull(formatColumn) &&
                        it.getInt(formatColumn) == MediaStore.Files.FileColumns.SPECIAL_FORMAT_MOTION_PHOTO
                    ) {
                        ids += it.getLong(idColumn)
                    }
                }
                ids
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Indexed motion-photo format unavailable; using XMP", error)
            null
        }
    }

    private fun queryVideos(out: MutableList<MediaItem>, trashedOnly: Boolean) {
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DATE_TAKEN)
            add(MediaStore.Video.Media.DATE_ADDED)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.BUCKET_ID)
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            if (trashedOnly) add(MediaStore.MediaColumns.DATE_EXPIRES)
        }.toTypedArray()

        try {
            query(videosCollection(), projection, trashedOnly)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val expiresColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    out += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(videosCollection(), id),
                        dateTaken = bestDate(cursor.getLong(dateColumn), cursor.getLong(addedColumn)),
                        isVideo = true,
                        durationMs = cursor.getLong(durationColumn),
                        bucketId = cursor.getLong(bucketIdColumn),
                        bucketName = cursor.getString(bucketNameColumn),
                        trashExpiresAtSeconds = cursor.longOrNull(expiresColumn),
                    )
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Video query failed", error)
        }
    }

    // The Bundle overload only runs for loadSystemTrashed(), which returns before this on API < 30.
    @SuppressLint("NewApi", "InlinedApi")
    private fun query(collection: Uri, projection: Array<String>, trashedOnly: Boolean): Cursor? {
        return if (trashedOnly) {
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.MediaColumns.DATE_EXPIRES} DESC",
                )
            }
            resolver.query(collection, projection, args, null)
        } else {
            resolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_TAKEN} DESC",
            )
        }
    }

    private fun imagesCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun videosCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun supportsSpecialFormatColumn(): Boolean {
        if (Build.VERSION.SDK_INT >= 37) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 21
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasSelectedMediaAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun canReadImages(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            hasPermission(Manifest.permission.READ_MEDIA_IMAGES) || hasSelectedMediaAccess()
        else -> hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun canReadVideos(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            hasPermission(Manifest.permission.READ_MEDIA_VIDEO) || hasSelectedMediaAccess()
        else -> hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** 没有当前写权限时返回 false，由调用方发起系统恢复确认弹窗。 */
    suspend fun untrash(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext false
        try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            }
            val updated = resolver.update(uri, values, args)
            Log.d(TAG, "untrash $uri -> $updated")
            updated > 0
        } catch (error: Exception) {
            Log.w(TAG, "untrash $uri needs user confirmation", error)
            false
        }
    }

    private fun bestDate(dateTaken: Long, dateAddedSeconds: Long): Long =
        if (dateTaken > 0) dateTaken else dateAddedSeconds * 1000

    private fun Cursor.blobOrNull(column: Int): ByteArray? =
        if (column < 0 || isNull(column)) null else getBlob(column)

    private fun Cursor.longOrNull(column: Int): Long? =
        if (column < 0 || isNull(column)) null else getLong(column)

    private companion object {
        const val TAG = "FanFanMedia"
    }
}
