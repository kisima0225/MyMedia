package com.mymedia.metadata;

import java.util.Map;

/** 本地元数据文件的解析结果。 */
record ParsedNfo(Map<String, String> fields, Map<String, String> extras) {
}
