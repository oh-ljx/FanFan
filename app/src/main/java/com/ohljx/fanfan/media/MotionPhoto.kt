package com.ohljx.fanfan.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * 实况照片（Motion Photo）工具：检测 JPEG 尾部内嵌的 MP4 并抽取成临时文件。
 * Google Pixel / 三星等机型的实况照片结构都是 JPEG + 追加的 MP4，
 * 通过从文件尾部向前找最后一个 "ftyp" box 定位视频段。
 * 小米的动态照片同样是 JPEG 尾部追加 MP4，但 XMP 只写 MicroVideoOffset（或不写），
 * 其 XMP 结构常包含多个 rdf:Description，Media3 的严格解析会失败，需要抽取后再播。
 */
object MotionPhoto {

    private val FTYP = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
    private const val MIN_VIDEO_BYTES = 64L * 1024
    private const val REVERSE_SCAN_BLOCK_BYTES = 64 * 1024
    private const val REVERSE_SCAN_OVERLAP_BYTES = 7

    /**
     * 兜底只用于用户当前刷到的旧格式候选；
     * 512 MiB 足以覆盖远大于常见实况视频的尾段。
     */
    private const val MAX_REVERSE_SCAN_BYTES = 512L * 1024 * 1024

    /** JPEG 的 XMP 位于 SOS 图像数据之前；限制损坏文件让我们在元数据区无限跳转。 */
    private const val MAX_JPEG_METADATA_BYTES = 16L * 1024 * 1024
    private const val MAX_XMP_PACKETS = 8

    private val legacyNamePatterns = listOf(
        Regex("^MVIMG_.*\\.(?:jpe?g|heic|heif|avif)$", RegexOption.IGNORE_CASE),
        Regex("^.*[_-]MP\\.(?:jpe?g|heic|heif|avif)$", RegexOption.IGNORE_CASE),
        Regex("^(?:MOTION|.*[_-]MOTION)(?:[_-].*)?\\.(?:jpe?g|heic|heif|avif)$", RegexOption.IGNORE_CASE),
    )
    private val extractLocks = ConcurrentHashMap<String, Any>()
    private val xmpMotionPatterns = listOf(
        Regex("(?:Camera:)?MotionPhoto\\s*=\\s*[\"']1[\"']", RegexOption.IGNORE_CASE),
        Regex("(?:Camera:)?MicroVideo\\s*=\\s*[\"']1[\"']", RegexOption.IGNORE_CASE),
        Regex("(?:Item:)?Semantic\\s*=\\s*[\"']MotionPhoto[\"']", RegexOption.IGNORE_CASE),
    )
    private val xmpDirectoryPattern = Regex(
        """<(?:G?Container):Directory\b[^>]*>(.*?)</(?:G?Container):Directory\s*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val xmpItemPattern = Regex(
        """<(?:G?Container):Item\b[^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val microVideoOffsetPattern = Regex(
        """(?:(?:G?Camera):)?MicroVideoOffset\s*=\s*["'](\d+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val mimeAttributePattern = xmpAttributePattern("Mime")
    private val semanticAttributePattern = xmpAttributePattern("Semantic")
    private val lengthAttributePattern = xmpAttributePattern("Length")
    private val paddingAttributePattern = xmpAttributePattern("Padding")

    private data class ContainerItem(
        val mime: String,
        val semantic: String,
        val length: Long,
        val padding: Long,
    )

    /**
     * MediaStore 在 Android 11+ 可直接返回图片的 XMP。这里只检查规范标记，
     * 不再为了识别实况照片逐个打开并扫描完整媒体文件。
     */
    fun hasMotionMetadata(xmp: ByteArray?): Boolean {
        if (xmp == null || xmp.isEmpty()) return false
        val metadata = xmp.toString(Charsets.UTF_8)
        return xmpMotionPatterns.any { it.containsMatchIn(metadata) } ||
            metadata.contains("MotionPhoto_Data", ignoreCase = true)
    }

    /** 旧系统或厂商未写入索引时采用规范/历史文件名提示，避免昂贵的全库文件扫描。 */
    fun isLikelyMotionPhotoName(displayName: String?): Boolean {
        if (displayName.isNullOrBlank()) return false
        return legacyNamePatterns.any { it.matches(displayName) }
    }

    /** 返回 (视频偏移, 视频长度)，不是实况照片返回 null。 */
    fun detect(resolver: ContentResolver, uri: Uri): Pair<Long, Long>? {
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val channel = input.channel
                    val size = pfd.statSize.takeIf { it > 0L } ?: channel.size()
                    return detectChannel(channel, size)
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** 内嵌视频段的定位方式。 */
    internal enum class LocationSource { XMP_DIRECTORY, XMP_MICRO_VIDEO_OFFSET, REVERSE_SCAN }

    internal data class LocatedVideo(val offset: Long, val length: Long, val source: LocationSource)

    /**
     * Media3 只能直接播放带规范 Container:Directory 目录的实况照片；小米等只写
     * MicroVideoOffset（或干脆没有 XMP、纯尾部追加 MP4）的实况照片，需要先把内嵌视频
     * 抽成临时文件再喂给播放器。
     */
    fun requiresExtraction(resolver: ContentResolver, uri: Uri): Boolean {
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val channel = input.channel
                    val size = pfd.statSize.takeIf { it > 0L } ?: channel.size()
                    val located = locateVideo(channel, size) ?: return false
                    return located.source != LocationSource.XMP_DIRECTORY
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * 先读取 JPEG APP1 中很小的 XMP 包并按规范计算资源位置；元数据缺失或损坏时，
     * 再用固定 64 KiB 缓冲区从文件尾分块反扫。此入口仍只由当前旧格式候选懒调用。
     */
    internal fun detectChannel(channel: FileChannel, fileSize: Long = channel.size()): Pair<Long, Long>? =
        locateVideo(channel, fileSize)?.let { it.offset to it.length }

    internal fun locateVideo(channel: FileChannel, fileSize: Long = channel.size()): LocatedVideo? {
        if (fileSize <= MIN_VIDEO_BYTES) return null

        val xmpPackets = readJpegXmpPackets(channel, fileSize)
        for (xmp in xmpPackets) {
            val directoryRange = directoryVideoRange(xmp, fileSize)
            if (directoryRange != null && isValidVideoRange(channel, fileSize, directoryRange)) {
                return LocatedVideo(directoryRange.first, directoryRange.second, LocationSource.XMP_DIRECTORY)
            }
            val microRange = microVideoRange(xmp, fileSize)
            if (microRange != null && isValidVideoRange(channel, fileSize, microRange)) {
                return LocatedVideo(microRange.first, microRange.second, LocationSource.XMP_MICRO_VIDEO_OFFSET)
            }
        }

        return scanBackwardForVideo(channel, fileSize)?.let { (offset, length) ->
            LocatedVideo(offset, length, LocationSource.REVERSE_SCAN)
        }
    }

    /** 按 Motion Photo V1 Container 目录或旧版 MicroVideoOffset 计算视频区间。 */
    internal fun videoRangeFromXmp(xmp: String, fileSize: Long): Pair<Long, Long>? =
        videoRangesFromXmp(xmp, fileSize).firstOrNull()

    private fun videoRangesFromXmp(xmp: String, fileSize: Long): List<Pair<Long, Long>> {
        if (fileSize <= 0L) return emptyList()
        val ranges = ArrayList<Pair<Long, Long>>(2)
        directoryVideoRange(xmp, fileSize)?.let(ranges::add)
        microVideoRange(xmp, fileSize)?.let { if (it !in ranges) ranges += it }
        return ranges
    }

    /** 旧版 GCamera:MicroVideoOffset：视频长度（从文件尾向前数）。 */
    private fun microVideoRange(xmp: String, fileSize: Long): Pair<Long, Long>? {
        if (fileSize <= 0L) return null
        val offsetFromEnd = microVideoOffsetPattern.find(xmp)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        if (offsetFromEnd == null || offsetFromEnd <= 0L || offsetFromEnd > fileSize) return null
        return (fileSize - offsetFromEnd) to offsetFromEnd
    }

    /** 与 Media3 的 MotionPhotoDescription 位置计算一致：从最后一个资源反向扣 Length。 */
    private fun directoryVideoRange(xmp: String, fileSize: Long): Pair<Long, Long>? {
        val directory = xmpDirectoryPattern.find(xmp)?.groupValues?.getOrNull(1) ?: return null
        val items = xmpItemPattern.findAll(directory).mapNotNull { match ->
            val tag = match.value
            val mime = attributeValue(mimeAttributePattern, tag) ?: return@mapNotNull null
            val semantic = attributeValue(semanticAttributePattern, tag) ?: return@mapNotNull null
            val length = attributeValue(lengthAttributePattern, tag)?.toLongOrNull() ?: 0L
            val padding = attributeValue(paddingAttributePattern, tag)?.toLongOrNull() ?: 0L
            if (length < 0L || padding < 0L) return@mapNotNull null
            ContainerItem(mime, semantic, length, padding)
        }.toList()
        if (items.size < 2) return null

        var cursor = fileSize
        var videoRange: Pair<Long, Long>? = null
        for (index in items.indices.reversed()) {
            val item = items[index]
            val end: Long
            val start: Long
            if (index == 0) {
                if (item.padding > cursor) return null
                start = 0L
                end = cursor - item.padding
            } else {
                if (item.length > cursor) return null
                end = cursor
                start = cursor - item.length
            }

            val isVideo = item.mime.equals("video/mp4", ignoreCase = true) ||
                item.mime.equals("video/quicktime", ignoreCase = true)
            if (isVideo && start < end) videoRange = start to (end - start)
            cursor = start
        }
        return videoRange
    }

    private fun xmpAttributePattern(localName: String): Regex = Regex(
        """(?:[A-Za-z_][\w.-]*:)?$localName\s*=\s*(["'])(.*?)\1""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private fun attributeValue(pattern: Regex, tag: String): String? =
        pattern.find(tag)?.groupValues?.getOrNull(2)?.trim()

    /** 只遍历 JPEG 元数据段；每个 APP1 按 JPEG 规范最多约 64 KiB。 */
    private fun readJpegXmpPackets(channel: FileChannel, fileSize: Long): List<String> {
        val oneByte = ByteBuffer.allocate(1)
        fun byteAt(position: Long): Int {
            oneByte.clear()
            return if (channel.read(oneByte, position) == 1) oneByte.array()[0].toInt() and 0xFF else -1
        }

        if (fileSize < 4L || byteAt(0L) != 0xFF || byteAt(1L) != 0xD8) return emptyList()
        val packets = ArrayList<String>(1)
        val metadataEnd = minOf(fileSize, MAX_JPEG_METADATA_BYTES)
        var position = 2L
        while (position + 1L < metadataEnd && packets.size < MAX_XMP_PACKETS) {
            if (byteAt(position) != 0xFF) break
            var marker: Int
            do {
                position++
                if (position >= metadataEnd) return packets
                marker = byteAt(position)
            } while (marker == 0xFF)
            position++

            if (marker < 0 || marker == 0x00 || marker == 0xD9 || marker == 0xDA) break
            if (marker == 0x01 || marker in 0xD0..0xD8) continue
            if (position + 2L > fileSize) break
            val high = byteAt(position)
            val low = byteAt(position + 1L)
            if (high < 0 || low < 0) break
            val segmentLength = (high shl 8) or low
            if (segmentLength < 2) break
            val payloadLength = segmentLength - 2
            val payloadStart = position + 2L
            val nextPosition = payloadStart + payloadLength
            if (nextPosition > fileSize) break

            if (marker == 0xE1 && payloadLength > 0) {
                val payload = ByteArray(payloadLength)
                if (readFully(channel, payloadStart, ByteBuffer.wrap(payload)) == payloadLength) {
                    val text = payload.toString(Charsets.UTF_8)
                    if (
                        text.contains("MicroVideoOffset", ignoreCase = true) ||
                        text.contains("Container:Directory", ignoreCase = true) ||
                        text.contains("GContainer:Directory", ignoreCase = true)
                    ) {
                        packets += text
                    }
                }
            }
            position = nextPosition
        }
        return packets
    }

    /** 固定内存反向扫描；重叠 7 字节确保 box 长度和 ftyp 跨分块边界时也不会漏。 */
    private fun scanBackwardForVideo(channel: FileChannel, fileSize: Long): Pair<Long, Long>? {
        val lowerBound = maxOf(0L, fileSize - MAX_REVERSE_SCAN_BYTES)
        val bytes = ByteArray(REVERSE_SCAN_BLOCK_BYTES + REVERSE_SCAN_OVERLAP_BYTES)
        var blockEnd = fileSize
        while (blockEnd > lowerBound) {
            val blockStart = maxOf(lowerBound, blockEnd - REVERSE_SCAN_BLOCK_BYTES)
            val readEnd = minOf(fileSize, blockEnd + REVERSE_SCAN_OVERLAP_BYTES)
            val requested = (readEnd - blockStart).toInt()
            val buffer = ByteBuffer.wrap(bytes, 0, requested)
            val read = readFully(channel, blockStart, buffer)
            for (index in read - FTYP.size downTo 4) {
                if (
                    bytes[index] == FTYP[0] &&
                    bytes[index + 1] == FTYP[1] &&
                    bytes[index + 2] == FTYP[2] &&
                    bytes[index + 3] == FTYP[3]
                ) {
                    val boxOffset = blockStart + index - 4L
                    val range = boxOffset to (fileSize - boxOffset)
                    if (isValidVideoRange(channel, fileSize, range)) return range
                }
            }
            blockEnd = blockStart
        }
        return null
    }

    private fun isValidVideoRange(
        channel: FileChannel,
        fileSize: Long,
        range: Pair<Long, Long>,
    ): Boolean {
        val (offset, length) = range
        if (offset <= 0L || length < MIN_VIDEO_BYTES || offset > fileSize - length) return false

        val headerBytes = ByteArray(24)
        val read = readFully(channel, offset, ByteBuffer.wrap(headerBytes))
        if (read < 12 || !matchesFtyp(headerBytes, 4)) return false

        val size32 = unsignedInt(headerBytes, 0)
        val brandOffset: Int
        val minimumBoxSize: Long
        val boxSize: Long
        if (size32 == 1L) {
            if (read < 20) return false
            boxSize = unsignedLong(headerBytes, 8) ?: return false
            brandOffset = 16
            minimumBoxSize = 24L
        } else {
            boxSize = size32
            brandOffset = 8
            minimumBoxSize = 16L
        }
        if (read < brandOffset + 4 || !isPlausibleFourCc(headerBytes, brandOffset)) return false
        if (boxSize != 0L && (boxSize < minimumBoxSize || boxSize > length)) return false
        return true
    }

    private fun matchesFtyp(bytes: ByteArray, offset: Int): Boolean =
        bytes[offset] == FTYP[0] &&
            bytes[offset + 1] == FTYP[1] &&
            bytes[offset + 2] == FTYP[2] &&
            bytes[offset + 3] == FTYP[3]

    private fun isPlausibleFourCc(bytes: ByteArray, offset: Int): Boolean {
        for (index in offset until offset + 4) {
            val value = bytes[index].toInt() and 0xFF
            if (value !in 0x20..0x7E) return false
        }
        return true
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun unsignedLong(bytes: ByteArray, offset: Int): Long? {
        val high = unsignedInt(bytes, offset)
        val low = unsignedInt(bytes, offset + 4)
        if (high > 0x7FFF_FFFFL) return null
        return (high shl 32) or low
    }

    private fun readFully(channel: FileChannel, start: Long, buffer: ByteBuffer): Int {
        var total = 0
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, start + total)
            if (read <= 0) break
            total += read
        }
        return total
    }

    /** 把内嵌视频段抽到缓存目录，返回可播放的文件。 */
    fun extract(context: Context, item: MediaItem): File? {
        val key = item.uri.toString()
        val lock = extractLocks.computeIfAbsent(key) { Any() }
        return try {
            synchronized(lock) { extractLocked(context, item, key) }
        } finally {
            // 只需要覆盖一次正在进行的单飞任务，完成后不能随浏览数量永久增长。
            extractLocks.remove(key, lock)
        }
    }

    /** 同一媒体单飞抽取，并用临时文件原子发布，播放器永远不会读到半成品。 */
    private fun extractLocked(context: Context, item: MediaItem, key: String): File? {
        val detected = if (item.motionOffset != null && item.motionLength != null) {
            item.motionOffset to item.motionLength
        } else {
            // 只会用于当前正在展示的旧格式候选，不会再在启动时遍历整个相册。
            detect(context.contentResolver, item.uri) ?: return null
        }
        val (offset, length) = detected
        val cacheKey = Integer.toHexString(key.hashCode())
        val out = File(context.cacheDir, "motion_${item.id}_$cacheKey.mp4")
        if (out.exists() && out.length() == length) return out
        val pending = File(context.cacheDir, ".motion_${item.id}_$cacheKey.tmp")
        if (pending.exists()) pending.delete()
        try {
            context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    input.channel.position(offset)
                    pending.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = length
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            remaining -= n
                        }
                    }
                }
            }
            if (pending.length() != length) return null
            if (out.exists() && !out.delete()) return null
            return if (pending.renameTo(out)) out else null
        } catch (_: Exception) {
            return null
        } finally {
            if (pending.exists()) pending.delete()
        }
    }
}
