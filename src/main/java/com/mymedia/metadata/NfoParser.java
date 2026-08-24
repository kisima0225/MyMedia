package com.mymedia.metadata;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mymedia.shared.MetadataFields;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析本地元数据文件：Kodi / Jellyfin 的 {@code .nfo}（XML），
 * 以及本项目自己的 {@code metadata.json}。
 *
 * <p><b>纯逻辑，没有文件 I/O</b>：调用方读好字符串再喂进来，于是测试就是喂字符串。
 */
final class NfoParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** XML 标签 → 标准字段名。 */
    private static final Map<String, String> STANDARD_TAGS = Map.of(
            "title", MetadataFields.TITLE,
            "originaltitle", MetadataFields.ORIGINAL_TITLE,
            "plot", MetadataFields.SUMMARY,
            "premiered", MetadataFields.RELEASE_DATE,
            "rating", MetadataFields.RATING);

    /** XML 标签 → extras 键。 */
    private static final Map<String, String> EXTRA_TAGS = Map.of(
            "director", "director",
            "studio", "studio",
            "writer", "writer",
            "country", "country");

    private NfoParser() {
    }

    static ParsedNfo parseXml(String xml) {
        Document document = parseDocument(xml);
        Element root = document.getDocumentElement();

        Map<String, String> fields = new LinkedHashMap<>();
        STANDARD_TAGS.forEach((tag, field) -> putIfPresent(fields, field, firstText(root, tag)));

        // premiered 缺席时退回 year，补成当年 1 月 1 日，让 release_date 列有个能排序的值
        if (!fields.containsKey(MetadataFields.RELEASE_DATE)) {
            String year = firstText(root, "year");
            if (year != null && year.matches("\\d{4}")) {
                fields.put(MetadataFields.RELEASE_DATE, year + "-01-01");
            }
        }

        Map<String, String> extras = new LinkedHashMap<>();
        EXTRA_TAGS.forEach((tag, key) -> putIfPresent(extras, key, firstText(root, tag)));
        List<String> genres = allText(root, "genre");
        if (!genres.isEmpty()) {
            extras.put("genres", String.join(", ", genres));
        }

        return new ParsedNfo(fields, extras);
    }

    static ParsedNfo parseJson(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 metadata.json", e);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        MetadataFields.STANDARD.forEach(field -> putIfPresent(fields, field, text(root.get(field))));

        Map<String, String> extras = new LinkedHashMap<>();
        JsonNode extrasNode = root.get("extras");
        if (extrasNode != null && extrasNode.isObject()) {
            extrasNode.properties().forEach(entry ->
                    putIfPresent(extras, entry.getKey(), text(entry.getValue())));
        }

        return new ParsedNfo(fields, extras);
    }

    /**
     * 建一个禁用 DTD 的解析器。
     *
     * <p>{@code .nfo} 是用户放在媒体目录里的文件，内容不可信。默认配置下一个
     * {@code <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>} 就能读走
     * 服务器上的任意文件。<b>关掉 DOCTYPE 是这里唯一正确的默认值</b>——
     * 合法的 .nfo 从来不需要 DTD。
     */
    private static Document parseDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 .nfo：" + e.getMessage(), e);
        }
    }

    private static String firstText(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static List<String> allText(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            String text = node.getTextContent();
            if (text != null && !text.isBlank()) {
                values.add(text.trim());
            }
        }
        return values;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }
}
