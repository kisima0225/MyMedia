package com.mymedia.metadata;

import java.util.Locale;

/**
 * 标签名 → slug。
 *
 * <p>slug 在本项目里的唯一用途是给 {@code (domain, slug)} 做唯一键，
 * 让「科幻」「科幻！」「 科幻 」被认成同一个标签。
 * <b>不做音译</b>——那需要一张词表或一个外部库，而这里根本不需要 slug 可读成 ASCII。
 *
 * <p>纯逻辑，无依赖。
 */
final class TagSlug {

    private TagSlug() {
    }

    static String of(String name) {
        if (name == null) {
            throw new IllegalArgumentException("标签名不能为空");
        }
        StringBuilder builder = new StringBuilder(name.length());
        name.toLowerCase(Locale.ROOT).codePoints().forEach(codePoint -> {
            if (Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(codePoint);
            } else {
                // 空白、标点、已有的连字符统统折叠成一个分隔符
                builder.append('-');
            }
        });

        String slug = builder.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            // 全是标点的名字做不了唯一键，当场拒绝而不是存一个空 slug
            throw new IllegalArgumentException("标签名里没有可用字符: " + name);
        }
        return slug;
    }
}
