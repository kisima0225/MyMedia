package com.mymedia.shared;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 刮削结果写回前的过滤。
 *
 * <p>只有两条规则，spec 7.2 的优先级由它和"链的顺序"共同表达，<b>不存在第三套机制</b>：
 * <ol>
 *   <li><b>跳过锁定字段</b>——用户编辑过的字段任何刮削都不得覆盖。</li>
 *   <li><b>跳过空值</b>——提供者没给出的字段不该把已有的好数据洗成空。</li>
 * </ol>
 *
 * <p>用户编辑走的是另一条路（{@code applyUserEdit}），<b>不经过本类</b>：
 * 用户就是权威，没有什么能拦住他改自己锁过的字段。
 */
public final class FieldMergePolicy {

    private FieldMergePolicy() {
    }

    public static Map<String, String> apply(Map<String, String> incoming,
                                            Collection<String> lockedFields) {
        Set<String> locked = Set.copyOf(lockedFields);
        Map<String, String> result = new LinkedHashMap<>();
        incoming.forEach((field, value) -> {
            if (value == null || value.isBlank() || locked.contains(field)) {
                return;
            }
            result.put(field, value);
        });
        return result;
    }
}
