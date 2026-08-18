package com.mymedia.scan;

/**
 * 物理文件的存在状态。
 *
 * <p>{@code MISSING} 表示上一轮扫描没有在磁盘上找到它。**绝不因此删除记录**——
 * 外接盘未挂载、网络存储临时不可达都会让整个目录「消失」，
 * 删除意味着用户的观看进度、收藏与手工元数据一并蒸发。
 */
public enum ScannedFileStatus { ACTIVE, MISSING }
