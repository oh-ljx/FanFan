package com.ohljx.fanfan.media

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.Closeable

/**
 * 监听系统相册中的新增、删除、恢复和回收站变化。
 * 调用方负责合并短时间内的连续通知。
 */
class MediaStoreChangeObserver(
    context: Context,
    private val onChange: () -> Unit,
) : ContentObserver(Handler(Looper.getMainLooper())), Closeable {

    private val resolver = context.applicationContext.contentResolver

    init {
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            this,
        )
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            this,
        )
    }

    override fun onChange(selfChange: Boolean) {
        if (!selfChange) onChange()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        if (!selfChange) onChange()
    }

    override fun close() {
        resolver.unregisterContentObserver(this)
    }
}
