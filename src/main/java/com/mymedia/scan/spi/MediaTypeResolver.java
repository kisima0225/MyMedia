package com.mymedia.scan.spi;

import java.util.Optional;

/**
 * 领域模块为 scan 提供扩展名分类的服务提供接口。
 *
 * <p>传入的扩展名已经统一为小写且不含点号。实现只应根据扩展名做确定性
 * 白名单判断，不应读取文件内容或执行内容嗅探。
 */
@FunctionalInterface
public interface MediaTypeResolver {

    /**
     * 尝试分类一个内置白名单未识别的扩展名。
     *
     * @param extension 小写、不含点号的文件扩展名
     * @return 能处理时返回分类，否则返回 {@link Optional#empty()}
     */
    Optional<MediaKind> resolve(String extension);
}
