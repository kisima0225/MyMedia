package com.mymedia.image;

/** 压缩包内的一个图片条目。{@code sizeBytes} 为 -1 表示归档未记录原始大小。 */
record ArchivePage(String entryName, long sizeBytes) {
}