package com.mymedia.shared;

import java.util.Map;
import java.util.Set;

/**
 * 一个条目当前的元数据全貌，供编辑界面展示。
 *
 * <p>{@code fieldSources} 只用来展示"这个字段是谁写的"，<b>不参与任何判定</b>；
 * 覆盖保护全部由 {@code lockedFields} 表达。
 */
public record MetadataSnapshot(
        Map<String, String> fields,
        Map<String, String> fieldSources,
        Set<String> lockedFields,
        ScrapeStatus scrapeStatus,
        String scrapeSource,
        String scrapeSourceId) {
}
