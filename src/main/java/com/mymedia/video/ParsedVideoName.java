package com.mymedia.video;

/**
 * 从文件路径推断出的元数据。
 *
 * <p>{@code title} 永远非空：解析不出任何模式时回落到去掉扩展名的文件名，
 * 没有可用文件名时使用固定兜底标题。
 *
 * @param title   推断出的标题
 * @param season  季号，无法判定时为 null
 * @param episode 集号，无法判定时为 null
 * @param year    发行年份，无法判定时为 null
 * @param quality 画质标记（如 1080p），无法判定时为 null
 */
record ParsedVideoName(String title, Integer season, Integer episode, Integer year, String quality) {
}
