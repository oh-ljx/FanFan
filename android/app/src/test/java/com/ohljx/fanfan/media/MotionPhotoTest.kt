package com.ohljx.fanfan.media

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionPhotoTest {

    @Test
    fun detectChannel_findsDistantVideoWhenFtypCrossesReverseScanBlock() {
        val photoLength = 256L * 1024 + 37L
        // ftyp 从反扫分块边界后第 2 字节开始，box 长度字段落在前一块中。
        val videoLength = 128L * 64 * 1024 + 2L
        withTempMotionFile(photoLength, videoLength) { file ->
            FileInputStream(file).channel.use { channel ->
                assertEquals(
                    photoLength to videoLength,
                    MotionPhoto.detectChannel(channel, channel.size()),
                )
            }
        }
    }

    @Test
    fun detectChannel_prefersGCameraOffsetOverLaterFtypInsideVideoPayload() {
        val photoLength = 384L * 1024 + 19L
        val videoLength = 8L * 1024 * 1024 + 701L
        val file = File.createTempFile("motion_xmp_", ".jpg")
        try {
            RandomAccessFile(file, "rw").use { output ->
                val xmp = """
                    <x:xmpmeta xmlns:x="adobe:ns:meta/">
                      <rdf:Description GCamera:MicroVideo="1"
                          GCamera:MicroVideoOffset="$videoLength" />
                    </x:xmpmeta>
                """.trimIndent().toByteArray(Charsets.UTF_8)
                output.writeByte(0xFF)
                output.writeByte(0xD8)
                output.writeByte(0xFF)
                output.writeByte(0xE1)
                output.writeShort(xmp.size + 2)
                output.write(xmp)
                output.writeByte(0xFF)
                output.writeByte(0xDA)
                output.setLength(photoLength + videoLength)
                writeFtyp(output, photoLength)
                // 没有 XMP 优先级时，反扫会先撞到这个看似合法但位于视频载荷内的 box。
                writeFtyp(output, output.length() - 128L * 1024)
            }

            FileInputStream(file).channel.use { channel ->
                assertEquals(
                    photoLength to videoLength,
                    MotionPhoto.detectChannel(channel, channel.size()),
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun videoRangeFromXmp_usesContainerLengthAndPrimaryPadding() {
        val primaryLength = 1_000_000L
        val primaryPadding = 16L
        val videoLength = 7_500_000L
        val trailingDepthLength = 2_048L
        val fileSize = primaryLength + primaryPadding + videoLength + trailingDepthLength
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"
                        Item:Length="0" Item:Padding="$primaryPadding" />
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto"
                        Item:Length="$videoLength" Item:Padding="0" />
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Mime="application/octet-stream" Item:Semantic="Depth"
                        Item:Length="$trailingDepthLength" Item:Padding="0" />
                  </rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </x:xmpmeta>
        """.trimIndent()

        assertEquals(
            (primaryLength + primaryPadding) to videoLength,
            MotionPhoto.videoRangeFromXmp(xmp, fileSize),
        )
    }

    @Test
    fun locateVideo_marksMicroVideoOffsetFormatAsNeedingExtraction() {
        // 小米格式：XMP 只写 GCamera:MicroVideoOffset，没有规范 Container:Directory
        val photoLength = 384L * 1024 + 19L
        val videoLength = 8L * 1024 * 1024 + 701L
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:Description GCamera:MicroVideo="1"
                  GCamera:MicroVideoOffset="$videoLength" />
            </x:xmpmeta>
        """.trimIndent()
        withXmpMotionFile(xmp, photoLength, videoLength) { file ->
            FileInputStream(file).channel.use { channel ->
                val located = requireNotNull(MotionPhoto.locateVideo(channel, channel.size()))
                assertEquals(photoLength, located.offset)
                assertEquals(videoLength, located.length)
                assertEquals(MotionPhoto.LocationSource.XMP_MICRO_VIDEO_OFFSET, located.source)
            }
        }
    }

    @Test
    fun locateVideo_marksContainerDirectoryFormatAsDirectlyPlayable() {
        val photoLength = 256L * 1024 + 11L
        val videoLength = 128L * 1024
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <Container:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"
                        Item:Length="0" Item:Padding="0" />
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto"
                        Item:Length="$videoLength" Item:Padding="0" />
                  </rdf:li>
                </rdf:Seq>
              </Container:Directory>
            </x:xmpmeta>
        """.trimIndent()
        withXmpMotionFile(xmp, photoLength, videoLength) { file ->
            FileInputStream(file).channel.use { channel ->
                val located = requireNotNull(MotionPhoto.locateVideo(channel, channel.size()))
                assertEquals(photoLength, located.offset)
                assertEquals(videoLength, located.length)
                assertEquals(MotionPhoto.LocationSource.XMP_DIRECTORY, located.source)
            }
        }
    }

    @Test
    fun locateVideo_marksPureAppendedMp4AsReverseScan() {
        withTempMotionFile(256L * 1024 + 37L, 128L * 1024) { file ->
            FileInputStream(file).channel.use { channel ->
                assertEquals(
                    MotionPhoto.LocationSource.REVERSE_SCAN,
                    MotionPhoto.locateVideo(channel, channel.size())?.source,
                )
            }
        }
    }

    private inline fun withXmpMotionFile(
        xmp: String,
        photoLength: Long,
        videoLength: Long,
        block: (File) -> Unit,
    ) {
        val file = File.createTempFile("motion_xmp_", ".jpg")
        try {
            RandomAccessFile(file, "rw").use { output ->
                val xmpBytes = xmp.toByteArray(Charsets.UTF_8)
                output.writeByte(0xFF)
                output.writeByte(0xD8)
                output.writeByte(0xFF)
                output.writeByte(0xE1)
                output.writeShort(xmpBytes.size + 2)
                output.write(xmpBytes)
                output.writeByte(0xFF)
                output.writeByte(0xDA)
                output.setLength(photoLength + videoLength)
                writeFtyp(output, photoLength)
            }
            block(file)
        } finally {
            file.delete()
        }
    }

    private inline fun withTempMotionFile(
        photoLength: Long,
        videoLength: Long,
        block: (File) -> Unit,
    ) {
        val file = File.createTempFile("motion_scan_", ".jpg")
        try {
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(photoLength + videoLength)
                output.seek(0L)
                output.writeByte(0xFF)
                output.writeByte(0xD8)
                writeFtyp(output, photoLength)
            }
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun writeFtyp(output: RandomAccessFile, offset: Long) {
        output.seek(offset)
        output.writeInt(24)
        output.writeBytes("ftyp")
        output.writeBytes("isom")
        output.writeInt(0)
        output.writeBytes("isom")
        output.writeBytes("mp42")
    }
}
