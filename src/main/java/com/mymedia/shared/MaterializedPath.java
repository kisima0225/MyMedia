package com.mymedia.shared;

import java.util.ArrayList;
import java.util.List;

/**
 * 树节点的物化路径运算，形如 {@code /1/17/93/}。
 *
 * <p>存物化路径的收益：子树查询是一次前缀索引扫描（{@code LIKE '/1/17/%'}），
 * 面包屑导航直接解析路径即可得到全部祖先 id，都不需要递归查询。
 *
 * <p>代价：移动子树时必须重写整棵子树的路径。{@link #rewrite} 就是为此存在——
 * 数据库端用一条前缀替换 UPDATE 完成，不可逐层递归。
 *
 * <p>{@code video} 与 {@code image} 两个域各有自己的树表，但共用本工具：
 * <b>复用算法，不复用模型</b>。
 */
public final class MaterializedPath {

    private static final String SEPARATOR = "/";

    private MaterializedPath() {
    }

    public static String rootPath() {
        return SEPARATOR;
    }

    public static String childOf(String parentPath, Long parentId) {
        requireWellFormed(parentPath);
        if (parentId == null) {
            throw new IllegalArgumentException("父节点 id 不能为 null");
        }
        return parentPath + parentId + SEPARATOR;
    }

    public static List<Long> ancestorIds(String path) {
        requireWellFormed(path);
        List<Long> ids = new ArrayList<>();
        for (String segment : path.split(SEPARATOR)) {
            if (!segment.isEmpty()) {
                ids.add(Long.valueOf(segment));
            }
        }
        return ids;
    }

    public static int depthOf(String path) {
        return ancestorIds(path).size();
    }

    /**
     * 子树查询的 LIKE 前缀。
     *
     * <p><b>必须以斜杠收尾</b>：否则 {@code /1/17} 会误匹配 {@code /1/170/}，
     * 把不相干的子树卷进查询与移动操作。这是物化路径最经典的 bug。
     */
    public static String subtreePrefix(String path) {
        requireWellFormed(path);
        return path;
    }

    /**
     * 子树移动时重写路径：把 {@code oldPrefix} 换成 {@code newPrefix}。
     * 不以 {@code oldPrefix} 开头的路径原样返回。
     */
    public static String rewrite(String path, String oldPrefix, String newPrefix) {
        requireWellFormed(path);
        requireWellFormed(oldPrefix);
        requireWellFormed(newPrefix);
        if (!path.startsWith(oldPrefix)) {
            return path;
        }
        return newPrefix + path.substring(oldPrefix.length());
    }

    private static void requireWellFormed(String path) {
        if (path == null || !path.startsWith(SEPARATOR) || !path.endsWith(SEPARATOR)) {
            throw new IllegalArgumentException("物化路径必须以斜杠开头并以斜杠结尾: " + path);
        }
    }
}
