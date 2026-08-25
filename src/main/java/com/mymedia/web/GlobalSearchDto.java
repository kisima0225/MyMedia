package com.mymedia.web;

import com.mymedia.image.ImageSearchHit;
import com.mymedia.video.VideoSearchHit;

import java.util.List;

final class GlobalSearchDto {

    private GlobalSearchDto() {
    }

    /**
     * 两个域各一个数组。
     *
     * <p>没有命中的那一边返回<b>空数组而不是省略字段</b>——前端的两个分区是常驻的，
     * 缺字段只会让它多写一堆判空。
     */
    record Response(String query, List<VideoSearchHit> video, List<ImageSearchHit> image) {
    }
}
