package com.mymedia.preview;

/** 派生资源的种类。取值与 {@code derived_asset.kind} 的 CHECK 约束一一对应。 */
public enum DerivedAssetKind {

    /** 封面：视频抽帧 / 漫画首页 / 图集首图，等比缩到 640 宽。 */
    COVER("covers", "cover", "jpg"),

    /** 缩略图：从封面再缩到 320 宽，列表页用。 */
    THUMBNAIL("thumbs", "thumb", "jpg"),

    /** 进度条预览雪碧图：固定 100 帧、10 × 10 单张。 */
    SPRITE_SHEET("sprites", "sprite", "jpg"),

    /** 雪碧图的 WebVTT 索引，播放器按它把时间点映射到图块。 */
    SPRITE_VTT("sprites", "sprite", "vtt");

    private final String directory;
    private final String suffix;
    private final String extension;

    DerivedAssetKind(String directory, String suffix, String extension) {
        this.directory = directory;
        this.suffix = suffix;
        this.extension = extension;
    }

    String directory() {
        return directory;
    }

    /** {@code {sourceFileId}-cover.jpg} 这样的文件名。 */
    String fileName(Long sourceScannedFileId) {
        return sourceScannedFileId + "-" + suffix + "." + extension;
    }

    /** public：Task 6 的 {@code preview.web.AssetController} 在嵌套包里，需要读得到。 */
    public String contentType() {
        return this == SPRITE_VTT ? "text/vtt" : "image/jpeg";
    }
}