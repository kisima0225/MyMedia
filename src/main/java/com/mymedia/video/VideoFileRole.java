package com.mymedia.video;

/**
 * 文件在条目中扮演的角色。
 *
 * <p>使得一个 {@code FLAT} 条目也能拥有多个文件：
 * 一部电影可以有 1080p 与 4K 两个版本、外加花絮与预告片。
 */
public enum VideoFileRole { PRIMARY, VERSION, EXTRA, SUBTITLE, TRAILER }
