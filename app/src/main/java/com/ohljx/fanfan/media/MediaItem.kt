package com.ohljx.fanfan.media

import android.net.Uri

/** 系统相册里的一条媒体（照片或视频）。 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    /** 拍摄时间，毫秒时间戳。 */
    val dateTaken: Long,
    val isVideo: Boolean,
    /** 视频时长（毫秒），照片为 null。 */
    val durationMs: Long? = null,
    /** 实况照片内嵌视频在文件中的偏移，非实况照片为 null。 */
    val motionOffset: Long? = null,
    /** 实况照片内嵌视频的长度（字节）。 */
    val motionLength: Long? = null,
    /** MediaStore / XMP 给出的实况照片标记；无需在启动时读取整张图片。 */
    val motionPhoto: Boolean = false,
    /** 厂商未写入标准元数据、仅由历史文件名识别；播放时才做一次兼容检测。 */
    val legacyMotionPhotoHint: Boolean = false,
    /** 系统回收站到期时间（秒）；仅用于让“最近删除”保持正确顺序。 */
    val trashExpiresAtSeconds: Long? = null,
) {
    /** 是否为实况照片（Motion Photo）。旧缓存的 offset/length 仍兼容。 */
    val isMotionPhoto: Boolean
        get() = !isVideo &&
            (motionPhoto || legacyMotionPhotoHint || (motionOffset != null && motionLength != null))
}
