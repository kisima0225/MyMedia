package com.mymedia.image;

/**
 * 节点的内容来源。
 *
 * <p>只有两种：磁盘上的真实目录，或一个压缩包（CBZ/ZIP）。
 * 压缩包是树的叶子——它内部的条目是页，不是子节点。
 */
public enum ImageSourceKind { DIRECTORY, ARCHIVE }