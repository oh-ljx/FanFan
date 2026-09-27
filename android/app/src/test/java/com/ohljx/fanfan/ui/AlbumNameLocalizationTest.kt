package com.ohljx.fanfan.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumNameLocalizationTest {

    @Test
    fun wellKnownBucketsMapToChineseNames() {
        assertEquals("相机", localizedAlbumName("Camera"))
        assertEquals("截屏", localizedAlbumName("Screenshots"))
        assertEquals("截屏", localizedAlbumName("screenshots"))
        assertEquals("微信", localizedAlbumName("WeiXin"))
        assertEquals("下载", localizedAlbumName("Download"))
        assertEquals("抖音", localizedAlbumName("douyin"))
    }

    @Test
    fun unknownNamesStayAsTheyAre() {
        assertEquals("旅行 2024", localizedAlbumName("旅行 2024"))
        assertEquals("My Album", localizedAlbumName("My Album"))
    }
}
