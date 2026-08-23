package com.mymedia.preview;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 把 {@code ffprobe -print_format json -show_format -show_streams} 的输出
 * 解析成 {@link FfprobeOutput}。
 *
 * <p><b>纯函数，没有任何 I/O</b>——因此它的测试是喂字符串的单元测试，
 * 不需要机器上装 ffprobe。这正是把进程调用收敛到 {@link CommandRunner} 的收益。
 */
final class FfprobeParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FfprobeParser() {
    }

    static FfprobeOutput parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 ffprobe 输出: " + preview(json), e);
        }
        if (!root.isObject() || !root.has("format")) {
            throw new IllegalArgumentException("ffprobe 输出缺少 format 节: " + preview(json));
        }

        JsonNode format = root.path("format");
        JsonNode video = firstStreamOfType(root, "video");
        JsonNode audio = firstStreamOfType(root, "audio");

        Double duration = asDouble(format.path("duration"));
        if (duration == null) {
            duration = asDouble(video.path("duration"));
        }

        return new FfprobeOutput(
                duration == null ? null : (int) Math.round(duration),
                asInt(video.path("width")),
                asInt(video.path("height")),
                textOf(video.path("codec_name")),
                textOf(audio.path("codec_name")),
                asLong(format.path("bit_rate")),
                firstToken(textOf(format.path("format_name"))),
                json);
    }

    private static JsonNode firstStreamOfType(JsonNode root, String codecType) {
        for (JsonNode stream : root.path("streams")) {
            if (codecType.equals(stream.path("codec_type").asString(null))) {
                return stream;
            }
        }
        return MAPPER.nullNode();
    }

    /** {@code "mov,mp4,m4a,3gp,3g2,mj2"} → {@code "mov"}。 */
    private static String firstToken(String formatName) {
        if (formatName == null) {
            return null;
        }
        int comma = formatName.indexOf(',');
        return comma < 0 ? formatName : formatName.substring(0, comma);
    }

    private static String textOf(JsonNode node) {
        String value = node.asString(null);
        return value == null || value.isBlank() || "N/A".equals(value) ? null : value;
    }

    private static Integer asInt(JsonNode node) {
        String value = textOf(node);
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long asLong(JsonNode node) {
        String value = textOf(node);
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double asDouble(JsonNode node) {
        String value = textOf(node);
        try {
            return value == null ? null : Double.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String preview(String json) {
        if (json == null) {
            return "(null)";
        }
        return json.length() <= 200 ? json : json.substring(0, 200) + "…";
    }
}