package com.mymedia.image;

/**
 * 用户对节点阅读方式的覆盖。
 *
 * <p>系统的自动判定是「有直属图片就能读，有子节点就能浏览，两者皆有就都给」。
 * 但组织方式是高度个人化的，自动判定必然有猜错的时候，
 * 因此保留用户随时推翻的能力（spec §6.4）。
 */
public enum ImageReadingMode {
    /** 按 direct_page_count / child_node_count 自动判定。 */
    AUTO,
    /** 强制当作一本书：只给阅读入口，页 = 整棵子树的图片按目录顺序展开。 */
    FORCE_BOOK,
    /** 强制当作文件夹：只给浏览入口，即使有直属图片也不直接进阅读器。 */
    FORCE_FOLDER
}