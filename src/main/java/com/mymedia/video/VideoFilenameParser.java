package com.mymedia.video;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从文件路径推断标题、季号、集号、年份与画质。
 *
 * <p>分层策略：先从文件名取季集，再从目录名补季号，最后清洗出标题。任何一层失败都不影响其他层；
 * 解析器不因非法或不完整路径抛出异常，最差情况是返回去掉扩展名的原文件名作为标题。
 */
final class VideoFilenameParser {

    /** S01E05 / s1e13 */
    private static final Pattern SEASON_EPISODE =
            Pattern.compile("(?i)s(\\d{1,3})[\\s._-]*e(\\d{1,4})");

    /** 目录名中的季号：Season 3 / 第2季 / S03 */
    private static final Pattern SEASON_IN_DIR =
            Pattern.compile("(?i)(?:season[\\s._-]*|第\\s*|s)(\\d{1,3})\\s*(?:季)?");

    /** 文件名中的独立集号：E07 / 第11话 / [08] */
    private static final Pattern EPISODE_ONLY =
            Pattern.compile("(?i)(?:\\be(\\d{1,4})\\b|第\\s*(\\d{1,4})\\s*[话話集]|\\[(\\d{1,4})\\])");

    /** 圆括号或独立词形式的年份 */
    private static final Pattern YEAR = Pattern.compile("(?<![\\d])(19\\d{2}|20\\d{2})(?![\\d])");

    /** 画质标记 */
    private static final Pattern QUALITY = Pattern.compile("(?i)\\b(2160p|1080p|720p|480p|4k)\\b");

    /** 方括号标签：字幕组、压制组、画质标记等 */
    private static final Pattern BRACKET_TAG = Pattern.compile("\\[[^\\]]*\\]|\\([^)]*\\)");

    private VideoFilenameParser() {
    }

    static ParsedVideoName parse(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        String fileName = lastSlash < 0 ? normalized : normalized.substring(lastSlash + 1);
        String directories = lastSlash < 0 ? "" : normalized.substring(0, lastSlash);
        String baseName = stripExtension(fileName);

        Integer season = null;
        Integer episode = null;

        // 第一优先：文件名里的 SxxExx，季集一次拿全。
        Matcher seasonEpisode = SEASON_EPISODE.matcher(baseName);
        if (seasonEpisode.find()) {
            season = parseIntOrNull(seasonEpisode.group(1));
            episode = parseIntOrNull(seasonEpisode.group(2));
        }

        // 季号退而求其次从目录名取。
        if (season == null && !directories.isEmpty()) {
            season = seasonFromDirectories(directories);
        }

        // 集号退而求其次从文件名的独立模式取。
        if (episode == null) {
            episode = episodeFrom(baseName);
        }

        Integer year = yearFrom(baseName);
        String quality = qualityFrom(baseName);
        String title = cleanTitle(baseName, directories);

        return new ParsedVideoName(title, season, episode, year, quality);
    }

    private static Integer seasonFromDirectories(String directories) {
        String[] segments = directories.split("/", -1);
        // 从最靠近文件的目录往外找，越近的越可能是季目录。
        for (int i = segments.length - 1; i >= 0; i--) {
            Matcher matcher = SEASON_IN_DIR.matcher(segments[i]);
            if (matcher.find()) {
                Integer value = parseIntOrNull(matcher.group(1));
                if (value != null && value <= 100) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Integer episodeFrom(String baseName) {
        Matcher matcher = EPISODE_ONLY.matcher(baseName);
        while (matcher.find()) {
            for (int group = 1; group <= 3; group++) {
                if (matcher.group(group) != null) {
                    return parseIntOrNull(matcher.group(group));
                }
            }
        }
        return null;
    }

    private static Integer yearFrom(String baseName) {
        // 先去掉画质标记，避免 2160p 里的 2160 之类的干扰。
        String withoutQuality = QUALITY.matcher(baseName).replaceAll(" ");
        Matcher matcher = YEAR.matcher(withoutQuality);
        return matcher.find() ? parseIntOrNull(matcher.group(1)) : null;
    }

    private static String qualityFrom(String baseName) {
        Matcher matcher = QUALITY.matcher(baseName);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    /** 清洗标题，并在清洗结果为空时逐级回落，保证标题始终非空。 */
    private static String cleanTitle(String baseName, String directories) {
        String work = BRACKET_TAG.matcher(baseName).replaceAll(" ");
        work = SEASON_EPISODE.matcher(work).replaceAll(" ");
        work = EPISODE_ONLY.matcher(work).replaceAll(" ");
        work = QUALITY.matcher(work).replaceAll(" ");
        work = YEAR.matcher(work).replaceAll(" ");
        work = work.replaceAll(
                "(?i)\\b(bluray|bdrip|webrip|web-dl|hdtv|x264|x265|h264|h265|hevc|aac|flac)\\b", " ");
        work = work.replaceAll("[._]+", " ");
        work = work.replaceAll("\\s{2,}", " ").trim();
        work = work.replaceAll("^[\\s\\-–—]+|[\\s\\-–—]+$", "").trim();

        if (!work.isEmpty()) {
            return work;
        }

        // 文件名被清空了（例如整个名字就是 S01E05），用最靠近的非季目录名。
        String[] segments = directories.split("/", -1);
        for (int i = segments.length - 1; i >= 0; i--) {
            String candidate = segments[i].trim();
            if (!candidate.isEmpty() && !SEASON_IN_DIR.matcher(candidate).matches()) {
                return candidate;
            }
        }

        String fallback = baseName.trim();
        return fallback.isEmpty() ? "Untitled" : fallback;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static Integer parseIntOrNull(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
